package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.ErrorCodes
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakConstants
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.UrlExpiredException
import io.github.nihildigit.pikpak.isUsable
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile

/**
 * Internal HTTP layer. Handles what every PikPak request needs:
 *  1. Rate limiting (token bucket — surface area for tuning lives on the client).
 *  2. Standard headers (User-Agent, Authorization, X-Device-Id, X-Captcha-Token).
 *  3. Authentication on demand: a request with no session, or one past its
 *     expiry, logs in first; an HTTP 401 re-authenticates and retries once.
 *  4. Failure recovery: transient HTTP/network and 5xx/429 → backoff retry,
 *     except where the server may already have acted (see [execute]);
 *     PikPak `error_code=9` (captcha required) → refresh captcha for the action
 *     and retry exactly once before bubbling up.
 *
 * Two request families with deliberately different pipelines:
 *  - [request] / [requestRaw] talk to the PikPak API. Rate limited, PikPak
 *    headers attached, response body fully read as JSON.
 *  - [sendRaw] talks to signed CDN/OSS URLs. NOT rate limited (the token
 *    bucket exists to stay under the API's captcha wall; the CDN has no such
 *    wall and a 16-way parallel fetch would otherwise serialise itself), no
 *    PikPak headers, and the body is handed to the caller as a live stream.
 *
 * Auth flows (signin / refresh / captcha-init) bypass [request] and call
 * [requestRaw] directly so they don't recursively trigger captcha handling.
 */
