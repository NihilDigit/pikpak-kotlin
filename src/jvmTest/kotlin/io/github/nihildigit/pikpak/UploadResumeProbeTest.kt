package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * [continueUpload] from a different process than [startUpload]. Two separate
 * Gradle runs, so two JVMs; stop the daemon in between to be sure:
 *
 *   PIKPAK_PROBE=1 PIKPAK_RESUME_STAGE=1 ./gradlew jvmTest --tests '*UploadResumeProbe*' --rerun
 *   ./gradlew --stop
 *   PIKPAK_PROBE=1 PIKPAK_RESUME_STAGE=2 ./gradlew jvmTest --tests '*UploadResumeProbe*' --rerun
 *
 * Stage 1 starts a 3 MiB random upload in a fresh `pikpak-kotlin-probe-resume-*`
 * folder with a source that runs dry after two parts, and saves the session.
 * Stage 2 lists the parts one per page, so the OSS paging parameters are
 * exercised, finishes the upload, reads the file back, then deletes the folder
 * and the local state, which holds live credentials.
 */
class UploadResumeProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"
    private val stage = System.getenv("PIKPAK_RESUME_STAGE")

    private val dir = File("build/upload-resume-probe")
    private val sessionFile = File(dir, "session.json")
    private val folderFile = File(dir, "folder.txt")
    private val contentFile = File(dir, "content.bin")

    @Test
    fun `stage 1 start and stop after two parts`(): Unit = runBlocking {
        Assumptions.assumeTrue(enabled && stage == "1", "set PIKPAK_PROBE=1 PIKPAK_RESUME_STAGE=1")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        try {
            client.login()
            val ts = Clock.System.now().toEpochMilliseconds()
            val folderId = client.createFolder("", "pikpak-kotlin-probe-resume-$ts")
            val content = Random(ts).nextBytes(3 * 1024 * 1024)
            val gcid = PikPakHash.fromSource(Buffer().apply { write(content) }, content.size.toLong())
            val start = client.startUpload(folderId, "resume-$ts.bin", content.size.toLong(), gcid)
            val session = (start as UploadStart.Pending).session
            println("[stage1] partSize=${session.partSize} expiration=${session.expiration}")
            val twoParts = (2 * session.partSize).toInt()
            val stopped = runCatching {
                client.continueUpload(session, open = { offset -> Buffer().apply { write(content, offset.toInt(), twoParts) } })
            }
            println("[stage1] continue stopped as intended: ${stopped.exceptionOrNull()?.message}")
            dir.mkdirs()
            sessionFile.writeText(Json.encodeToString(UploadSession.serializer(), session))
            folderFile.writeText(folderId)
            contentFile.writeBytes(content)
        } finally {
            client.close()
        }
    }

    @Test
    fun `stage 2 continue in another process`(): Unit = runBlocking {
        Assumptions.assumeTrue(enabled && stage == "2", "set PIKPAK_PROBE=1 PIKPAK_RESUME_STAGE=2")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val session = Json.decodeFromString(UploadSession.serializer(), sessionFile.readText())
        val folderId = folderFile.readText()
        val content = contentFile.readBytes()
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        try {
            client.login()
            val listed = client.listUploadedParts(session, pageSize = 1)
            println("[stage2] parts on OSS, one per page: ${listed.keys}")
            assertEquals(setOf(1, 2), listed.keys)

            val opened = mutableListOf<Long>()
            val progress = mutableListOf<Long>()
            client.continueUpload(
                session,
                open = { offset -> opened += offset; Buffer().apply { write(content, offset.toInt(), content.size) } },
                onProgress = { progress += it },
            )
            println("[stage2] opened at $opened, progress first=${progress.first()} last=${progress.last()}")
            assertEquals(listOf(2 * session.partSize), opened)
            assertEquals(2 * session.partSize, progress.first())

            var detail = client.getFile(session.fileId)
            repeat(15) {
                if (detail.phase == TaskPhase.COMPLETE) return@repeat
                delay(1000)
                detail = client.getFile(session.fileId)
            }
            println("[stage2] phase=${detail.phase}")
            assertEquals(TaskPhase.COMPLETE, detail.phase)
            val back = client.streamRangeFromUrl(detail.downloadUrl!!, 0, content.size.toLong()) { it.channel.toByteArray() }
            assertContentEquals(content, back)
            println("[stage2] read back identical")
        } finally {
            runCatching { client.batchTrash(listOf(folderId)); delay(1500); client.batchDelete(listOf(folderId)) }
                .onFailure { println("[cleanup] $it") }
            val gone = runCatching { client.getFile(folderId); false }.getOrDefault(true)
            println("[cleanup] folder gone=$gone")
            assertTrue(gone)
            client.close()
            dir.deleteRecursively()
        }
    }
}
