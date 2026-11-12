package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.AuthClientProfile
import io.github.nihildigit.pikpak.DeviceAuthorization
import io.github.nihildigit.pikpak.DeviceAuthorizationException
import io.github.nihildigit.pikpak.DeviceAuthorizationFailureReason
import io.github.nihildigit.pikpak.DevicePollResult
import io.github.nihildigit.pikpak.ErrorCodes
import io.github.nihildigit.pikpak.InteractiveLoginReason
import io.github.nihildigit.pikpak.InteractiveLoginRequiredException
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakConstants
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.isUsable
import io.github.nihildigit.pikpak.toHex
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.http.HttpMethod
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
private const val DEFAULT_DEVICE_EXPIRES_IN_SEC = 300L
private const val RFC8628_DEFAULT_INTERVAL_SEC = 5L
private const val MIN_POLL_INTERVAL_SEC = 2L
private const val MAX_TRANSIENT_POLL_INTERVAL_SEC = 15L
private const val MAX_SINGLE_POLL_TIMEOUT_MS = 15_000L

private class DeadlineFlag(@kotlin.concurrent.Volatile var tripped: Boolean = false)

private val DEFINITIVE_REFRESH_ERRORS = setOf(
    "refresh_token_invalid",
    "invalid_grant",
    "invalid_token",
    "token_expired",
    "expired_token",
    "unauthenticated",
)

internal data class ActiveAuthContext(
    val profile: AuthClientProfile,
    val deviceId: String,
)

/**
 * Auth state machine.
 *  - [loginLocked] and [reauthenticateLocked]: one ladder, see [authenticateLocked].
 *  - [startDeviceAuthorization] / [pollDeviceAuthorization] / [awaitDeviceAuthorization]:
 *    first-party RFC 8628 device authorization flow.
 *  - [refreshCaptchaToken]: re-issue X-Captcha-Token for a given action when
 *    the server rejects with `error_code=9`.
 *
 * Mutex policy:
 *  - [loginLocked] and [reauthenticateLocked] assume the caller already holds
 *    [PikPakClient.mutex].
 *  - [refreshCaptchaToken] and [pollDeviceAuthorization] take the mutex only when
 *    committing state — never across external user waiting intervals.
 *
 * Invariant: every request issued from inside [AuthApi] MUST go through
 * [HttpEngine.requestRaw] (not `request`) so auth bootstrap never recurses into
 * captcha recovery.
 */
internal class AuthApi(private val pikpak: PikPakClient) {

    /** A session that can authorize a request. */
    suspend fun loginLocked(): Session = authenticateLocked(rejected = null)

    /**
     * After the server refused [previous] (HTTP 401) although it had not reached its own
     * expiry. The same ladder, passing over any session that still carries the refused token.
     */
    suspend fun reauthenticateLocked(previous: Session?): Session =
        authenticateLocked(rejected = previous?.accessToken)

    /**
     * The one ladder, cheapest first:
     *  1. the in-memory session, if it can authorize and is not the refused one;
     *  2. the stored one, on the same test — another process sharing the store may have
     *     rotated it since this one loaded;
     *  3. a refresh with each candidate session's refresh token (preserving its own
     *     [AuthClientProfile] and `deviceId`), memory's first;
     *  4. a password sign-in, once — or [InteractiveLoginRequiredException] immediately
     *     without touching `captcha/init` when no password supplier is configured.
     *
     * Transient network errors, 429s, and 5xx server failures on refresh propagate directly
     * without clearing the stored session.
     */
    private suspend fun authenticateLocked(rejected: String?): Session {
        val memory = pikpak.state.session
        if (memory != null && memory.accessToken != rejected && memory.isUsable()) {
            pikpak.activeAuthContext(memory)
            return memory
        }

        val stored = pikpak.sessionStore.load(pikpak.account)
        if (stored != null && stored.accessToken != rejected && stored.isUsable()) {
            pikpak.activeAuthContext(stored)
            pikpak.state.session = stored
            return stored
        }

        var deadRefreshToken = false
        val refreshCandidates = listOfNotNull(memory, stored)
            .filter { it.refreshToken.isNotEmpty() }
            .distinctBy { it.refreshToken }
        for (candidate in refreshCandidates) {
            val refreshed = refreshLocked(candidate)
            if (refreshed != null) return refreshed
            deadRefreshToken = true
        }
        if (deadRefreshToken) forgetSessionLocked()
        return signInLocked(hadDeadRefreshToken = deadRefreshToken)
    }

