package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.ErrorCodes
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakConstants
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.toHex
import io.ktor.http.HttpMethod
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.kotlincrypto.hash.md.MD5

private const val SESSION_EXPIRY_SKEW_SEC = 5L * 60L

/**
 * Auth state machine. Three flows:
 *  - [loginLocked]: try cached session → refresh if expired → full signin if refresh fails.
 *  - [refreshAccessToken]: POST /v1/auth/token with refresh_token grant.
 *  - [refreshCaptchaToken]: re-issue X-Captcha-Token for a given action when
 *    the server rejects with `error_code=9`. Uses the salt-cascade signing
 *    derived from the official Android client.
 *
 * Mutex policy:
 *  - [loginLocked] assumes the caller already holds [PikPakClient.mutex].
 *  - [refreshCaptchaToken] takes the mutex itself — it's called from inside
 *    `HttpEngine.request`, which never holds the mutex.
 *
 * Invariant: every request issued from inside [AuthApi] MUST go through
 * [HttpEngine.requestRaw] (not `request`). The `request` path may trigger
 * a captcha-refresh callback that re-enters this API, which would deadlock
 * on the already-held mutex. `requestRaw` bypasses that callback.
 */
internal class AuthApi(private val pikpak: PikPakClient) {

    suspend fun loginLocked(): Session {
        pikpak.sessionStore.load(pikpak.account)?.let { cached ->
            pikpak.state.session = cached
            if (!isExpired(cached)) return cached
            try {
                return refreshAccessTokenLocked(cached.refreshToken)
            } catch (_: PikPakException) {
                // fall through to full signin
            }
        }
        return signInLocked()
    }

    private suspend fun signInLocked(): Session {
        val captcha = initCaptcha("POST:https://user.mypikpak.com/v1/auth/signin")
        val body = buildJsonObject {
            put("client_id", PikPakConstants.CLIENT_ID)
            put("client_secret", PikPakConstants.CLIENT_SECRET)
            put("grant_type", "password")
            put("username", pikpak.account)
            put("password", pikpak.state.password)
            put("captcha_token", captcha)
        }
        val response = pikpak.http.requestRaw(
            HttpMethod.Post,
            "${PikPakConstants.USER_BASE}/v1/auth/signin",
        ) { jsonBody(pikpak.json, body) }
        ensureOk(response, "signin")
        val session = response.toSession()
        commitSession(session)
        return session
    }

    private suspend fun refreshAccessTokenLocked(refreshToken: String): Session {
        val body = buildJsonObject {
            put("client_id", PikPakConstants.CLIENT_ID)
            put("client_secret", PikPakConstants.CLIENT_SECRET)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
        }
        val response = pikpak.http.requestRaw(
            HttpMethod.Post,
            "${PikPakConstants.USER_BASE}/v1/auth/token",
        ) { jsonBody(pikpak.json, body) }
        val errorCode = (response as? JsonObject)?.get("error_code")
            ?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        if (errorCode == ErrorCodes.REFRESH_TOKEN_INVALID) {
            return signInLocked()
        }
        ensureOk(response, "refresh_token")
        val session = response.toSession()
        commitSession(session)
        return session
    }

    suspend fun refreshCaptchaToken(action: String) = pikpak.mutex.withLock {
        val sub = pikpak.state.session?.sub.orEmpty()
        val timestamp = Clock.System.now().toEpochMilliseconds().toString()
        val signRaw = PikPakConstants.CLIENT_ID +
            PikPakConstants.CLIENT_VERSION +
            PikPakConstants.PACKAGE_NAME +
            pikpak.deviceId +
            timestamp
        var hashed = signRaw
        for (salt in PikPakConstants.CAPTCHA_SALTS) {
            hashed = MD5().digest((hashed + salt).encodeToByteArray()).toHex()
        }
        val body = buildJsonObject {
            put("action", action)
            put("captcha_token", pikpak.state.captchaToken)
            put("client_id", PikPakConstants.CLIENT_ID)
            put("device_id", pikpak.deviceId)
            putJsonObject("meta") {
                put("captcha_sign", "1.$hashed")
                put("user_id", sub)
                put("package_name", PikPakConstants.PACKAGE_NAME)
                put("client_version", PikPakConstants.CLIENT_VERSION)
                put("timestamp", timestamp)
            }
            // Byte-for-byte copy of the Go reference (52funny/pikpakcli/internal/api/captcha_token.go).
            // PikPak's server does not validate this value — only that the field is present.
            // Do NOT "fix" the missing `h`: matching the reference exactly is the safest posture.
            put("redirect_uri", "ttps://api.mypikpak.com/v1/auth/callback")
        }
        val url = "${PikPakConstants.USER_BASE}/v1/shield/captcha/init?client_id=${PikPakConstants.CLIENT_ID}"
        val response = pikpak.http.requestRaw(HttpMethod.Post, url) {
            jsonBody(pikpak.json, body)
        }
        ensureOk(response, "captcha_refresh")
        val token = (response as JsonObject)["captcha_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        pikpak.state.captchaToken = token
    }

    private suspend fun initCaptcha(action: String): String {
        val body = buildJsonObject {
            put("client_id", PikPakConstants.CLIENT_ID)
            put("device_id", pikpak.deviceId)
            put("action", action)
            putJsonObject("meta") {
                put("username", pikpak.account)
            }
        }
        val response = pikpak.http.requestRaw(
            HttpMethod.Post,
            "${PikPakConstants.USER_BASE}/v1/shield/captcha/init",
        ) { jsonBody(pikpak.json, body) }
        ensureOk(response, "captcha_init")
        val token = (response as JsonObject)["captcha_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        pikpak.state.captchaToken = token
        return token
    }

    private suspend fun commitSession(session: Session) {
        pikpak.state.session = session
        runCatching { pikpak.sessionStore.save(pikpak.account, session) }
    }

    private fun isExpired(session: Session): Boolean {
        val now = Clock.System.now().epochSeconds
        return session.expiresAt <= now
    }

    private fun JsonElement.toSession(): Session {
        val obj = jsonObject
        val accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sub = obj["sub"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 0L
        val expiresAt = Clock.System.now().epochSeconds + expiresIn - SESSION_EXPIRY_SKEW_SEC
        return Session(accessToken, refreshToken, sub, expiresAt)
    }

    private fun ensureOk(response: JsonElement, op: String) {
        val obj = response as? JsonObject ?: throw PikPakException(-1, "$op: bad response shape")
        val code = (obj["error_code"] as? JsonPrimitive)?.intOrNull ?: 0
        if (code != 0) {
            val msg = (obj["error"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val desc = (obj["error_description"] as? JsonPrimitive)?.contentOrNull
            throw PikPakException(code, "$op: $msg", desc)
        }
    }
}
