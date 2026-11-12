package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.rfc3986Encode
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * First-party client identity and signing parameters for PikPak authentication.
 *
 * A [Session] records the [id] of the profile that minted it so subsequent
 * `refresh_token` grants and `captcha/init` handshakes use matching client
 * credentials, `User-Agent`, package metadata, and salt cascade rather than
 * mixing fields across client families.
 */
data class AuthClientProfile(
    val id: String,
    val clientId: String,
    val clientSecret: String?,
    val userAgent: String,
    val packageName: String,
    val clientVersion: String,
    val captchaSalts: List<String>,
) {
    /** Never include [clientSecret] in diagnostic strings. */
    override fun toString(): String = "AuthClientProfile(id=$id, clientId=$clientId)"

    companion object {
        const val ANDROID_DEFAULT_ID = "android-default"

        /**
         * First-party Android client profile (`YNxT9w7GMdWvEOKa`), supporting both
         * password sign-in and RFC 8628 device authorization with full `/drive/v1`
         * access and non-Connected-Apps transfer quota.
         */
        val AndroidDefault = AuthClientProfile(
            id = ANDROID_DEFAULT_ID,
            clientId = PikPakConstants.CLIENT_ID,
            clientSecret = PikPakConstants.CLIENT_SECRET,
            userAgent = PikPakConstants.USER_AGENT,
            packageName = PikPakConstants.PACKAGE_NAME,
            clientVersion = PikPakConstants.CLIENT_VERSION,
            captchaSalts = PikPakConstants.CAPTCHA_SALTS,
        )

        /**
         * Resolves [id] to a known [AuthClientProfile]. Legacy sessions persisted
         * without a `profile_id` (`null` or blank) map to [AndroidDefault]; unknown
         * non-blank identifiers return `null` so callers fail closed instead of
         * refreshing under the wrong client identity.
         */
        fun resolveOrNull(
            id: String?,
            primary: AuthClientProfile = AndroidDefault,
            additionalProfiles: Iterable<AuthClientProfile> = emptyList(),
        ): AuthClientProfile? {
            if (id.isNullOrBlank()) return AndroidDefault
            if (id == primary.id) return primary
            if (id == ANDROID_DEFAULT_ID) return AndroidDefault
            return additionalProfiles.firstOrNull { it.id == id }
        }
    }
}

/** Default scope set for first-party PikPak drive & offline-task sessions. */
const val DEFAULT_OAUTH_SCOPE: String = "user pan offline"

/**
 * Pending RFC 8628 device authorization transaction returned by
 * [PikPakClient.startDeviceAuthorization].
 *
 * Open [googleAuthUrl] in the user's system browser (or Custom Tabs on Android)
 * to jump straight into Google account selection and land on [qrLoginUrl] for
 * one-click confirmation, or open [qrLoginUrl] directly when the user's browser
 * is already signed into `mypikpak.com`. Never open these URLs inside an embedded
 * WebView/CEF, which Google rejects with `disallowed_useragent`.
 */
