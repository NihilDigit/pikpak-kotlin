package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakConstants
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.isUsable
import io.github.nihildigit.pikpak.toHex
import io.ktor.http.HttpMethod
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.kotlincrypto.hash.md.MD5

private const val SESSION_EXPIRY_SKEW_SEC = 5L * 60L

/**
 * Auth state machine.
 *  - [loginLocked] and [reauthenticateLocked]: one ladder, see [authenticateLocked].
 *  - [refreshCaptchaToken]: re-issue X-Captcha-Token for a given action when
 *    the server rejects with `error_code=9`. Uses the salt-cascade signing
 *    derived from the official Android client.
 *
 * Mutex policy:
 *  - [loginLocked] and [reauthenticateLocked] assume the caller already holds
 *    [PikPakClient.mutex].
 *  - [refreshCaptchaToken] takes the mutex itself — it's called from inside
 *    `HttpEngine.request`, which never holds the mutex.
 *
 * Invariant: every request issued from inside [AuthApi] MUST go through
 * [HttpEngine.requestRaw] (not `request`). The `request` path may trigger
 * a captcha-refresh callback that re-enters this API, which would deadlock
 * on the already-held mutex. `requestRaw` bypasses that callback.
 */
internal class AuthApi(private val pikpak: PikPakClient) {

    /** A session that can authorize a request. */
    suspend fun loginLocked(): Session = authenticateLocked(rejected = null)

    /**
     * After the server refused [previous] (HTTP 401) although it had not reached its own
     * expiry. The same ladder, passing over any session that still carries the refused token.
     * If another coroutine already replaced it while this one waited for the lock, that
     * replacement is returned and no round trip happens.
     */
    suspend fun reauthenticateLocked(previous: Session?): Session =
        authenticateLocked(rejected = previous?.accessToken)

    /**
     * The one ladder, cheapest first:
     *  1. the in-memory session, if it can authorize and is not the refused one;
     *  2. the stored one, on the same test — another process sharing the store may have
     *     rotated it since this one loaded;
     *  3. a refresh with each refresh token on hand, memory's first;
     *  4. a password sign-in, once.
     *
     * Two partial ladders stood here before, and each skipped a rung the other had. login()
     * ignored an expired in-memory session's refresh token, and the 401 path never read the
     * store, so a request made before login() went from a missing header straight to a
     * password sign-in while a valid session sat on disk.
     *
     * A refresh the server refuses for any reason moves on to the next rung; one that fails
     * on the wire is thrown, because signing in over a network that just dropped a request
     * only fails the same way while costing a password prompt.
     */
    private suspend fun authenticateLocked(rejected: String?): Session {
        val memory = pikpak.state.session
        if (memory != null && memory.accessToken != rejected && memory.isUsable()) return memory

        val stored = pikpak.sessionStore.load(pikpak.account)
        if (stored != null && stored.accessToken != rejected && stored.isUsable()) {
            pikpak.state.session = stored
            return stored
        }

        var deadRefreshToken = false
        val refreshTokens = listOfNotNull(memory?.refreshToken, stored?.refreshToken).filter { it.isNotEmpty() }.distinct()
        for (token in refreshTokens) {
            val refreshed = try {
                refreshLocked(token)
            } catch (_: PikPakException) {
                continue
            }
            if (refreshed != null) return refreshed
            deadRefreshToken = true
        }
        // The dead session goes before the sign-in, not after it succeeds: a sign-in that then
        // fails (no password to give, a wrong one) would otherwise leave the caller a store
        // that retries the same dead token on every start. Callers used to clear it themselves
        // on isRefreshTokenInvalid, which never reached them because the sign-in failure is
        // what they saw.
        if (deadRefreshToken) forgetSessionLocked()
        return signInLocked()
    }

    private suspend fun forgetSessionLocked() {
        pikpak.state.session = null
        pikpak.sessionStore.clear(pikpak.account)
    }

