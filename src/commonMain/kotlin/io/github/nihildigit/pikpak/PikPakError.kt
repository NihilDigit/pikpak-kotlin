package io.github.nihildigit.pikpak

class PikPakException(
    val errorCode: Int,
    val errorMessage: String,
    val errorDescription: String? = null,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(buildMessage(errorCode, errorMessage, errorDescription, httpStatus), cause) {

    val isCaptchaRequired: Boolean get() = errorCode == ErrorCodes.CAPTCHA_REQUIRED
    val isRefreshTokenInvalid: Boolean get() = errorCode == ErrorCodes.REFRESH_TOKEN_INVALID

    companion object {
        private fun buildMessage(code: Int, message: String, description: String?, http: Int?): String {
            val httpPart = http?.let { " (http=$it)" }.orEmpty()
            val descPart = description?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            return "PikPak error_code=$code$httpPart, error=\"$message\"$descPart"
        }
    }
}

object ErrorCodes {
    const val OK = 0
    const val CAPTCHA_REQUIRED = 9
    const val REFRESH_TOKEN_INVALID = 4126
}