    private suspend fun forgetSessionLocked() {
        pikpak.state.session = null
        pikpak.sessionStore.clear(pikpak.account)
    }

    private suspend fun signInLocked(hadDeadRefreshToken: Boolean): Session {
        val supplier = pikpak.state.passwordSupplier
        val username = pikpak.loginUsername.trim()
        if (supplier == null || username.isEmpty()) {
            val interactive = InteractiveLoginRequiredException(
                reason = if (hadDeadRefreshToken) InteractiveLoginReason.REFRESH_EXPIRED else InteractiveLoginReason.NO_CREDENTIALS,
                profileId = pikpak.authProfile.id,
            )
            runCatching { pikpak.onInteractiveLoginRequired?.invoke(interactive) }
            throw interactive
        }
        val ctx = pikpak.activeAuthContext(candidateSession = null)
        val secret = ctx.profile.clientSecret
            ?: throw PikPakException(-1, "Password sign-in requires clientSecret on profile ${ctx.profile.id}")
        val captcha = initCaptcha("POST:https://user.mypikpak.com/v1/auth/signin", ctx)
        val body = buildJsonObject {
            put("client_id", ctx.profile.clientId)
            put("client_secret", secret)
            put("grant_type", "password")
            put("username", username)
            put("password", supplier())
            put("captcha_token", captcha)
        }
        val response = pikpak.http.requestRaw(
            method = HttpMethod.Post,
            url = "${PikPakConstants.USER_BASE}/v1/auth/signin",
            authContext = ctx,
        ) { jsonBody(pikpak.json, body) }
        val session = ensureOk(response, "signin").toSession(
            profileId = ctx.profile.id,
            deviceId = ctx.deviceId,
        )
        commitSession(session)
        return session
    }

    /**
     * A new session from [candidate], or `null` ONLY when the server definitively reports
     * the refresh token itself as dead/invalid. Throttling (429), 5xx, transport errors,
     * and profile configuration errors propagate untouched so valid stored tokens survive
     * transient outages.
     */
    private suspend fun refreshLocked(candidate: Session): Session? {
        val ctx = pikpak.activeAuthContext(candidate)
        val body = buildJsonObject {
            put("client_id", ctx.profile.clientId)
            ctx.profile.clientSecret?.let { put("client_secret", it) }
            put("grant_type", "refresh_token")
            put("refresh_token", candidate.refreshToken)
        }
        val response = try {
            pikpak.http.requestRaw(
                method = HttpMethod.Post,
                url = "${PikPakConstants.USER_BASE}/v1/auth/token",
                authContext = ctx,
            ) { jsonBody(pikpak.json, body) }
        } catch (e: PikPakException) {
            if (e.isDefinitiveRefreshRejection) return null
            throw e
        }
        response.envelopeError()?.let {
            if (it.isDefinitiveRefreshRejection) return null
            throw it
        }
        val session = ensureOk(response, "refresh_token").toSession(
            previous = candidate,
            profileId = candidate.profileId ?: ctx.profile.id.takeIf { it != AuthClientProfile.ANDROID_DEFAULT_ID },
            deviceId = candidate.deviceId?.takeIf { it.isNotBlank() } ?: ctx.deviceId,
        )
        commitSession(session)
        return session
    }

