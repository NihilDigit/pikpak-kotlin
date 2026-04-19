package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.ErrorCodes
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakConstants
import io.github.nihildigit.pikpak.PikPakException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.delay
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
 *  3. Failure recovery: transient HTTP/network → backoff retry; PikPak
 *     `error_code=9` (captcha required) → refresh captcha for the action and
 *     retry exactly once before bubbling up.
 *
 * Auth flows (signin / refresh / captcha-init) bypass [request] and call
 * [requestRaw] directly so they don't recursively trigger captcha handling.
 */
internal class HttpEngine(
    private val client: HttpClient,
    private val pikpak: PikPakClient,
) {
    suspend fun request(
        method: HttpMethod,
        url: String,
        captchaAction: String? = null,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): JsonElement {
        var captchaRetried = false
        while (true) {
            val element = requestRaw(method, url) {
                applyAuthHeaders()
                configure()
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
     */
    suspend fun requestRaw(
        method: HttpMethod,
        url: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): JsonElement {
        val response = sendWithRetry(method, url) {
            headers {
                append(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
                append("X-Device-Id", pikpak.deviceId)
            }
            configure()
        }
        val text = response.bodyAsText()
        if (text.isBlank()) return JsonObject(emptyMap())
        return pikpak.json.parseToJsonElement(text)
    }

    /**
     * Raw external HTTP. Does NOT add PikPak-specific headers — use this for
     * the signed CDN/OSS URLs returned by PikPak, which reject auth headers
     * and Accept negotiation. Caller is responsible for any required headers
     * (Range, custom UA, etc.).
     */
    suspend fun sendRaw(
        method: HttpMethod,
        url: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = sendWithRetry(method, url, configure)

    private suspend fun sendWithRetry(
        method: HttpMethod,
        url: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        val policy = pikpak.retryPolicy
        var lastError: Throwable? = null
        repeat(policy.maxAttempts) { attempt ->
            pikpak.rateLimiter.acquire()
            try {
                return client.request(url) {
                    this.method = method
                    configure()
                }
            } catch (t: Throwable) {
                lastError = t
                if (!isRetryable(t) || attempt == policy.maxAttempts - 1) throw t
                delay(policy.delayFor(attempt))
            }
        }
        throw lastError ?: IllegalStateException("retry loop exited without error")
    }

    private fun isRetryable(t: Throwable): Boolean {
        val name = t::class.simpleName?.lowercase().orEmpty()
        if ("timeout" in name || "connect" in name || "io" in name) return true
        val message = t.message?.lowercase().orEmpty()
        return "reset" in message || "closed" in message || "broken" in message ||
            "unexpected eof" in message || "timeout" in message
    }

    private fun HttpRequestBuilder.applyAuthHeaders() {
        val session = pikpak.state.session
        if (session != null) {
            headers.append(HttpHeaders.Authorization, "Bearer ${session.accessToken}")
        }
        val captcha = pikpak.state.captchaToken
        if (captcha.isNotEmpty()) {
            headers.append("X-Captcha-Token", captcha)
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
        return PikPakException(code, msg, desc)
    }

    companion object {
        // ContentNegotiation is intentionally NOT installed: it would auto-add an
        // `Accept: application/json` header that the OSS/CDN download endpoints
        // reject with 406. We parse JSON manually via bodyAsText() + Json.parseToJsonElement().
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
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 2)
                exponentialDelay()
            }
            expectSuccess = false
        }
    }
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
        c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
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