internal class HttpEngine(
    private val client: HttpClient,
    private val pikpak: PikPakClient,
    /** Deferred so the CDN client is only built if CDN traffic actually happens. */
    private val cdnClient: () -> HttpClient,
) {
    suspend fun request(
        method: HttpMethod,
        url: String,
        captchaAction: String? = null,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): JsonElement {
        var captchaRetried = false
        var reauthRetried = false
        while (true) {
            // No session yet, or one past its own expiry: log in before sending rather than
            // spend a request learning it from a 401. That 401 used to be the only way in for
            // a request made before login(), and its recovery skipped the stored session.
            val current = pikpak.state.session
            // Both snapshots are taken before the request goes out, not when
            // its failure comes back: by then another coroutine may already
            // have replaced the value, and a snapshot of the replacement
            // would not match the state that was actually rejected. The
            // headers are built from the same snapshots, never re-read: a
            // wait in the rate limiter or a retry inside execute would
            // otherwise send a newer value than the one the refresh below
            // is keyed on, and the refresh would skip itself.
            val sessionUsed = if (current != null && current.isUsable()) current else pikpak.mutex.withLock { pikpak.auth.loginLocked() }
            val captchaUsed = pikpak.state.captchaToken
            val element = try {
                requestRaw(method, url) {
                    applyAuthHeaders(sessionUsed, captchaUsed)
                    configure()
                }
            } catch (e: PikPakException) {
                // The access token has not reached its own expiry or it would
                // have been replaced above, so the server disagreeing is the
                // only signal available. Re-auth once; a second 401 is a real
                // authorization failure and belongs to the caller.
                if (e.httpStatus == 401 && !reauthRetried) {
                    reauthRetried = true
                    pikpak.mutex.withLock { pikpak.auth.reauthenticateLocked(sessionUsed) }
                    continue
                }
                // Captcha rejection arrives as HTTP 400 with error_code 9 in
                // the body, not as a 2xx envelope; same recovery as below.
                if (e.isCaptchaRequired && captchaAction != null && !captchaRetried) {
                    pikpak.auth.refreshCaptchaToken(captchaAction, captchaUsed)
                    captchaRetried = true
                    continue
                }
                throw e
            }
            val error = element.envelopeError() ?: return element
            if (error.isCaptchaRequired && captchaAction != null && !captchaRetried) {
                pikpak.auth.refreshCaptchaToken(captchaAction, captchaUsed)
                captchaRetried = true
                continue
            }
            throw error
        }
    }

    /**
     * PikPak API request that returns the parsed JSON envelope without checking
     * `error_code`. Adds the standard PikPak headers (UA + X-Device-Id) — used
     * by auth bootstrap and any caller that needs to inspect the envelope itself.
     *
     * A non-2xx status is an exception even when the body parses: PikPak's
     * error pages are valid JSON with none of the fields the models expect, so
     * decoding one yields an all-defaults object that reads as success.
     */
    suspend fun requestRaw(
        method: HttpMethod,
        url: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): JsonElement = execute(
        client = client,
        method = method,
        url = url,
        rateLimited = true,
        configure = {
            headers {
                set(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
                set("X-Device-Id", pikpak.deviceId)
            }
            configure()
        },
    ) { response ->
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // A 4xx from the API usually still carries the normal error
            // envelope: captcha_invalid (error_code 9) rides on HTTP 400,
            // refresh-token rejection on 401. Keep those fields on the
            // exception so request() can refresh the captcha and AuthApi can
            // fall back to a fresh signin. 0.5.0 threw a bare "HTTP 400" here,
            // which silently disabled both recoveries.
            val envelope = parseErrorEnvelopeOrNull(text)
            throw envelope?.envelopeError(response.status.value, response.headerMap())
                ?: PikPakException(
                    errorCode = -1,
                    errorMessage = "HTTP ${response.status.value}",
                    httpStatus = response.status.value,
                    headers = response.headerMap(),
                    rawBody = text.truncateForError(),
                )
        }
        // Some PikPak endpoints (e.g. DELETE) legitimately return an empty 2xx body.
        if (text.isBlank()) return@execute JsonObject(emptyMap())
        try {
            pikpak.json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            // SerializationException is a third-party type; letting it escape
            // would make it part of the SDK's public failure surface.
            throw PikPakException(
                errorCode = -1,
                errorMessage = "malformed JSON in a 2xx response",
                httpStatus = response.status.value,
                headers = response.headerMap(),
                rawBody = text.truncateForError(),
                cause = e,
            )
        }
    }

    /**
     * Raw external HTTP for the signed CDN/OSS URLs PikPak hands out.
     *
     * Does NOT add PikPak-specific headers — those URLs reject auth headers —
     * and does NOT take a rate-limiter token. Sends `Accept: * / *` explicitly
     * so a ContentNegotiation plugin on an injected client cannot slip in
     * `Accept: application/json`, which the CDN answers with 406.
     *
     * The response is delivered to [block] as a live exchange: the body has not
     * been read and the connection is still open. Everything the caller needs
     * must be taken inside [block]; the connection is released on return.
     *
     * Throws [UrlExpiredException] on 401/403 before [block] runs.
     */
    suspend fun <T> sendRaw(
        method: HttpMethod,
        url: String,
        configure: HttpRequestBuilder.() -> Unit = {},
        block: suspend (HttpResponse) -> T,
    ): T = execute(
        client = cdnClient(),
        method = method,
        url = url,
        rateLimited = false,
        configure = {
            accept(ContentType.Any)
            configure()
        },
    ) { response ->
        val status = response.status.value
        if (status == 401 || status == 403) {
            throw UrlExpiredException(
                url = url,
                httpStatus = status,
                headers = response.headerMap(),
                rawBody = response.bodyAsText().truncateForError(),
            )
        }
        block(response)
    }

    /**
     * Shared request pipeline. Retries the *request* on transient transport
     * errors and on 5xx/429 responses; never retries once [block] has started,
     * because a block may have already written bytes somewhere. On the last
     * attempt a 5xx is handed to [block] instead, which is where RangeReader
     * sees a 503 as backpressure.
     *
     * A POST is replayed only when the server cannot have acted on it: a 429
     * or 503, which refuse the request, or a failure before the request was
     * sent (see [failedBeforeSending]) — a TLS handshake the route drops is
     * the common one on a proxied line. A
     * read timeout, a reset after the body was sent or a 502 from a gateway
     * whose backend committed all leave the effect unknown, and replaying a
     * createFolder, an offline task or a multipart completion then makes a
     * duplicate, costs quota twice, or finds the upload already finished and
     * reports it gone. The other methods this SDK sends read or overwrite, and
     * are replayed on all of them.
     */
    private suspend fun <T> execute(
        client: HttpClient,
        method: HttpMethod,
        url: String,
        rateLimited: Boolean,
        configure: HttpRequestBuilder.() -> Unit,
        block: suspend (HttpResponse) -> T,
    ): T {
        val policy = pikpak.retryPolicy
        val replayable = method != HttpMethod.Post
        var attempt = 0
        while (true) {
            if (rateLimited) pikpak.rateLimiter.acquire()
            var blockEntered = false
            try {
                return client.prepareRequest(url) {
                    this.method = method
                    this.expectSuccess = false
                    configure()
                }.execute { response ->
                    val status = response.status.value
                    val retryable = status == 429 || status == 503 || (replayable && status >= 500)
                    if (retryable && attempt < policy.maxAttempts - 1) {
                        throw RetryableStatus(status, response.retryAfter())
                    }
                    blockEntered = true
                    block(response)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (blockEntered) throw t
                if (t is RetryableStatus) {
                    val waitFor = t.retryAfter ?: policy.delayFor(attempt)
                    recordRetry(t.status, waitFor)
                    delay(waitFor)
                    attempt++
                    continue
                }
                val retryable = if (replayable) isTransient(t) else t.failedBeforeSending()
                if (!retryable || attempt >= policy.maxAttempts - 1) throw t
                val waitFor = policy.delayFor(attempt)
                recordRetry(null, waitFor)
                delay(waitFor)
                attempt++
            }
        }
    }

    /** Counted against the caller's own request, when it installed a counter; see [AttemptRetries]. */
    private suspend fun recordRetry(status: Int?, waited: Duration) {
        currentCoroutineContext()[AttemptRetries]?.record(status, waited)
    }

    /** Thrown only while retries remain, so it never leaves [execute]. */
    private class RetryableStatus(val status: Int, val retryAfter: Duration?) : RuntimeException("HTTP $status")

    private fun isTransient(t: Throwable): Boolean {
        if (t is UrlExpiredException) return false
        if (t is HttpRequestTimeoutException) return true
        if (t is ConnectTimeoutException) return true
        if (t is SocketTimeoutException) return true
        if (t is kotlinx.io.IOException) return true
        // Ktor's engine-layer I/O errors (CIO, OkHttp, Darwin) all extend
        // java.io.IOException on JVM and kotlinx.io.IOException on native,
        // but some engines also throw plain RuntimeException wrappers. Fall
        // back to a message-level check for the canonical transient signals.
        val message = t.message?.lowercase().orEmpty()
        return "connection reset" in message ||
            "connection closed" in message ||
            "broken pipe" in message ||
            "unexpected eof" in message
    }

    private fun HttpRequestBuilder.applyAuthHeaders(session: Session, captcha: String) {
        headers.set(HttpHeaders.Authorization, "Bearer ${session.accessToken}")
        if (captcha.isNotEmpty()) {
            headers.set("X-Captcha-Token", captcha)
        }
    }

    /** The body as a PikPak error envelope, or null when it is not one (non-JSON, or no numeric `error_code`). */
    private fun parseErrorEnvelopeOrNull(text: String): JsonObject? {
        if (text.isBlank()) return null
        val element = try {
            pikpak.json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return null
        }
        val obj = element as? JsonObject ?: return null
        val code = (obj["error_code"] as? JsonPrimitive)?.intOrNull ?: return null
        return if (code != ErrorCodes.OK) obj else null
    }

    companion object {
        // ContentNegotiation is intentionally NOT installed: it would auto-add an
        // `Accept: application/json` header that the OSS/CDN download endpoints
        // reject with 406. We parse JSON manually via bodyAsText() + Json.parseToJsonElement().
        //
        // An injected HttpClient must honour the same two constraints, plus one
        // more: no HttpRequestRetry (see below). If yours installs any of the
        // three, hand the SDK a separate client instead of your app-wide one.
        fun defaultClient(): HttpClient = HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                // This client carries JSON API calls only; bytes go over the CDN
                // client. Bounded, because sign-in and captcha requests run under
                // the client's auth mutex, and one that stalls holds up every
                // login and captcha refresh behind it for as long as it is let.
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 30_000
            }
            // HttpRequestRetry is intentionally NOT installed. The SDK's own
            // retry loop (driven by RetryPolicy) already handles transient
            // failures; stacking both plugins compounds backoff and multiplies
            // attempt counts in surprising ways.
            expectSuccess = false
        }
    }
}

