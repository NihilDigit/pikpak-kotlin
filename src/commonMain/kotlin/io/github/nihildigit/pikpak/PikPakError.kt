package io.github.nihildigit.pikpak

/**
 * Every failure the SDK surfaces. Open so transport-specific subtypes
 * ([UrlExpiredException]) can be matched by callers that care, while callers
 * that don't can keep catching this one type.
 *
 * [httpStatus], [headers] and [rawBody] are populated whenever the failure was
 * observed at the HTTP layer. They are the only way a caller can tell a 502
 * from a 403 from a JSON envelope error, so nothing that reaches a consumer
 * should drop them.
 */
open class PikPakException(
    val errorCode: Int,
    val errorMessage: String,
    val errorDescription: String? = null,
    val httpStatus: Int? = null,
    /** Response headers, lower-cased keys. Empty when the failure was not an HTTP response. */
    val headers: Map<String, List<String>> = emptyMap(),
    /** Response body as text, truncated by the layer that built the exception. Null when not read. */
    val rawBody: String? = null,
    cause: Throwable? = null,
) : RuntimeException(buildMessage(errorCode, errorMessage, errorDescription, httpStatus), cause) {

    val isCaptchaRequired: Boolean get() = errorCode == ErrorCodes.CAPTCHA_REQUIRED
    val isRefreshTokenInvalid: Boolean get() = errorCode == ErrorCodes.REFRESH_TOKEN_INVALID

    /** First value of [name] (case-insensitive), or null. */
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    companion object {
        private fun buildMessage(code: Int, message: String, description: String?, http: Int?): String {
            val httpPart = http?.let { " (http=$it)" }.orEmpty()
            val descPart = description?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            return "PikPak error_code=$code$httpPart, error=\"$message\"$descPart"
        }
    }
}

/**
 * A signed CDN/OSS URL was rejected with 401 or 403. The PikPak CDN returns
 * 403 with an empty body once the `expire` parameter in the signature has
 * passed, which is indistinguishable from "file deleted" unless the status is
 * carried through — hence a dedicated type.
 *
 * Recovery is always the same: fetch a fresh link (`getFile`) and reissue the
 * request. `RangeReader` does this automatically via its `urlProvider`.
 */
class UrlExpiredException(
    /** The URL that was rejected, for logging. May be null if the caller withheld it. */
    val url: String?,
    httpStatus: Int,
    headers: Map<String, List<String>> = emptyMap(),
    rawBody: String? = null,
) : PikPakException(
    errorCode = -1,
    errorMessage = "signed URL rejected with HTTP $httpStatus (expired or revoked)",
    errorDescription = url,
    httpStatus = httpStatus,
    headers = headers,
    rawBody = rawBody,
)

object ErrorCodes {
    const val OK = 0
    const val CAPTCHA_REQUIRED = 9
    const val REFRESH_TOKEN_INVALID = 4126
}
