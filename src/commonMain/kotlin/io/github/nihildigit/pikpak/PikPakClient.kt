package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.AuthApi
import io.github.nihildigit.pikpak.internal.HttpEngine
import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.kotlincrypto.hash.md.MD5

/**
 * Entry point to the PikPak SDK.
 *
 * One [PikPakClient] represents one logical authenticated session against the
 * PikPak API. Construction is cheap (no I/O), so consumers wanting multi-account
 * rotation should just hold a list of clients and pick one per request — the
 * SDK deliberately does not bake in an account pool.
 *
 * Credentials: the primary constructor takes a *supplier*, not a password.
 * It is invoked only on a full sign-in — never on a cached-session or
 * refresh-token path — so an app that keeps its password in a keychain or asks
 * the user for it never has to hold plaintext for the client's lifetime. The
 * `password: String` constructor is the convenience form and does retain it.
 *
 * Concurrency: every public method is a `suspend` function and is safe to call
 * from many coroutines concurrently. Internal mutation (session refresh,
 * captcha refresh) is serialized through a mutex.
 *
 * Injected [HttpClient]: the SDK parses JSON by hand and runs its own retry
 * loop, so a client passed in here must NOT install ContentNegotiation (it adds
 * an `Accept` header the CDN answers with 406) or HttpRequestRetry (it
 * compounds with [retryPolicy]). App-wide clients usually have both; give the
 * SDK its own.
 *
 * Lifecycle: call [close] when done if you let the SDK construct the
 * underlying HTTP client (the default). If you passed your own [HttpClient],
 * you own its lifecycle.
 */
class PikPakClient(
    val account: String,
    passwordSupplier: suspend () -> String,
    val sessionStore: SessionStore = FileSessionStore(),
    val rateLimiter: RateLimiter = RateLimiter.default(),
    val retryPolicy: RetryPolicy = RetryPolicy.Default,
    httpClient: HttpClient? = null,
) {
    constructor(
        account: String,
        password: String,
        sessionStore: SessionStore = FileSessionStore(),
        rateLimiter: RateLimiter = RateLimiter.default(),
        retryPolicy: RetryPolicy = RetryPolicy.Default,
        httpClient: HttpClient? = null,
    ) : this(account, { password }, sessionStore, rateLimiter, retryPolicy, httpClient)

    val deviceId: String = MD5().digest(account.encodeToByteArray()).toHex()

    private val ownsHttpClient = httpClient == null
    private val client: HttpClient = httpClient ?: HttpEngine.defaultClient()
    internal val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    internal val state = ClientState(passwordSupplier)
    internal val mutex = Mutex()
    internal val http = HttpEngine(client, this)
    internal val auth = AuthApi(this)

    /**
     * Ensures the client has a valid access token. Reuses a cached session if
     * one is still fresh; refreshes if expired; falls back to a full credential
     * sign-in if the refresh token is also stale. Safe to call repeatedly.
     */
    suspend fun login(): Session = mutex.withLock { auth.loginLocked() }

    /**
     * Warms the client up: authenticates and performs one cheap API call so the
     * captcha handshake happens now rather than on the first request the user
     * is waiting for. Failures propagate — a caller that wants best-effort
     * warm-up should catch.
     */
    suspend fun prewarm(): QuotaResponse {
        login()
        return getQuota()
    }

    /** Discards the cached session (in-memory and on disk). Next [login] will re-authenticate. */
    suspend fun logout() = mutex.withLock {
        state.session = null
        // The captcha token is bound to the signed-out user id; keeping it
        // would send the next account's requests with the previous one's token.
        state.captchaToken = ""
        sessionStore.clear(account)
    }

    /** Currently cached session, if any. Read-only snapshot. */
    val currentSession: Session? get() = state.session

    /**
     * The same value as [currentSession], observable. Emits on every login,
     * refresh and logout, so a UI can follow sign-in state without polling.
     */
    val sessionFlow: StateFlow<Session?> = state.sessionFlow.asStateFlow()

    /** Closes the underlying HTTP client if it was created by this SDK. No-op otherwise. */
    fun close() {
        if (ownsHttpClient) client.close()
    }
}

internal class ClientState(val passwordSupplier: suspend () -> String) {
    val sessionFlow = MutableStateFlow<Session?>(null)
    var session: Session?
        get() = sessionFlow.value
        set(value) {
            sessionFlow.value = value
        }

    @Volatile var captchaToken: String = ""
}
