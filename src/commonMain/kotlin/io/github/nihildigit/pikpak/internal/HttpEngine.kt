package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.ErrorCodes
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakConstants
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.UrlExpiredException
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
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

/**
 * Internal HTTP layer. Handles the three things every PikPak request needs:
 *  1. Rate limiting (token bucket — surface area for tuning lives on the client).
 *  2. Standard headers (User-Agent, Authorization, X-Device-Id, X-Captcha-Token).
 *  3. Failure recovery: transient HTTP/network and 5xx/429 → backoff retry;
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
            val sessionUsed = pikpak.state.session
            val element = try {
                requestRaw(method, url) {
                    applyAuthHeaders()
                    configure()
                }
            } catch (e: PikPakException) {
                // The access token has not reached its own expiry or we would
                // not have sent it, so the server disagreeing is the only
                // signal available. Re-auth once; a second 401 is a real
                // authorization failure and belongs to the caller.
                if (e.httpStatus == 401 && !reauthRetried) {
                    reauthRetried = true
                    pikpak.mutex.withLock { pikpak.auth.reauthenticateLocked(sessionUsed) }
                    continue
                }
                throw e
            }
            val errorCode = element.tryGetErrorCode()
            if (errorCode == ErrorCodes.OK) return element
            if (errorCode == ErrorCodes.CAPTCHA_REQUIRED && captchaAction != null && !captchaRetried) {
                pikpak.auth.refreshCaptchaToken(captchaAction)
                captchaRetried = true
                continue
            }
            throw element.toException()
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
            throw PikPakException(
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
     * because a block may have already written bytes somewhere.
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
                    if ((status >= 500 || status == 429) && attempt < policy.maxAttempts - 1) {
                        throw RetryableStatus(status, response.retryAfter(), response.headerMap())
                    }
                    blockEntered = true
                    block(response)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (blockEntered) throw t
                if (t is RetryableStatus) {
                    if (attempt >= policy.maxAttempts - 1) throw t.toPikPakException(url)
                    delay(t.retryAfter ?: policy.delayFor(attempt))
                    attempt++
                    continue
                }
                if (!isRetryable(t) || attempt >= policy.maxAttempts - 1) throw t
                delay(policy.delayFor(attempt))
                attempt++
            }
        }
    }

    private class RetryableStatus(
        val status: Int,
        val retryAfter: Duration?,
        val headers: Map<String, List<String>>,
    ) : RuntimeException("HTTP $status") {
        fun toPikPakException(url: String) = PikPakException(
            errorCode = -1,
            errorMessage = "HTTP $status after retry budget exhausted",
            errorDescription = url,
            httpStatus = status,
            headers = headers,
        )
    }

    private fun isRetryable(t: Throwable): Boolean {
        // Cancellation is not a transport failure; retrying it would resurrect
        // work the caller already abandoned.
        if (t is CancellationException) return false
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

    private fun HttpRequestBuilder.applyAuthHeaders() {
        val session = pikpak.state.session
        if (session != null) {
            headers.set(HttpHeaders.Authorization, "Bearer ${session.accessToken}")
        }
        val captcha = pikpak.state.captchaToken
        if (captcha.isNotEmpty()) {
            headers.set("X-Captcha-Token", captcha)
        }
    }

    private fun JsonElement.tryGetErrorCode(): Int {
        val obj = this as? JsonObject ?: return ErrorCodes.OK
        val code = obj["error_code"] as? JsonPrimitive ?: return ErrorCodes.OK
        return code.intOrNull ?: ErrorCodes.OK
    }

    private fun JsonElement.toException(): PikPakException {
        val obj = this as? JsonObject
            ?: return PikPakException(-1, "unexpected response", this.toString())
        val code = (obj["error_code"] as? JsonPrimitive)?.intOrNull ?: -1
        val msg = (obj["error"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val desc = (obj["error_description"] as? JsonPrimitive)?.contentOrNull
        return PikPakException(code, msg, desc, rawBody = obj.toString().truncateForError())
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
                // Request timeout caps the entire HTTP exchange — set high so
                // long file downloads aren't aborted mid-stream. The per-call
                // RetryPolicy handles short-lived transport failures.
                requestTimeoutMillis = Long.MAX_VALUE
                // Inter-byte socket timeout. Bytes should flow at least every
                // few minutes even on slow links; tune via a custom HttpClient
                // if your workload needs different bounds.
                socketTimeoutMillis = 5L * 60 * 1000
            }
            // HttpRequestRetry is intentionally NOT installed. The SDK's own
            // retry loop (driven by RetryPolicy) already handles transient
            // failures; stacking both plugins compounds backoff and multiplies
            // attempt counts in surprising ways.
            expectSuccess = false
        }
    }
}

private const val ERROR_BODY_LIMIT = 2048

private fun String.truncateForError(): String =
    if (length <= ERROR_BODY_LIMIT) this else substring(0, ERROR_BODY_LIMIT) + "…(${length} bytes total)"

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

internal fun buildUrl(base: String, path: String, query: Map<String, String> = emptyMap()): String {
    val full = base.trimEnd('/') + "/" + path.trimStart('/')
    if (query.isEmpty()) return full
    val qs = query.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
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