data class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
    val qrLoginUrl: String,
    val googleAuthUrl: String,
    val deviceId: String,
    val profileId: String,
    val scope: String = DEFAULT_OAUTH_SCOPE,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
) {
    private val loopClaimed = MutableStateFlow(false)
    private val stepClaimed = MutableStateFlow(false)
    private val _currentIntervalSeconds = MutableStateFlow(intervalSeconds.coerceAtLeast(2L))

    /** Current RFC 8628 minimum polling interval in seconds, including any cumulative `slow_down` increments. */
    val currentIntervalSeconds: Long get() = _currentIntervalSeconds.value

    internal fun claimLoop(): Boolean = loopClaimed.compareAndSet(expect = false, update = true)

    internal fun tryClaimStep(ownedByLoop: Boolean): Boolean {
        if (!ownedByLoop && loopClaimed.value) return false
        return stepClaimed.compareAndSet(expect = false, update = true)
    }

    internal fun releaseStep() {
        stepClaimed.value = false
    }

    internal fun recordSlowDown(): Long {
        while (true) {
            val prev = _currentIntervalSeconds.value
            val next = prev + 5L
            if (_currentIntervalSeconds.compareAndSet(prev, next)) return next
        }
    }

    /** Redacts [deviceCode] so logs never leak a live bearer grant secret. */
    override fun toString(): String =
        "DeviceAuthorization(userCode=$userCode, profileId=$profileId, expiresAt=$expiresAtEpochSeconds)"

    companion object {
        private const val OFFICIAL_WEB_ORIGIN = "https://mypikpak.com"

        /**
         * Builds the official web confirmation URL and Google jump URL.
         * Always anchored on `https://mypikpak.com` because Google's OAuth redirect URI
         * allowlist for PikPak's web client strictly requires `https://mypikpak.com/auth`.
         */
        fun buildBrowserUrls(
            clientId: String,
            scope: String,
            userCode: String,
            platformName: String,
        ): Pair<String, String> {
            val qrUrl = "$OFFICIAL_WEB_ORIGIN/drive/qr-login" +
                "?client_id=${rfc3986Encode(clientId)}" +
                "&scope=${rfc3986Encode(scope)}" +
                "&user_code=${rfc3986Encode(userCode)}" +
                "&platform=${rfc3986Encode(platformName)}"
            val doubleEncodedOrigin = rfc3986Encode(rfc3986Encode(qrUrl))
            val googleUrl = "$OFFICIAL_WEB_ORIGIN/auth?type=google&init=1&origin=$doubleEncodedOrigin"
            return qrUrl to googleUrl
        }
    }
}

/** Single-step outcome of [PikPakClient.pollDeviceAuthorization]. */
sealed interface DevicePollResult {
    /** User has not clicked Confirm in the browser yet (`authorization_pending` / `4050`). */
    data object Pending : DevicePollResult

    /** Server requested slower polling (`slow_down`); RFC 8628 §3.5 adds 5 seconds permanently. */
    data class SlowDown(val recommendedIntervalSeconds: Long) : DevicePollResult

    /** User confirmed sign-in and the server issued a first-party [Session]. */
    data class Authorized(val session: Session) : DevicePollResult
}

enum class DeviceAuthorizationFailureReason {
    EXPIRED_TOKEN,
    ACCESS_DENIED,
    INVALID_GRANT,
    ALREADY_POLLING,
    PROTOCOL_ERROR,
}

/** Terminal failure during RFC 8628 device authorization. */
class DeviceAuthorizationException(
    val reason: DeviceAuthorizationFailureReason,
    errorCode: Int = -1,
    errorMessage: String,
    errorDescription: String? = null,
    httpStatus: Int? = null,
    headers: Map<String, List<String>> = emptyMap(),
    rawBody: String? = null,
    cause: Throwable? = null,
) : PikPakException(
    errorCode = errorCode,
    errorMessage = errorMessage,
    errorDescription = errorDescription,
    httpStatus = httpStatus,
    headers = headers,
    rawBody = rawBody,
    cause = cause,
)

enum class InteractiveLoginReason {
    /** Client has no cached session and no password supplier configured. */
    NO_CREDENTIALS,
    /** Cached session's refresh token was definitively rejected and no password supplier is configured. */
    REFRESH_EXPIRED,
}

/**
 * Thrown when a passwordless / externally-authorized [PikPakClient] has no usable
 * session or refresh token left and requires interactive browser/user sign-in.
 */
class InteractiveLoginRequiredException(
    val reason: InteractiveLoginReason,
    val profileId: String,
    cause: Throwable? = null,
) : PikPakException(
    errorCode = ErrorCodes.REFRESH_TOKEN_INVALID,
    errorMessage = "interactive_login_required",
    errorDescription = "Interactive sign-in required ($reason, profile=$profileId)",
    cause = cause,
)

/** Generates a fresh 32-character lowercase hex device identifier (128 random bits). */
fun generateDeviceId(): String = Random.Default.nextBytes(16).toHex()
