package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.AuthApi
import io.github.nihildigit.pikpak.internal.HttpEngine
import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
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
 * Concurrency: every public method is a `suspend` function and is safe to call
 * from many coroutines concurrently. Internal mutation (session refresh,
 * captcha refresh) is serialized through a mutex.
 *
 * Lifecycle: call [close] when done if you let the SDK construct the
 * underlying HTTP client (the default). If you passed your own [HttpClient],
 * you own its lifecycle.
 */
class PikPakClient(
    val account: String,
    password: String,
    val sessionStore: SessionStore = FileSessionStore(),
    val rateLimiter: RateLimiter = RateLimiter.default(),
    val retryPolicy: RetryPolicy = RetryPolicy.Default,
    httpClient: HttpClient? = null,
) {
    val deviceId: String = MD5().digest(account.encodeToByteArray()).toHex()

    private val ownsHttpClient = httpClient == null
    private val client: HttpClient = httpClient ?: HttpEngine.defaultClient()
    internal val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    internal val state = ClientState(password)
    internal val mutex = Mutex()
    internal val http = HttpEngine(client, this)
    internal val auth = AuthApi(this)

    /**
     * Ensures the client has a valid access token. Reuses a cached session if
     * one is still fresh; refreshes if expired; falls back to a full credential
     * sign-in if the refresh token is also stale. Safe to call repeatedly.
     */
    suspend fun login(): Session = mutex.withLock { auth.loginLocked() }

    /** Discards the cached session (in-memory and on disk). Next [login] will re-authenticate. */
    suspend fun logout() = mutex.withLock {
        state.session = null
        sessionStore.clear(account)
    }

    /** Currently cached session, if any. Read-only snapshot. */
    val currentSession: Session? get() = state.session

    /** Closes the underlying HTTP client if it was created by this SDK. No-op otherwise. */
    fun close() {
        if (ownsHttpClient) client.close()
    }
}

internal class ClientState(val password: String) {
    @Volatile var session: Session? = null
    @Volatile var captchaToken: String = ""
}
