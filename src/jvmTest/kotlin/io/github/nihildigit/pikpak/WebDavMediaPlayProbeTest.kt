package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.test.Test

/**
 * What [WebDavClient.optimizedForMediaPlay] changes. A throwaway client is created, a directory is
 * listed and the first KiB of one file read with the flag on and then off, and the client is deleted.
 * Compares listing size and how a file read is answered (302 to a CDN link, or bytes from the DAV
 * host). Read-only on the drive; touches only the client it creates. Prints hosts and statuses,
 * never credentials, paths or file names. Opt in with PIKPAK_PROBE=1.
 */
class WebDavMediaPlayProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    @Test
    fun `compare webdav reads with and without media play optimisation`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no credentials in .env")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        var created: WebDavClient? = null
        try {
            client.login()
            check(client.getWebDav().enabled) { "WebDAV is off on this account" }
            created = client.createWebDavClient("piko-probe")
            println("[dav] created client, flag=${created.optimizedForMediaPlay} readOnly=${created.readOnly} maxEntries=${created.maxEntriesInResponse}")
            val auth = "Basic " + Base64.getEncoder().encodeToString("${created.username}:${created.password}".toByteArray())

            val file = findFile(auth) ?: error("no file found within the walk limit")
            for (flag in listOf(true, false)) {
                val current = client.getWebDav().clients.first { it.id == created.id }
                client.updateWebDavClient(current, optimizedForMediaPlay = flag)
                val after = client.getWebDav().clients.first { it.id == created.id }
                println("[dav] --- flag set to $flag (reads back ${after.optimizedForMediaPlay}) ---")
                val listing = propfind(auth, file.parent)
                println("[dav] listing of the file's folder: status=${listing.first} entries=${listing.second.size}")
                read(auth, file.href)
            }
        } finally {
            created?.let { runCatching { client.deleteWebDavClient(client.getWebDav().clients.first { c -> c.id == it.id }) } }
            println("[dav] probe client deleted=${created != null && client.getWebDav().clients.none { it.id == created.id }}")
            client.close()
        }
    }

    private class DavFile(val href: String, val parent: String)

    private fun findFile(auth: String): DavFile? {
        val queue = ArrayDeque(listOf("/"))
        var walked = 0
        while (queue.isNotEmpty() && walked < 40) {
            val dir = queue.removeFirst()
            walked++
            val (_, entries) = propfind(auth, dir)
            for ((href, isDir) in entries) {
                if (href.trimEnd('/') == dir.trimEnd('/')) continue
                if (isDir) queue.addLast(href) else return DavFile(href, dir)
            }
        }
        return null
    }

    /** Status and (href, isCollection) of a depth-1 listing. */
    private fun propfind(auth: String, path: String): Pair<Int, List<Pair<String, Boolean>>> {
        val request = HttpRequest.newBuilder(URI(WebDav.ENDPOINT + path))
            .header("Authorization", auth)
            .header("Depth", "1")
            .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val responses = Regex("<(?:\\w+:)?response>(.*?)</(?:\\w+:)?response>", RegexOption.DOT_MATCHES_ALL)
            .findAll(response.body()).map { it.groupValues[1] }.toList()
        val entries = responses.mapNotNull { block ->
            val href = Regex("<(?:\\w+:)?href>(.*?)</(?:\\w+:)?href>").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val decodedPath = URI(href).rawPath.let { URLDecoder.decode(it, Charsets.UTF_8) }
            val reencoded = decodedPath.split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
            reencoded to Regex("<(?:\\w+:)?collection\\s*/>").containsMatchIn(block)
        }
        return response.statusCode() to entries
    }

    private fun read(auth: String, path: String) {
        val request = HttpRequest.newBuilder(URI(WebDav.ENDPOINT + path))
            .header("Authorization", auth)
            .header("Range", "bytes=0-1023")
            .GET()
            .build()
        val started = System.nanoTime()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        val millis = (System.nanoTime() - started) / 1_000_000
        val location = response.headers().firstValue("Location").orElse(null)
        val locationHost = location?.let { runCatching { URI(it).host }.getOrNull() }
        println(
            "[dav] read: status=${response.statusCode()} in ${millis}ms bytes=${response.body().size} " +
                "type=${response.headers().firstValue("Content-Type").orElse("-")} " +
                "range=${response.headers().firstValue("Content-Range").orElse("-")} redirectHost=${locationHost ?: "-"}",
        )
        if (location != null) {
            val followed = http.send(
                HttpRequest.newBuilder(URI(location)).header("Range", "bytes=0-1023").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            println("[dav]   redirect target: status=${followed.statusCode()} bytes=${followed.body().size}")
        }
    }
}
