package io.github.nihildigit.pikpak

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

class InMemorySessionStore : SessionStore {
    private val storage = mutableMapOf<String, Session>()

    override suspend fun load(account: String): Session? = storage[account]
    override suspend fun save(account: String, session: Session) {
        storage[account] = session
    }
    override suspend fun clear(account: String) {
        storage.remove(account)
    }
}
