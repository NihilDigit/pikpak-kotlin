package io.github.nihildigit.pikpak

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A persisted PikPak login session. Stored as JSON via [SessionStore].
 *
 * `expiresAt` is the UNIX epoch second after which the access token must be
 * refreshed. We deliberately under-report this (subtract a small skew at save
 * time) so callers don't race against expiry.
 */
@Serializable
data class Session(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val sub: String,
    @SerialName("expires_at") val expiresAt: Long,
)

interface SessionStore {
    suspend fun load(account: String): Session?
    suspend fun save(account: String, session: Session)
    suspend fun clear(account: String)
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

    override suspend fun load(account: String): Session? = mutex.withLock { storage[account] }

    override suspend fun save(account: String, session: Session) = mutex.withLock {
        storage[account] = session
        Unit
    }

    override suspend fun clear(account: String) = mutex.withLock {
        storage.remove(account)
        Unit
    }
}
