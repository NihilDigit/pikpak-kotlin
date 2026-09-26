package io.github.nihildigit.pikpak

/**
 * A failure PikPak itself reported: an error envelope, a status the SDK does
 * not recover from, or a response it could not make sense of. Open so
 * transport-specific subtypes ([UrlExpiredException]) can be matched by
 * callers that care.
 *
 * Not every failure is one of these. A request that never got an answer —
 * a timeout, a refused connection, a dropped Wi-Fi — surfaces as the
 * engine's own IOException-family exception once retries are spent, and
 * whatever a password supplier throws passes through unchanged. Callers rely
 * on the difference: "offline" and "refused" call for opposite handling, and
 * wrapping one in the other would hide it. Cancellation passes through as
 * well.
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

    /**
     * Code 9 is not captcha-only: a trashed file's detail answers
     * `error_code=9, error="file_in_recycle_bin"` (observed 2026-09-23), and
     * treating that as a captcha costs a captcha handshake and a retry before
     * the real error surfaces. The captcha cases name themselves
     * (`captcha_required`, `captcha_invalid`).
     */
    val isCaptchaRequired: Boolean
        get() = errorCode == ErrorCodes.CAPTCHA_REQUIRED && errorMessage.startsWith("captcha")

    /**
     * The refresh token is dead. [PikPakClient.login] never throws this: it
     * clears the dead session from memory and the store and signs in with the
     * password, and what reaches the caller is that sign-in's own outcome.
     */
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

/**
 * [instantCreate] found that PikPak does not hold the content behind [gcid]:
 * the server answered with a [phase] other than complete, waiting for bytes a
 * hash-only caller does not have. The placeholder the request created has
 * already been deleted, so nothing is left behind in the drive.
 *
 * The content has to reach PikPak another way — an offline task for the source
 * or a real upload. Matched by type so a caller can fall back to one of those
 * without parsing the message.
 */
class InstantContentUnavailableException(
    val gcid: String,
    val fileName: String,
    val phase: String,
) : PikPakException(
    errorCode = -1,
    errorMessage = "instantCreate: PikPak does not hold hash $gcid (phase=$phase); " +
        "$fileName needs a real upload or an offline task",
)

object ErrorCodes {
    const val OK = 0
    const val CAPTCHA_REQUIRED = 9
    const val REFRESH_TOKEN_INVALID = 4126
}
