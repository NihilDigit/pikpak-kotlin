package io.github.nihildigit.pikpak

import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Membership is read off getTransferQuota, which carries the tier and the expiry beside the
// allowances. A separate getVipInfo stood here with nobody calling it and an envelope of its own.
private const val USER_ME_URL = "${PikPakConstants.USER_BASE}/v1/user/me"

/**
 * One OAuth provider linked to the account, as returned in [UserProfile.providers].
 */
@Serializable
data class AuthProvider(
    val id: String = "",
    @SerialName("provider_user_id") val providerUserId: String = "",
    val name: String = "",
)

/**
 * The authenticated account's own profile (`GET /v1/user/me`).
 *
 * [email] and [phoneNumber] arrive masked by the server — the API has no way
 * to return them in full, so neither has one here.
 */
@Serializable
data class UserProfile(
    /** PikPak's user id. The same value as [Session.sub]. */
    val sub: String = "",
    /** Display name. Empty for an account that never set one. */
    val name: String = "",
    /**
     * Avatar URL. PikPak names this field `picture`, not `avatar`; the
     * property keeps the wire name so the mapping stays checkable against a
     * captured response.
     */
    val picture: String = "",
    /** Masked by the server, e.g. `a***@example.com`. */
    val email: String = "",
    /** Masked by the server. Empty when no number is linked. */
    @SerialName("phone_number") val phoneNumber: String = "",
    /** `"SET"` when a password exists, empty for an OAuth-only account. */
    val password: String = "",
    /** Observed value: `"ACTIVE"`. */
    val status: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("password_updated_at") val passwordUpdatedAt: String = "",
    val providers: List<AuthProvider> = emptyList(),
) {
    /** [picture] when the account has one, null when it does not. */
    val avatarUrl: String? get() = picture.takeIf { it.isNotBlank() }

    val hasPassword: Boolean get() = password == "SET"
}

/**
 * Returns the authenticated account's profile — display name, avatar, masked
 * contact details.
 *
 * Lives on `user.mypikpak.com` rather than the drive host, which is why the
 * captcha action is a full URL: actions on the user host are signed with one
 * (see the signin flow), while drive actions use the bare path.
 */
suspend fun PikPakClient.getUserProfile(): UserProfile {
    val response = http.request(
        method = HttpMethod.Get,
        url = USER_ME_URL,
        captchaAction = "GET:$USER_ME_URL",
    )
    return json.decodeFromJsonElement(UserProfile.serializer(), response)
}