    suspend fun startDeviceAuthorization(
        scope: String,
        platformName: String,
        deviceId: String,
    ): DeviceAuthorization {
        val normalizedScope = scope.trim().ifEmpty { io.github.nihildigit.pikpak.DEFAULT_OAUTH_SCOPE }
        val profile = pikpak.authProfile
        val ctx = ActiveAuthContext(profile = profile, deviceId = deviceId)
        val body = buildJsonObject {
            put("client_id", profile.clientId)
            put("scope", normalizedScope)
        }
        val response = pikpak.http.requestRaw(
            method = HttpMethod.Post,
            url = "${PikPakConstants.USER_BASE}/v1/auth/device/code",
            authContext = ctx,
        ) { jsonBody(pikpak.json, body) }
        val obj = ensureOk(response, "device_code")
        val deviceCode = obj["device_code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw DeviceAuthorizationException(
                reason = DeviceAuthorizationFailureReason.PROTOCOL_ERROR,
                errorMessage = "device_code missing in /v1/auth/device/code response",
            )
        val userCode = obj["user_code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw DeviceAuthorizationException(
                reason = DeviceAuthorizationFailureReason.PROTOCOL_ERROR,
                errorMessage = "user_code missing in /v1/auth/device/code response",
            )
        val expiresIn = (obj["expires_in"]?.jsonPrimitive?.longOrNull ?: DEFAULT_DEVICE_EXPIRES_IN_SEC)
            .coerceAtLeast(10L)
        val interval = (obj["interval"]?.jsonPrimitive?.longOrNull ?: RFC8628_DEFAULT_INTERVAL_SEC)
            .coerceAtLeast(MIN_POLL_INTERVAL_SEC)
        val issuedAt = Clock.System.now().epochSeconds
        val (qrUrl, googleUrl) = DeviceAuthorization.buildBrowserUrls(
            clientId = profile.clientId,
            scope = normalizedScope,
            userCode = userCode,
            platformName = platformName.trim().ifEmpty { "Piko" },
        )
        return DeviceAuthorization(
            deviceCode = deviceCode,
            userCode = userCode,
            expiresInSeconds = expiresIn,
            intervalSeconds = interval,
            qrLoginUrl = qrUrl,
            googleAuthUrl = googleUrl,
            deviceId = deviceId,
            profileId = profile.id,
            scope = normalizedScope,
            issuedAtEpochSeconds = issuedAt,
            expiresAtEpochSeconds = issuedAt + expiresIn,
        )
    }

    suspend fun pollDeviceAuthorization(
        authorization: DeviceAuthorization,
        commit: Boolean,
    ): DevicePollResult = pollStep(authorization = authorization, commit = commit, ownedByLoop = false, maxWaitMs = null)

    private suspend fun pollStep(
        authorization: DeviceAuthorization,
        commit: Boolean,
        ownedByLoop: Boolean,
        maxWaitMs: Long?,
    ): DevicePollResult {
        if (!authorization.tryClaimStep(ownedByLoop)) {
            throw DeviceAuthorizationException(
                reason = DeviceAuthorizationFailureReason.ALREADY_POLLING,
                errorMessage = "already_polling",
                errorDescription = "Another coroutine is already polling this DeviceAuthorization transaction",
            )
        }
        try {
            val wallRemainingMs = (authorization.expiresAtEpochSeconds * 1000L) - Clock.System.now().toEpochMilliseconds()
            val effectiveRemainingMs = if (maxWaitMs != null) minOf(wallRemainingMs, maxWaitMs) else wallRemainingMs
            if (effectiveRemainingMs <= 0L) {
                throw expiredDeviceTokenException()
            }
            val profile = pikpak.resolveAuthProfile(authorization.profileId)
            val ctx = ActiveAuthContext(profile = profile, deviceId = authorization.deviceId)
            val body = buildJsonObject {
                put("client_id", profile.clientId)
                put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                put("device_code", authorization.deviceCode)
            }
            val hasTimeoutPlugin = pikpak.apiClient.pluginOrNull(HttpTimeout) != null
            val perPollTimeoutMs = minOf(effectiveRemainingMs, MAX_SINGLE_POLL_TIMEOUT_MS)
            val deadlineExhaustedOnTimeout = perPollTimeoutMs >= effectiveRemainingMs
            val deadlineFlag = DeadlineFlag()
            val response = try {
                kotlinx.coroutines.coroutineScope {
                    val requestDeferred = async {
                        pikpak.http.requestRaw(
                            method = HttpMethod.Post,
                            url = "${PikPakConstants.USER_BASE}/v1/auth/token",
                            authContext = ctx,
                            retryRequests = false,
                        ) {
                            if (hasTimeoutPlugin) {
                                timeout { requestTimeoutMillis = perPollTimeoutMs }
                            }
                            jsonBody(pikpak.json, body)
                        }
                    }
                    val watchdog = launch(Dispatchers.Default) {
                        delay(perPollTimeoutMs)
                        deadlineFlag.tripped = true
                        requestDeferred.cancel(CancellationException("device_code poll timeout"))
                    }
                    try {
                        requestDeferred.await()
                    } catch (c: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (deadlineFlag.tripped) {
                            if (deadlineExhaustedOnTimeout ||
                                Clock.System.now().toEpochMilliseconds() >= authorization.expiresAtEpochSeconds * 1000L
                            ) {
                                throw expiredDeviceTokenException()
                            }
                            throw HttpRequestTimeoutException("${PikPakConstants.USER_BASE}/v1/auth/token", perPollTimeoutMs)
                        }
                        throw c
                    } finally {
                        watchdog.cancel()
                    }
                }
            } catch (timeoutErr: HttpRequestTimeoutException) {
                if (deadlineExhaustedOnTimeout ||
                    Clock.System.now().toEpochMilliseconds() >= authorization.expiresAtEpochSeconds * 1000L
                ) {
                    throw expiredDeviceTokenException()
                }
                throw timeoutErr
            } catch (e: PikPakException) {
                return classifyDevicePollError(e, authorization)
            }
            response.envelopeError()?.let { err ->
                return classifyDevicePollError(err, authorization)
            }
            val obj = ensureOk(response, "device_token")
            val session = obj.toSession(
                profileId = profile.id,
                deviceId = authorization.deviceId,
                defaultScope = authorization.scope,
            )
            if (session.accessToken.isBlank() || session.refreshToken.isBlank()) {
                throw DeviceAuthorizationException(
                    reason = DeviceAuthorizationFailureReason.PROTOCOL_ERROR,
                    errorMessage = "incomplete_token_response",
                    errorDescription = "Device token response omitted access_token or refresh_token",
                )
            }
            if (commit) {
                pikpak.mutex.withLock { commitSession(session) }
            }
            return DevicePollResult.Authorized(session)
        } finally {
            authorization.releaseStep()
        }
    }

