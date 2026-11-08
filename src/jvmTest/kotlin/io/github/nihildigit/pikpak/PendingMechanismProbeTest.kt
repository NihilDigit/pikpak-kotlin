package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.OssSign
import io.github.nihildigit.pikpak.internal.formatHttpDate
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.io.Buffer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions
import java.util.UUID
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.TimeSource

/** Local opt-in protocol experiment. Only its uniquely created remote root is deleted. */
class PendingMechanismProbeTest {
    private val env = dotenv { directory = "."; ignoreIfMissing = true; ignoreIfMalformed = true }
    private val clock = TimeSource.Monotonic.markNow()
    private fun now() = clock.elapsedNow().inWholeMilliseconds
    private val web = HttpClient(OkHttp) { expectSuccess = false }
    private data class Reply(val status: Int, val body: String, val etag: String?, val at: Long)
    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun safe(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, v) ->
            if (listOf("secret", "token", "access_key_id", "authorization", "signature", "user_id").any { key.contains(it, true) })
                JsonPrimitive("<redacted length=${(v as? JsonPrimitive)?.content?.length ?: v.toString().length}>")
            else safe(v)
        })
        is JsonArray -> JsonArray(value.map(::safe))
        is JsonPrimitive -> if (value.isString && value.content.startsWith("http") && value.content.contains('?'))
            JsonPrimitive(value.content.substringBefore('?') + "?<redacted query>") else value
        else -> value
    }

    @Test
    fun `measure pending mechanism and direct put permissions`() = runBlocking<Unit> {
        Assumptions.assumeTrue((System.getenv("PIKPAK_PROBE") ?: env["PIKPAK_PROBE"]) == "1")
        val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
        val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
        Assumptions.assumeTrue(username != null && password != null, "no live credentials")
        val client = PikPakClient(account = username!!, password = password!!, rateLimiter = RateLimiter(64, 60.0))
        var folder: String? = null
        try {
            client.login()
            val name = "pikpak-kotlin-pending-probe-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            folder = client.createFolder("", name)
            println("[pending] ROOT name=$name id=$folder utc=${Clock.System.now()}")
            for (mode in listOf("multipart", "put")) {
                coroutineScope { (1..6).map { index -> async { sample(client, folder, mode, index) } }.awaitAll() }
            }
        } finally {
            try {
                withContext(NonCancellable) {
                    folder?.let { id ->
                        client.batchTrash(listOf(id))
                        client.batchDelete(listOf(id))
                        var gone = false
                        repeat(30) {
                            if (!gone) {
                                try { client.getFile(id) } catch (e: PikPakException) {
                                    if (e.httpStatus != 404) throw e
                                    gone = true
                                }
                                if (!gone) delay(500)
                            }
                        }
                        check(gone) { "probe root deletion unconfirmed: $id" }
                        println("[pending] CLEANUP root=$id trash=success permanentDelete=success verified404=true")
                    }
                }
            } finally { client.close(); web.close() }
        }
    }

    private suspend fun sample(client: PikPakClient, folder: String, mode: String, index: Int) {
        val label = "$mode-$index"
        val bytes = (List(5) { UUID.randomUUID().toString() }.joinToString("\n") + "\n").padEnd(200, ' ').encodeToByteArray()
        val hash = PikPakHash.fromSource(Buffer().apply { write(bytes) }, bytes.size.toLong())
        val node = client.createUploadNode(folder, "$label.txt", bytes.size.toLong(), hash)
        println("[pending] CREATE label=$label keys=${node.response.keys} raw=${safe(node.response)}")
        check(node.phase == TaskPhase.PENDING) { "unique content not pending: $label" }
        val params = (node.response["resumable"] as JsonObject)["params"] as JsonObject
        val taskId = (node.response["task"] as? JsonObject)?.str("id")?.takeIf { it.isNotEmpty() }
        val hints = mutableListOf<String>()
        fun scan(value: JsonElement, path: String) {
            if (value is JsonObject) value.forEach { (k, v) ->
                if ((k.contains("callback", true) && v != JsonNull && v != JsonPrimitive("")) || v == JsonPrimitive("UPLOAD_TYPE_FORM")) hints += "$path.$k"
                scan(v, "$path.$k")
            }
        }
        scan(node.response, "response")
        println("[pending] HINTS label=$label callbackOrForm=$hints taskId=$taskId")
        check(hints.isEmpty()) { "callback/form discovered; implement supplied protocol before continuing" }
        val begin = now()
        var uploadId: String? = null
        var committed = false
        try {
            val result = if (mode == "put") oss(params, HttpMethod.Put, "", bytes) else {
                val init = oss(params, HttpMethod.Post, "uploads", null)
                check(init.status == 200) { "init status=${init.status}" }
                uploadId = Regex("<UploadId>(.*?)</UploadId>").find(init.body)!!.groupValues[1]
                val part = oss(params, HttpMethod.Put, "partNumber=1&uploadId=$uploadId", bytes)
                check(part.status == 200) { "part status=${part.status}" }
                val etag = requireNotNull(part.etag).trim('"')
                val xml = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>$etag</ETag></Part></CompleteMultipartUpload>"
                oss(params, HttpMethod.Post, "uploadId=$uploadId", xml.encodeToByteArray())
            }
            committed = result.status == 200
            val code = Regex("<Code>([^<]*)</Code>").find(result.body)?.groupValues?.get(1)
            println("[pending] OSS label=$label status=${result.status} errorCode=$code requests=${if (mode == "put") 1 else 3} wallMs=${result.at - begin} completeAtMs=${result.at} responseBytes=${result.body.length}")
            if (!committed) {
                println("[pending] SUMMARY label=$label denied=true status=${result.status}")
                return
            }
            observe(client, folder, node.fileId, taskId, label, result.at)
        } finally {
            if (uploadId != null && !committed) withContext(NonCancellable) {
                val abort = oss(params, HttpMethod.Delete, "uploadId=$uploadId", null)
                println("[pending] ABORT label=$label status=${abort.status}")
            }
        }
    }

    private suspend fun observe(client: PikPakClient, folder: String, fileId: String, taskId: String?, label: String, completedAt: Long) = coroutineScope {
        var listComplete: Long? = null
        var detailComplete: Long? = null
        var taskComplete: Long? = null
        var lastListPending: Long? = null
        var lastDetailPending: Long? = null
        var tick = 0
        while (true) {
            val requested = now() - completedAt
            val list = async { val v = client.listFiles(folder).firstOrNull { it.id == fileId }; Triple(v?.phase, now() - completedAt, v != null) }
            val detail = async { val v = client.getFile(fileId); v to (now() - completedAt) }
            val task = async { taskId?.let { val v = client.getTask(it); v to (now() - completedAt) } }
            val l = list.await()
            val (d, dt) = detail.await()
            val t = task.await()
            if (l.first == TaskPhase.COMPLETE && listComplete == null) listComplete = l.second
            if (d.phase == TaskPhase.COMPLETE && detailComplete == null) detailComplete = dt
            if (t?.first?.phase == TaskPhase.COMPLETE && taskComplete == null) taskComplete = t.second
            if (l.first == TaskPhase.PENDING) lastListPending = l.second
            if (d.phase == TaskPhase.PENDING) lastDetailPending = dt
            println("[pending] POLL label=$label tick=$tick requestMs=$requested listMs=${l.second} list=${l.first} detailMs=$dt detail=${d.phase} downloadUrl=${d.downloadUrl != null} medias=${d.medias.size} links=${d.links.size} taskMs=${t?.second} task=${t?.first?.phase} progress=${t?.first?.progress}")
            if ((listComplete != null && detailComplete != null && (taskId == null || taskComplete != null)) || now() - completedAt >= 90_000) break
            tick++
            delay((completedAt + tick * 1000L - now()).coerceAtLeast(0))
        }
        println("[pending] SUMMARY label=$label listCompleteMs=$listComplete detailCompleteMs=$detailComplete taskCompleteMs=$taskComplete lastListPendingMs=$lastListPending lastDetailPendingMs=$lastDetailPending observedMs=${now() - completedAt}")
    }

    private suspend fun oss(p: JsonObject, method: HttpMethod, query: String, body: ByteArray?): Reply {
        val date = Clock.System.now().formatHttpDate()
        val path = "/${p.str("key")}"
        val type = "application/octet-stream"
        val auth = OssSign.authorizationHeader(method, p.str("bucket"), path, query, type, date,
            headersOf("X-Oss-Security-Token", p.str("security_token")), p.str("access_key_id"), p.str("access_key_secret"))
        val response = web.request("https://${p.str("endpoint")}$path${if (query.isEmpty()) "" else "?$query"}") {
            this.method = method
            headers {
                set(HttpHeaders.Date, date)
                set(HttpHeaders.Authorization, auth)
                set("X-Oss-Security-Token", p.str("security_token"))
                set(HttpHeaders.UserAgent, "aliyun-sdk-android/2.9.5(Linux/Android 11/ONEPLUS%20A6000;RKQ1.201217.002)")
            }
            contentType(ContentType.parse(type))
            if (body != null) setBody(body)
        }
        val text = response.bodyAsText()
        return Reply(response.status.value, text, response.headers[HttpHeaders.ETag], now())
    }
}
