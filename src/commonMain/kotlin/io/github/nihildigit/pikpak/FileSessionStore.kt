package io.github.nihildigit.pikpak

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import org.kotlincrypto.hash.md.MD5

/**
 * Persists [Session] as JSON files in [dir]. One file per account, keyed by
 * md5(account) so the filename never reveals the email/phone.
 *
 * Written to a temporary file and moved over the old one. Losing the session
 * is not harmless: the refresh token lives nowhere else, and without it the
 * next start has to sign in with the password (see AuthApi.commitSession). An
 * overwrite in place that a crash interrupted left a file that failed to parse
 * and read as no session at all.
 *
 * On Android there is no default [dir]; see [defaultSessionDir].
 */
class FileSessionStore(
    private val dir: Path = defaultSessionDir(),
    private val json: Json = defaultJson,
) : SessionStore {

    override suspend fun load(account: String): Session? {
        val file = sessionPath(account)
        if (!SystemFileSystem.exists(file)) return null
        return runCatching {
            val text = SystemFileSystem.source(file).buffered().use { it.readString() }
            json.decodeFromString(Session.serializer(), text)
        }.getOrNull()
    }

    override suspend fun save(account: String, session: Session) {
        ensureDir()
        val file = sessionPath(account)
        val temp = Path(dir, "${file.name}.tmp")
        val text = json.encodeToString(Session.serializer(), session)
        SystemFileSystem.sink(temp).buffered().use { it.writeString(text) }
        SystemFileSystem.atomicMove(temp, file)
    }

    override suspend fun clear(account: String) {
        val file = sessionPath(account)
        if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
    }

    private fun ensureDir() {
        if (!SystemFileSystem.exists(dir)) SystemFileSystem.createDirectories(dir)
    }

    private fun sessionPath(account: String): Path {
        val digest = MD5().digest(account.encodeToByteArray()).toHex()
        return Path(dir, "session_$digest.json")
    }

    companion object {
        private val defaultJson = Json { ignoreUnknownKeys = true }
    }
}