    suspend fun awaitDeviceAuthorization(
        authorization: DeviceAuthorization,
        commit: Boolean,
        onPoll: (suspend (DevicePollResult) -> Unit)? = null,
    ): Session {
        if (!authorization.claimLoop()) {
            throw DeviceAuthorizationException(
                reason = DeviceAuthorizationFailureReason.ALREADY_POLLING,
                errorMessage = "already_polling",
                errorDescription = "Another poller has already claimed this DeviceAuthorization transaction",
            )
        }
        val totalBudgetMs = authorization.expiresInSeconds.coerceAtLeast(1L) * 1000L
        var waitedMs = 0L
        var transientExtraSec = 0L
        var retryAfterFloorSec = 0L
        while (true) {
            val baseIntervalSec = authorization.currentIntervalSeconds.coerceAtLeast(MIN_POLL_INTERVAL_SEC)
            val transientCeilingSec = maxOf(baseIntervalSec, MAX_TRANSIENT_POLL_INTERVAL_SEC)
            val sleepSec = maxOf(
                baseIntervalSec,
                retryAfterFloorSec,
                (baseIntervalSec + transientExtraSec).coerceAtMost(transientCeilingSec),
            )
            retryAfterFloorSec = 0L

            val wallRemainingMs = (authorization.expiresAtEpochSeconds * 1000L) - Clock.System.now().toEpochMilliseconds()
            val remainingMs = minOf(wallRemainingMs, totalBudgetMs - waitedMs)
            if (remainingMs <= 0L) {
                throw expiredDeviceTokenException()
            }
            val sleepMs = sleepSec * 1000L
            if (sleepMs >= remainingMs) {
                delay(remainingMs.milliseconds)
                throw expiredDeviceTokenException()
            }
            delay(sleepMs.milliseconds)
            waitedMs += sleepMs
            val postSleepRemainingMs = minOf(
                (authorization.expiresAtEpochSeconds * 1000L) - Clock.System.now().toEpochMilliseconds(),
                totalBudgetMs - waitedMs,
            )
            if (postSleepRemainingMs <= 0L) {
                throw expiredDeviceTokenException()
            }
            val result = try {
                pollStep(
                    authorization = authorization,
                    commit = commit,
                    ownedByLoop = true,
                    maxWaitMs = postSleepRemainingMs,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (terminal: DeviceAuthorizationException) {
                throw terminal
            } catch (apiError: PikPakException) {
                val status = apiError.httpStatus
                if (status != null && (status == 429 || status >= 500)) {
                    retryAfterFloorSec = apiError.retryAfter()?.inWholeSeconds ?: 0L
                    transientExtraSec = (transientExtraSec + 2L).coerceAtMost(MAX_TRANSIENT_POLL_INTERVAL_SEC)
                    continue
                }
                throw DeviceAuthorizationException(
                    reason = DeviceAuthorizationFailureReason.PROTOCOL_ERROR,
                    errorCode = apiError.errorCode,
                    errorMessage = apiError.errorMessage,
                    errorDescription = apiError.errorDescription,
                    httpStatus = apiError.httpStatus,
                    headers = apiError.headers,
                    rawBody = apiError.rawBody,
                    cause = apiError,
                )
            } catch (transport: Throwable) {
                if (pikpak.http.isTransientFailure(transport)) {
                    transientExtraSec = (transientExtraSec + 2L).coerceAtMost(MAX_TRANSIENT_POLL_INTERVAL_SEC)
                    continue
                }
                throw transport
            }
            transientExtraSec = 0L
            when (result) {
                is DevicePollResult.Authorized -> {
                    onPoll?.invoke(result)
                    return result.session
                }
                is DevicePollResult.Pending -> {
                    onPoll?.invoke(result)
                }
                is DevicePollResult.SlowDown -> {
                    onPoll?.invoke(result)
                }
            }
        }
    }

    private fun expiredDeviceTokenException() = DeviceAuthorizationException(
        reason = DeviceAuthorizationFailureReason.EXPIRED_TOKEN,
        errorMessage = "expired_token",
        errorDescription = "Device authorization timed out waiting for user confirmation",
    )

    private fun classifyDevicePollError(error: PikPakException, authorization: DeviceAuthorization): DevicePollResult {
        val code = error.errorCode
        val msg = error.errorMessage.lowercase()
        return when {
            code == ErrorCodes.DEVICE_AUTHORIZATION_PENDING || msg == "authorization_pending" ->
                DevicePollResult.Pending
            msg == "slow_down" ->
                DevicePollResult.SlowDown(authorization.recordSlowDown())
            msg == "expired_token" ->
                throw DeviceAuthorizationException(
                    reason = DeviceAuthorizationFailureReason.EXPIRED_TOKEN,
                    errorCode = code,
                    errorMessage = error.errorMessage,
                    errorDescription = error.errorDescription,
                    httpStatus = error.httpStatus,
                    headers = error.headers,
                    rawBody = error.rawBody,
                    cause = error,
                )
            msg == "access_denied" ->
                throw DeviceAuthorizationException(
                    reason = DeviceAuthorizationFailureReason.ACCESS_DENIED,
                    errorCode = code,
                    errorMessage = error.errorMessage,
                    errorDescription = error.errorDescription,
                    httpStatus = error.httpStatus,
                    headers = error.headers,
                    rawBody = error.rawBody,
                    cause = error,
                )
            code == ErrorCodes.REFRESH_TOKEN_INVALID || msg == "invalid_grant" || msg == "bad_verification_code" ->
                throw DeviceAuthorizationException(
                    reason = DeviceAuthorizationFailureReason.INVALID_GRANT,
                    errorCode = code,
                    errorMessage = error.errorMessage,
                    errorDescription = error.errorDescription,
                    httpStatus = error.httpStatus,
                    headers = error.headers,
                    rawBody = error.rawBody,
                    cause = error,
                )
            else -> throw error
        }
    }

    /**
     * Replaces [rejected], the token the failed request was sent with. N
     * coroutines that all hit error_code=9 on the same token must produce one
     * captcha/init: whoever takes the lock first refreshes, and the rest find
     * the token already changed and reuse it.
     */
    suspend fun refreshCaptchaToken(action: String, rejected: String) {
        pikpak.mutex.withLock {
            if (pikpak.state.captchaToken != rejected) return
            refreshCaptchaTokenLocked(action)
        }
    }

    private suspend fun refreshCaptchaTokenLocked(action: String) {
        val ctx = pikpak.activeAuthContext()
        val sub = pikpak.state.session?.sub.orEmpty()
        val timestamp = Clock.System.now().toEpochMilliseconds().toString()
        val signRaw = ctx.profile.clientId +
            ctx.profile.clientVersion +
            ctx.profile.packageName +
            ctx.deviceId +
            timestamp
        var hashed = signRaw
        for (salt in ctx.profile.captchaSalts) {
            hashed = MD5().digest((hashed + salt).encodeToByteArray()).toHex()
        }
        val body = buildJsonObject {
            put("action", action)
            put("captcha_token", pikpak.state.captchaToken)
            put("client_id", ctx.profile.clientId)
            put("device_id", ctx.deviceId)
            putJsonObject("meta") {
                put("captcha_sign", "1.$hashed")
                put("user_id", sub)
                put("package_name", ctx.profile.packageName)
                put("client_version", ctx.profile.clientVersion)
                put("timestamp", timestamp)
            }
            // Byte-for-byte copy of the Go reference (52funny/pikpakcli/internal/api/captcha_token.go).
            // PikPak's server does not validate this value — only that the field is present.
            // Do NOT "fix" the missing `h`: matching the reference exactly is the safest posture.
            put("redirect_uri", "ttps://api.mypikpak.com/v1/auth/callback")
        }
        val url = "${PikPakConstants.USER_BASE}/v1/shield/captcha/init?client_id=${rfc3986Encode(ctx.profile.clientId)}"
        val response = pikpak.http.requestRaw(HttpMethod.Post, url, authContext = ctx) {
            jsonBody(pikpak.json, body)
        }
        val token = ensureOk(response, "captcha_refresh")["captcha_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        pikpak.state.captchaToken = token
    }

    private suspend fun initCaptcha(action: String, ctx: ActiveAuthContext): String {
        val body = buildJsonObject {
            put("client_id", ctx.profile.clientId)
            put("device_id", ctx.deviceId)
            put("action", action)
            putJsonObject("meta") {
                put("username", pikpak.loginUsername)
            }
        }
        val response = pikpak.http.requestRaw(
            method = HttpMethod.Post,
            url = "${PikPakConstants.USER_BASE}/v1/shield/captcha/init",
            authContext = ctx,
        ) { jsonBody(pikpak.json, body) }
        val token = ensureOk(response, "captcha_init")["captcha_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        pikpak.state.captchaToken = token
        return token
    }

    private suspend fun commitSession(session: Session) {
        pikpak.state.session = session
        pikpak.sessionStore.save(pikpak.account, session)
    }

    private fun JsonObject.toSession(
        previous: Session? = null,
        profileId: String? = null,
        deviceId: String? = null,
        defaultScope: String? = null,
    ): Session {
        val accessToken = this["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val refreshToken = this["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: previous?.refreshToken.orEmpty()
        val sub = this["sub"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: extractUnverifiedJwtSub(accessToken)
            ?: previous?.sub.orEmpty()
        val scope = this["scope"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: defaultScope
            ?: previous?.scope
        val expiresIn = this["expires_in"]?.jsonPrimitive?.longOrNull ?: 0L
        val expiresAt = Clock.System.now().epochSeconds + expiresIn - SESSION_EXPIRY_SKEW_SEC
        return Session(
            accessToken = accessToken,
            refreshToken = refreshToken,
            sub = sub,
            expiresAt = expiresAt,
            profileId = profileId ?: previous?.profileId,
            deviceId = deviceId?.takeIf { it.isNotBlank() } ?: previous?.deviceId,
            scope = scope,
        )
    }

    /**
     * Extracts the `sub` claim from an access token JWT payload only as an unverified fallback
     * when the token endpoint omits top-level `sub` (as `/v1/auth/token` does on `device_code`
     * grants). Interactive callers MUST still verify identity via `getUserProfile()` (`/v1/user/me`).
     */
    private fun extractUnverifiedJwtSub(jwt: String): String? {
        val payloadPart = jwt.split('.').getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val decoded = runCatching {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payloadPart).decodeToString()
        }.getOrNull() ?: return null
        val obj = runCatching { pikpak.json.parseToJsonElement(decoded) as? JsonObject }.getOrNull() ?: return null
        return obj["sub"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private val PikPakException.isDefinitiveRefreshRejection: Boolean
        get() {
            if (isCaptchaRequired) return false
            if (isRefreshTokenInvalid) return true
            if (errorMessage.lowercase() in DEFINITIVE_REFRESH_ERRORS) return true
            return httpStatus == 401
        }

    private fun ensureOk(response: JsonElement, op: String): JsonObject {
        response.envelopeError()?.let { throw it }
        return response as? JsonObject ?: throw PikPakException(-1, "$op: bad response shape", rawBody = response.toString())
    }
}
