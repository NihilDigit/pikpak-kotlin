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
 * The file is rewritten atomically-ish via best-effort overwrite. PikPak
 * sessions are not catastrophic to lose (we just re-login), so we do not
 * implement a fsync-rename dance — keep the SDK dependency surface small.
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
        val text = json.encodeToString(Session.serializer(), session)
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }
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

internal fun ByteArray.toHex(): String = joinToString("") {
    val v = it.toInt() and 0xff
    val hi = v ushr 4
    val lo = v and 0x0f
    "${hexChar(hi)}${hexChar(lo)}"
}

private fun hexChar(v: Int): Char = if (v < 10) ('0' + v) else ('a' + (v - 10))
