package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An upload whose completion reached OSS while the answer did not. OSS then no longer knows the
 * upload id, and the drive file is complete; the SDK used to take the 404 for a failure and delete
 * the finished file on the way out.
 */
class UploadCompletionMockTest {
    private val requests = mutableListOf<String>()

    private val session = UploadSession(
        fileId = "F1",
        size = 10,
        partSize = 256 * 1024,
        uploadId = "U1",
        bucket = "bucket",
        endpoint = "oss.example",
        key = "key",
        accessKeyId = "AK",
        accessKeySecret = "SK",
        securityToken = "ST",
        expiration = "",
    )

    private fun client() = PikPakClient(
        account = "mock@x",
        password = "pw",
        sessionStore = InMemorySessionStore().also { runBlocking { it.save("mock@x", Session("AT", "RT", "UID", 9_999_999_999L)) } },
        httpClient = HttpClient(
            MockEngine { req ->
                requests += "${req.method.value} ${req.url.host}${req.url.encodedPath}"
                when {
                    // OSS has forgotten the upload: it completed
                    req.url.host == "oss.example" -> respond(ByteReadChannel("<Error><Code>NoSuchUpload</Code></Error>"), HttpStatusCode.NotFound)
                    req.method.value == "GET" && req.url.encodedPath == "/drive/v1/files/F1" ->
                        json("""{"id":"F1","kind":"drive#file","name":"a.bin","size":"10","phase":"PHASE_TYPE_COMPLETE"}""")
                    else -> json("{}")
                }
            },
        ),
    )

    private fun MockRequestHandleScope.json(body: String) =
        respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun `continuing an upload OSS no longer knows succeeds when the file is complete`() = runBlocking {
        val client = client()
        client.continueUpload(session, open = { Buffer() })
        client.close()
    }

    @Test
    fun `cancelling a completed upload leaves the file alone`() = runBlocking {
        val client = client()
        client.cancelUpload(session)
        assertEquals(0, requests.count { it.startsWith("DELETE ") && it.endsWith("/drive/v1/files/F1") }, "the finished file was deleted: $requests")
        client.close()
    }
}
