package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.buildUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.jupiter.api.Assumptions
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test

/**
 * How the share endpoints behave, for the KDoc in ShareEndpoint.kt. The first
 * test only reads the account's own share list. The second works on folders
 * and shares it creates, cancels the shares and permanently deletes the
 * folders at the end. Restoring cannot be exercised end to end: the server
 * refuses to restore one's own share, and this account has no other.
 * Opt in with PIKPAK_PROBE=1.
 */
class ShareProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    @Test
    fun `list my shares`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        val page = client.listMyShares(pageSize = 5)
        println("[list] ${page.shares.size} next=${page.nextPageToken.isNotEmpty()}")
        // Titles are the user's file names; print their length only.
        page.shares.forEach {
            println("  ${it.shareStatus} to=${it.shareTo} files=${it.fileNum} kind=${it.fileKind} expires=${it.expirationAt} title=${it.title.length}ch")
        }
        // Leftovers of a crashed lifecycle run show up here.
        val probes = client.listMyShares(pageSize = 100).shares.count { it.title.startsWith("piko-probe-share-") }
        println("[probe shares left] $probes")
        client.close()
    }

    @Test
    fun `share lifecycle on probe folders`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        val name = "piko-probe-share-${System.currentTimeMillis()}"
        val folders = mutableListOf<String>()
        val shares = mutableListOf<String>()
        val tmp = createTempDirectory("piko-probe")
        try {
            val src = client.createFolder("", name).also { folders += it }
            val sub = client.createFolder(src, "sub")
            val dest = client.createFolder("", "$name-dest").also { folders += it }
            for ((parent, suffix) in listOf(src to "a", sub to "b")) {
                val file = tmp.resolve("$name-$suffix.bin").toFile().apply { writeBytes(Random.nextBytes(4096)) }
                client.upload(parent, Path(file.path))
            }

            val public = client.createShare(listOf(src), expirationDays = 1).also { shares += it.shareId }
            val locked = client.createShare(listOf(src), requirePassCode = true, expirationDays = 1).also { shares += it.shareId }
            println("[create] public=${public.shareUrl} passCode='${public.passCode}' locked passCode='${locked.passCode}'")
            println("[shareIdFromUrl] ${shareIdFromUrl(public.shareUrl) == public.shareId}")

            // The web client sends custom_pass_code only on PATCH; see whether creation takes it.
            val customShare = client.createShare(listOf(src), customPassCode = "zq47", expirationDays = 1).also { shares += it.shareId }
            println("[custom pass code] asked zq47 got '${customShare.passCode}'")

            val mine = client.listMyShares(pageSize = 10).shares.filter { it.shareId in shares }
            mine.forEach { println("[listed] ${it.shareTo} status=${it.shareStatus} days=${it.expirationDays} at=${it.expirationAt} fileId=${it.fileId == src}") }

            val info = client.getShareInfo(public.shareId)
            println("[info public] files=${info.files.map { it.name }} token=${info.passCodeToken.isNotEmpty()} owner=${info.owner.nickname}")
            for (code in listOf("", "zzzz")) {
                val e = runCatching { client.getShareInfo(locked.shareId, code) }.exceptionOrNull()
                println("[info locked code='$code'] ${(e as? ShareUnavailableException)?.status ?: e}")
            }
            val lockedInfo = client.getShareInfo(locked.shareId, locked.passCode)
            println("[info locked right] files=${lockedInfo.files.size}")

            val top = client.listShareFiles(locked.shareId, lockedInfo.passCodeToken)
            println("[detail top] ${top.files.map { it.name }}")
            val inSrc = client.listShareFiles(locked.shareId, lockedInfo.passCodeToken, parentId = src)
            println("[detail src] ${inSrc.files.map { "${it.kind} ${it.name}" }}")
            val inSub = client.listShareFiles(locked.shareId, lockedInfo.passCodeToken, parentId = sub)
            println("[detail sub] ${inSub.files.map { it.name }}")
            val noToken = runCatching { client.listShareFiles(public.shareId, "", parentId = src) }
            println("[detail public no token] ${noToken.map { it.files.size }.getOrElse { (it as? ShareUnavailableException)?.status ?: it }}")

            // A fresh session whose first request is a share read: fails without X-Client-Id.
            val fresh = PikPakClient(account = username, password = password, sessionStore = InMemorySessionStore())
            println("[fresh session read] ${runCatching { fresh.getShareInfo(public.shareId).shareStatus }}")
            fresh.close()

            println("[anonymous read] ${anonymousRead(public.shareId)}")

            val own = runCatching { client.restoreShare(public.shareId, info.passCodeToken, listOf(src), toParentId = dest) }
            println("[restore own] ${own.exceptionOrNull()?.message ?: own.getOrNull()}")
            println("[dest after own restore] ${client.listFiles(dest).size}")

            client.deleteShares(shares)
            val afterDelete = runCatching { client.getShareInfo(public.shareId) }.exceptionOrNull()
            println("[after delete] ${(afterDelete as? ShareUnavailableException)?.status ?: afterDelete}")
            val stillListed = client.listMyShares(pageSize = 10).shares.filter { it.shareId in shares }
            println("[listed after delete] ${stillListed.map { it.shareStatus }}")
            shares.clear()
        } finally {
            if (shares.isNotEmpty()) println("[cleanup shares] ${runCatching { client.deleteShares(shares) }}")
            println("[cleanup folders] ${runCatching { client.batchDelete(folders) }}")
            delay(2_000)
            for (id in folders) println("[gone] $id ${runCatching { client.getFile(id) }.exceptionOrNull()?.message}")
            println("[left at root] ${client.listFiles("").count { it.name.startsWith(name) }}")
            tmp.toFile().deleteRecursively()
            client.close()
        }
    }

    /** No login: device id plus a captcha token from an anonymous captcha/init. */
    private fun anonymousRead(shareId: String): String {
        val device = "0123456789abcdef0123456789abcdef"
        val http = HttpClient.newHttpClient()
        val initBody = """{"client_id":"${PikPakConstants.CLIENT_ID}","device_id":"$device","action":"GET:/drive/v1/share","meta":{}}"""
        val init = http.send(
            HttpRequest.newBuilder(URI("${PikPakConstants.USER_BASE}/v1/shield/captcha/init"))
                .header("User-Agent", PikPakConstants.USER_AGENT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(initBody)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val captcha = Regex("\"captcha_token\":\"([^\"]+)\"").find(init.body())?.groupValues?.get(1).orEmpty()
        val url = buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/share", mapOf("share_id" to shareId, "limit" to "5"))
        fun get(withCaptcha: Boolean) = http.send(
            HttpRequest.newBuilder(URI(url))
                .header("User-Agent", PikPakConstants.USER_AGENT)
                .header("X-Device-Id", device)
                .header("X-Client-Id", PikPakConstants.CLIENT_ID)
                .apply { if (withCaptcha) header("X-Captcha-Token", captcha) }
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).let { "http=${it.statusCode()} ${Regex("\"(share_status|error)\":\"[^\"]*\"").find(it.body())?.value}" }
        return "captcha init http=${init.statusCode()}; without captcha ${get(false)}; with captcha ${get(true)}"
    }
}