/**
 * Retries [HttpEngine] performed for the one request the caller is in.
 *
 * The alternative was to read the client-wide counters before and after the
 * request and subtract. That is wrong the moment two requests overlap: the
 * window between the two reads contains every other connection's retries too,
 * so a request that retried nothing can be handed three of someone else's. A
 * context element cannot be misattributed, because the retry loop lives inside
 * `execute` and `execute` runs on the coroutine that called it.
 *
 * Unsynchronised on purpose. `execute` retries in a plain `while` loop on that
 * same coroutine — it never forks the retry to another one — and the one
 * installer, `RangeReader.pump`, wraps a single `streamRangeFromUrl` call whose
 * block only writes to its sink. Ktor does carry the caller's context into the
 * request pipeline, so engine coroutines can see this element, but nothing down
 * there calls [record].
 *
 * The counters are volatile for the one reader on another coroutine: RangeReader's
 * first-response deadline, which watches [statuses] to tell a host that answered
 * with a retried status from one that said nothing.
 */
internal class AttemptRetries : AbstractCoroutineContextElement(AttemptRetries) {
    companion object Key : CoroutineContext.Key<AttemptRetries>

    @Volatile
    var serverErrors: Int = 0
        private set

    @Volatile
    var rateLimited: Int = 0
        private set

