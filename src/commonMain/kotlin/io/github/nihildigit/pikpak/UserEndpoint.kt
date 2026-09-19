package io.github.nihildigit.pikpak

import io.ktor.http.HttpMethod
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val USER_ME_URL = "${PikPakConstants.USER_BASE}/v1/user/me"
private const val VIP_URL = "${PikPakConstants.DRIVE_BASE}/drive/v1/privilege/vip"

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

/** Payload of [VipStatus]. Absent on accounts the endpoint has nothing to say about. */
@Serializable
data class VipData(
    /** RFC 3339. Prefer [VipStatus.expiresAt]. */
    val expire: String = "",
    /** Observed values: `"ok"`, `"invalid"`. */
    val status: String = "",
    /** Membership tier, e.g. `"platinum"`. */
    val type: String = "",
    @SerialName("user_id") val userId: String = "",
)

/**
 * VIP membership (`GET /drive/v1/privilege/vip`).
 *
 * The envelope here is not PikPak's usual one: success is [result] ==
 * `"ACCEPTED"` rather than `error_code` 0, so a failed lookup arrives as a
 * 2xx this SDK cannot turn into an exception. Check [isVip] rather than
 * assuming [data] is populated.
 */
@Serializable
data class VipStatus(
    /** `"ACCEPTED"` on success. */
    val result: String = "",
    val message: String = "",
    val data: VipData? = null,
) {
    /** True only when the lookup succeeded *and* reported a live membership. */
    val isVip: Boolean get() = result == "ACCEPTED" && data?.status == "ok"

    /** When the membership lapses, or null when there is none or the date is unparseable. */
    val expiresAt: Instant?
        get() = data?.expire?.takeIf { it.isNotBlank() }?.let {
            try {
                Instant.parse(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
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

/**
 * Returns the account's VIP membership. Quota is a separate call — see
 * [getQuota]; a lapsed membership does not change the reported limit
 * immediately.
 */
suspend fun PikPakClient.getVipInfo(): VipStatus {
    val response = http.request(
        method = HttpMethod.Get,
        url = VIP_URL,
        captchaAction = "GET:/drive/v1/privilege/vip",
    )
    return json.decodeFromJsonElement(VipStatus.serializer(), response)
}