    private suspend fun signInLocked(): Session {
        val captcha = initCaptcha("POST:https://user.mypikpak.com/v1/auth/signin")
        val body = buildJsonObject {
            put("client_id", PikPakConstants.CLIENT_ID)
            put("client_secret", PikPakConstants.CLIENT_SECRET)
            put("grant_type", "password")
            put("username", pikpak.account)
            put("password", pikpak.state.passwordSupplier())
            put("captcha_token", captcha)
        }
        val response = pikpak.http.requestRaw(
            HttpMethod.Post,
            "${PikPakConstants.USER_BASE}/v1/auth/signin",
        ) { jsonBody(pikpak.json, body) }
        val session = ensureOk(response, "signin").toSession()
        commitSession(session)
        return session
    }

    /**
     * A new session from [refreshToken], or null when the server says that token is dead.
     * It reports a dead token either as a 2xx envelope or as a 4xx carrying the same
     * envelope, and requestRaw throws for the latter, so both shapes are checked.
     */
    private suspend fun refreshLocked(refreshToken: String): Session? {
        val body = buildJsonObject {
            put("client_id", PikPakConstants.CLIENT_ID)
            put("client_secret", PikPakConstants.CLIENT_SECRET)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
        }
        val response = try {
            pikpak.http.requestRaw(
                HttpMethod.Post,
                "${PikPakConstants.USER_BASE}/v1/auth/token",
            ) { jsonBody(pikpak.json, body) }
        } catch (e: PikPakException) {
            if (e.isRefreshTokenInvalid) return null
            throw e
        }
        response.envelopeError()?.let { if (it.isRefreshTokenInvalid) return null }
        val session = ensureOk(response, "refresh_token").toSession()
        commitSession(session)
        return session
    }

    /**
     * Replaces [rejected], the token the failed request was sent with. N
     * coroutines that all hit error_code=9 on the same token must produce one
     * captcha/init: whoever takes the lock first refreshes, and the rest find
     * the token already changed and reuse it. The caller supplies [rejected]
     * because a snapshot taken here would be taken too late — a coroutine
     * that reaches this point after the first refresh has completed would
     * snapshot the fresh token and refresh it again.
     *
     * One token for every action, although captcha/init is asked per action.
     * A token minted for one action has so far been accepted on all of them;
     * whether the server would ever bind it is not measured.
     */
    suspend fun refreshCaptchaToken(action: String, rejected: String) {
        pikpak.mutex.withLock {
            if (pikpak.state.captchaToken != rejected) return
            refreshCaptchaTokenLocked(action)
        }
    }

    private suspend fun refreshCaptchaTokenLocked(action: String) {
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
        val token = ensureOk(response, "captcha_refresh")["captcha_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
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
        val token = ensureOk(response, "captcha_init")["captcha_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        pikpak.state.captchaToken = token
        return token
    }

    private suspend fun commitSession(session: Session) {
        pikpak.state.session = session
        // A store failure used to be swallowed. The refresh token then existed
        // only in this process, and the next cold start silently fell back to a
        // plaintext password sign-in — with no signal that persistence had
        // stopped working. The in-memory session stays valid either way, so the
        // caller can catch this and carry on if it genuinely does not care.
        pikpak.sessionStore.save(pikpak.account, session)
    }

    private fun JsonObject.toSession(): Session {
        val accessToken = this["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val refreshToken = this["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sub = this["sub"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expiresIn = this["expires_in"]?.jsonPrimitive?.longOrNull ?: 0L
        val expiresAt = Clock.System.now().epochSeconds + expiresIn - SESSION_EXPIRY_SKEW_SEC
        return Session(accessToken, refreshToken, sub, expiresAt)
    }

    /**
     * The response as an object, or the error its envelope carries. The envelope goes through
     * the same conversion as every API call, so predicates such as isCaptchaRequired read the
     * server's own `error`: this used to prefix it with the operation name, which made a
     * captcha rejection on a 2xx look like something else than the same rejection on a 400.
     */
    private fun ensureOk(response: JsonElement, op: String): JsonObject {
        response.envelopeError()?.let { throw it }
        return response as? JsonObject ?: throw PikPakException(-1, "$op: bad response shape", rawBody = response.toString())
    }
}