    /** Responses the server did send, retried all the same. */
    val statuses: Int get() = serverErrors + rateLimited
    var transport: Int = 0
        private set
    var waited: Duration = Duration.ZERO
        private set

    fun record(status: Int?, waitedFor: Duration) {
        when {
            status == null -> transport++
            status == 429 -> rateLimited++
            status >= 500 -> serverErrors++
        }
        waited += waitedFor
    }
}

private const val ERROR_BODY_LIMIT = 2048

private fun String.truncateForError(): String =
    if (length <= ERROR_BODY_LIMIT) this else substring(0, ERROR_BODY_LIMIT) + "…(${length} bytes total)"

/**
 * The error a PikPak JSON envelope reports, or null when it reports none. Anything that is not
 * an object is data, not an envelope. One conversion for every reader of envelopes, so the
 * fields the exception's predicates read are the server's own wherever the envelope arrived.
 */
internal fun JsonElement.envelopeError(
    httpStatus: Int? = null,
    headers: Map<String, List<String>> = emptyMap(),
): PikPakException? {
    val obj = this as? JsonObject ?: return null
    val code = (obj["error_code"] as? JsonPrimitive)?.intOrNull ?: return null
    if (code == ErrorCodes.OK) return null
    return PikPakException(
        errorCode = code,
        errorMessage = (obj["error"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        errorDescription = (obj["error_description"] as? JsonPrimitive)?.contentOrNull,
        httpStatus = httpStatus,
        headers = headers,
        rawBody = obj.toString().truncateForError(),
    )
}

internal fun HttpResponse.headerMap(): Map<String, List<String>> =
    headers.entries().associate { it.key.lowercase() to it.value }

/**
 * Reads `Retry-After`. Only the delta-seconds form is honoured; the HTTP-date
 * form would need a parser and neither PikPak's API nor its CDN has been seen
 * to send one. Absurd values are clamped so a hostile header can't park a
 * coroutine for an hour.
 */
private fun HttpResponse.retryAfter(): Duration? {
    val raw = headers[HttpHeaders.RetryAfter]?.trim() ?: return null
    val seconds = raw.toLongOrNull() ?: return null
    if (seconds < 0) return null
    return seconds.coerceAtMost(30L).seconds
}

internal fun buildUrl(base: String, path: String, query: Map<String, String> = emptyMap()): String =
    buildUrl(base, path, query.toList())

/** Pairs instead of a map, for endpoints that take one key several times (`task_ids`). */
internal fun buildUrl(base: String, path: String, query: List<Pair<String, String>>): String {
    val full = base.trimEnd('/') + "/" + path.trimStart('/')
    if (query.isEmpty()) return full
    val qs = query.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
    val sep = if ('?' in full) '&' else '?'
    return "$full$sep$qs"
}

private fun encode(s: String): String = buildString(s.length) {
    for (c in s) when {
        // RFC 3986 unreserved set. Must match ASCII only — Char.isLetterOrDigit
        // returns true for Unicode letters on the JVM, which would leave
        // non-ASCII characters unencoded and mis-transmit UTF-8 bytes.
        c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' ||
            c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
        else -> for (b in c.toString().encodeToByteArray()) append('%').append(hex(b))
    }
}

private fun hex(b: Byte): String {
    val v = b.toInt() and 0xff
    val hi = v ushr 4
    val lo = v and 0x0f
    fun ch(x: Int) = if (x < 10) ('0' + x) else ('A' + (x - 10))
    return "${ch(hi)}${ch(lo)}"
}

internal fun HttpRequestBuilder.jsonBody(json: Json, value: JsonElement) {
    contentType(ContentType.Application.Json)
    setBody(json.encodeToString(JsonElement.serializer(), value))
}
