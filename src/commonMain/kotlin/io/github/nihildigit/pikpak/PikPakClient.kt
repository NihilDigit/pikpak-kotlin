package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.AuthApi
import io.github.nihildigit.pikpak.internal.FolderIdCache
import io.github.nihildigit.pikpak.internal.ForegroundStreams
import io.github.nihildigit.pikpak.internal.HostHealth
import io.github.nihildigit.pikpak.internal.HttpEngine
import io.github.nihildigit.pikpak.internal.PriorityGate
import io.github.nihildigit.pikpak.internal.defaultCdnHttpClient
import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Duration
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
    cdnHttpClient: HttpClient? = null,
    val connectionBudget: Int = DEFAULT_CONNECTION_BUDGET,
    /**
     * Ceiling on concurrent CDN connections across every file this client
     * reads or downloads. See [DEFAULT_ACCOUNT_CONNECTION_BUDGET].
     *
     * Lower it to leave headroom for another client on the same account, or
     * set it to [connectionBudget] to let one file at a time have the line.
     */
    val accountConnectionBudget: Int = DEFAULT_ACCOUNT_CONNECTION_BUDGET,
) {
    constructor(
        account: String,
        password: String,
        sessionStore: SessionStore = FileSessionStore(),
        rateLimiter: RateLimiter = RateLimiter.default(),
        retryPolicy: RetryPolicy = RetryPolicy.Default,
        httpClient: HttpClient? = null,
        cdnHttpClient: HttpClient? = null,
        connectionBudget: Int = DEFAULT_CONNECTION_BUDGET,
        accountConnectionBudget: Int = DEFAULT_ACCOUNT_CONNECTION_BUDGET,
    ) : this(
        account,
        { password },
        sessionStore,
        rateLimiter,
        retryPolicy,
        httpClient,
        cdnHttpClient,
        connectionBudget,
        accountConnectionBudget,
    )

    val deviceId: String = MD5().digest(account.encodeToByteArray()).toHex()

    private val ownsHttpClient = httpClient == null
    private val client: HttpClient = httpClient ?: HttpEngine.defaultClient()

    private val ownsCdnClient = cdnHttpClient == null && httpClient == null

    /**
     * Client for signed CDN and OSS URLs.
     *
     * When the SDK owns its clients this is a separate, per-platform-tuned one
     * allowing as many connections per host as the account gate does, because
     * every engine's default cap is lower, the CDN offers no HTTP/2 to
     * multiplex over, and the gates are what should be doing the limiting.
     *
     * An injected [httpClient] is reused here rather than quietly opening a
     * second connection pool behind the caller's back. That costs the tuning:
     * to keep both, pass [tunedCdnClient] as `cdnHttpClient`.
     */
    private val cdn: Lazy<HttpClient> = lazy {
        cdnHttpClient ?: httpClient ?: defaultCdnHttpClient(maxOf(connectionBudget, accountConnectionBudget))
    }
    /**
     * The account-wide half of the two-level connection limit.
     *
     * Every CDN read takes a slot here as well as one from its own file's
     * budget, so a client cannot exceed [accountConnectionBudget] no matter
     * how many files it has open. Priority is honoured across files: a
     * playback read outranks a background download's even though they are
     * reading different URLs, which is the whole reason this is not just N
     * independent per-file gates.
     */
    internal val accountGate = PriorityGate(accountConnectionBudget)

    /**
     * Foreground readers open on this account. Priority alone orders the
     * queue but cannot take back a slot a background read already holds, so
     * background readers also shrink their in-flight count while this is
     * non-zero; see [PikPakStreamReader.role].
     */
    internal val foregroundStreams = ForegroundStreams()

    /** Edge hosts that failed recently, so no file on the account mints a link to one again soon. */
    internal val hostHealth = HostHealth()

    // Keyed by content and variant: a transcode's length is only learned by a probe, and it
    // cannot change while the content exists, whichever file object or link reaches it.
    private val streamSizes = MutableStateFlow<Map<String, Long>>(emptyMap())

    internal fun knownStreamSize(gcid: String, mediaId: String): Long? = streamSizes.value["$gcid/$mediaId"]

    internal fun rememberStreamSize(gcid: String, mediaId: String, size: Long) {
        streamSizes.update { it + ("$gcid/$mediaId" to size) }
    }

    init {
        // A gate of zero never grants its first slot — nothing holds one, so
        // nothing releases one — and every range read parks forever instead of
        // reporting the misconfiguration. RangeReader checks its own budget the
        // same way.
        require(connectionBudget >= 1) { "connectionBudget must be >= 1, got $connectionBudget" }
        require(accountConnectionBudget >= 1) {
            "accountConnectionBudget must be >= 1, got $accountConnectionBudget"
        }
    }

    internal val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    internal val state = ClientState(passwordSupplier)
    internal val mutex = Mutex()
    internal val http = HttpEngine(client, this) { cdn.value }
    internal val auth = AuthApi(this)
    internal val folderIds = FolderIdCache()

    /**
     * Drops the memoized path-to-folder-id map. The SDK clears it after every
     * mutation it performs itself; call this when a folder was moved, renamed
     * or deleted through some other client.
     */
    suspend fun clearFolderIdCache() = folderIds.invalidateAll()

    /**
     * Ensures the client has a valid access token: the in-memory session, the
     * stored one, a refresh, and only then a password sign-in. A dead refresh
     * token is cleared from the store before that sign-in. Safe to call
     * repeatedly. Optional: any API call logs in the same way first.
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

    /** Closes the underlying HTTP clients that were created by this SDK. No-op for injected ones. */
    fun close() {
        if (ownsHttpClient) client.close()
        if (ownsCdnClient && cdn.isInitialized()) cdn.value.close()
    }

    companion object {
        /**
         * Concurrent connections one file reads over, and the default budget
         * of a [RangeReader].
         *
         * Measured 2026-09-02: one signed PikPak URL accepts exactly 8
         * concurrent connections and answers the 9th onward with 503, the
         * excess count matching n − 8 precisely. Two different URLs get 8 each,
         * so the cap is per URL or per edge host rather than per client.
         * Per-connection throughput sits near 0.8 MB/s regardless of how many
         * are open, so the aggregate scales linearly to that ceiling.
         *
         * See [DEFAULT_ACCOUNT_CONNECTION_BUDGET] for the wider limit above it.
         */
        const val DEFAULT_CONNECTION_BUDGET = 8

        /**
         * Concurrent connections one account may hold across all files at once.
         *
         * A second, wider limit sits above the per-URL one: 8 on each of
         * several URLs is eventually refused too, either with 503 or by
         * dropping the TLS handshake before a status line. Measured 2026-09-10
         * against a 54-file pack spread over 12 distinct edge hosts — 16 were
         * admitted without a single refusal, while 32, 64 and 96 requested all
         * settled at exactly 20 admitted.
         *
         * Measured again 2026-09-26 on 720P transcodes of 12 files: 8 and 16
         * requested were all admitted; 24, 32 and 40 requested got 11, 13 and 9,
         * the rest answered 503. Past the limit, asking for more got fewer, so
         * overshooting it costs connections rather than merely wasting requests.
         *
         * 16 is the largest round number under that ceiling, and it divides
         * into the two consumers that matter: playback holding 8 on one file
         * while a background download holds 8 on another. Nothing has to
         * reserve them — the per-URL cap already stops one file from taking
         * more than half, and priority decides the rest.
         *
         * Not a per-host limit; the refusals appeared with the connections
         * spread across a dozen hosts.
         *
         * What this bounds is what PikPak tolerates, which is not the same as
         * what a consumer's line can carry, and the smaller of the two governs.
         * At the ~0.8 MB/s per connection measured above, eight connections
         * already fill 50 Mbit/s; opening sixteen there does not move more
         * bytes, it only makes each one slower and the slowest ones much
         * slower. A consumer on a constrained line should lower this to roughly
         * its bandwidth divided by that per-connection figure — on such a line
         * playback stalls and long-tail latency were measured getting worse,
         * and total throughput falling by a third, purely from the extra
         * concurrency. Raise it towards this default only with the bandwidth to
         * use it.
         */
        const val DEFAULT_ACCOUNT_CONNECTION_BUDGET = 16

        /**
         * The per-platform CDN client the SDK would build for itself. Pass it
         * as `cdnHttpClient` when you inject your own API client but still
         * want the tuned connection pool for downloads. You own its lifecycle.
         *
         * [perHostLimit] should be at least the client's account budget: the
         * gates are the limit, and an engine cap under them only queues
         * requests where RangeReader cannot see them.
         */
        fun tunedCdnClient(perHostLimit: Int = DEFAULT_ACCOUNT_CONNECTION_BUDGET): HttpClient =
            defaultCdnHttpClient(perHostLimit)
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
