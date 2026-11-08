package io.github.nihildigit.pikpak

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * A persisted PikPak login session. Stored as JSON via [SessionStore].
 *
 * `expiresAt` is the UNIX epoch second after which the access token must be
 * refreshed. It is set a few minutes before the server's own expiry when the
 * token response is parsed, and the client refreshes before sending a request
 * once it has passed, so no request goes out on a token about to lapse.
 */
@Serializable
data class Session(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val sub: String,
    @SerialName("expires_at") val expiresAt: Long,
) {
    /** Leaves the tokens out: a session printed into a log or a crash report would hand the account over. */
    override fun toString(): String = "Session(sub=$sub, expiresAt=$expiresAt)"
}

/**
 * Whether this can authorize a request now. A store that hands back a placeholder carrying
 * only a refresh token reports a blank access token, and `Authorization: Bearer ` fails every
 * call until something forces a re-login.
 */
internal fun Session.isUsable(): Boolean = accessToken.isNotEmpty() && expiresAt > Clock.System.now().epochSeconds

/**
 * Cached non-secret account state (storage quota, transfer allowances, user profile)
 * persisted alongside the account's [Session] so a client starts with the last known
 * state already populated in [PikPakClient.quotaFlow], [PikPakClient.transferQuotaFlow]
 * and [PikPakClient.userProfileFlow] before the network answers.
 */
@Serializable
data class AccountSnapshot(
    val quota: QuotaResponse? = null,
    val transferQuota: TransferQuota? = null,
    val userProfile: UserProfile? = null,
    @SerialName("updated_at_epoch_ms") val updatedAtEpochMs: Long = 0L,
)

interface SessionStore {
    suspend fun load(account: String): Session?
    suspend fun save(account: String, session: Session)
    suspend fun clear(account: String)

    /**
     * Loads the last persisted [AccountSnapshot] for [account], or null when none is stored.
     * Default implementation returns null so minimal custom stores only have to implement
     * the three session methods.
     */
    suspend fun loadAccountSnapshot(account: String): AccountSnapshot? = null

    /**
     * Persists [snapshot] for [account]. Default implementation is a no-op.
     */
    suspend fun saveAccountSnapshot(account: String, snapshot: AccountSnapshot) {}
}

/**
 * Sessions held for the process lifetime only.
 *
 * Access is serialised: a plain map is not safe to mutate from several
 * coroutines, and the store is reached from every login and every token
 * refresh, which do run concurrently.
 */
class InMemorySessionStore : SessionStore {
    private val mutex = Mutex()
    private val storage = mutableMapOf<String, Session>()
    private val snapshots = mutableMapOf<String, AccountSnapshot>()

    override suspend fun load(account: String): Session? = mutex.withLock { storage[account] }

    override suspend fun save(account: String, session: Session) = mutex.withLock {
        storage[account] = session
        Unit
    }

    override suspend fun clear(account: String) = mutex.withLock {
        storage.remove(account)
        snapshots.remove(account)
        Unit
    }

    override suspend fun loadAccountSnapshot(account: String): AccountSnapshot? = mutex.withLock {
        snapshots[account]
    }

    override suspend fun saveAccountSnapshot(account: String, snapshot: AccountSnapshot) = mutex.withLock {
        snapshots[account] = snapshot
        Unit
    }
}
