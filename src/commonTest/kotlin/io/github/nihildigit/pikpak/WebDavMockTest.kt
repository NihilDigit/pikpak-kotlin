package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebDavMockTest {
    private class Sent(val method: String, val url: String, val body: JsonObject?)

    private val sent = mutableListOf<Sent>()

    private fun clientAnswering(domain: PikPakDomain = PikPakDomain.MYPIKPAK_COM, answer: (String) -> String): PikPakClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/v1/shield/captcha/init") -> """{"captcha_token":"C","expires_in":300}"""
                path.endsWith("/v1/auth/signin") -> """{"access_token":"AT","refresh_token":"RT","sub":"U","expires_in":3600}"""
                else -> {
                    val text = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
                    sent += Sent(request.method.value, request.url.toString(), text?.let { Json.parseToJsonElement(it) as JsonObject })
                    answer(path)
                }
            }
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return PikPakClient(
            account = "mock@example.com", password = "pw",
            sessionStore = InMemorySessionStore(), httpClient = HttpClient(engine), domain = domain,
        )
    }

    private val existing = WebDavClient(id = 7, name = "Client-1", username = "abcd", password = "secret12")

    // The web client changes one setting at a time; a field sent as null or false would reset
    // what the caller meant to leave alone
    @Test
    fun `an update sends the login and only the settings given`() = runBlocking {
        val client = clientAnswering { """{"success":true}""" }
        client.updateWebDavClient(existing, readOnly = false)

        val body = sent.single().body!!
        assertEquals("PATCH", sent.single().method)
        assertEquals("abcd", body["username"].toString().trim('"'))
        assertEquals("secret12", body["password"].toString().trim('"'))
        assertEquals("""{"read_only":false}""", body["modify_props"].toString())
        client.close()
    }

    // Declines come back as 200 with success false; taken at face value a free account would
    // believe it had just turned WebDAV on
    @Test
    fun `a declined change throws instead of passing for done`() = runBlocking {
        val client = clientAnswering { """{"success":false}""" }
        assertFailsWith<PikPakException> { client.setWebDavEnabled(true) }
        assertFailsWith<PikPakException> { client.deleteWebDavClient(existing) }
        client.close()
    }

    @Test
    fun `settings read the flags sent as 0 and 1`() = runBlocking {
        val client = clientAnswering {
            """{"webdav_enable":true,"is_premium":true,"applications":[{"id":7,"application_name":"Client-1",
               "username":"abcd","password":"secret12","max_entries_in_response":10000,
               "optimized_for_media_play":1,"read_only":0,"enable":1}]}"""
        }
        val settings = client.getWebDav()
        val only = settings.clients.single()
        assertTrue(settings.enabled && only.optimizedForMediaPlay && only.enabled)
        assertEquals(false, only.readOnly)
        assertEquals(10000, only.maxEntriesInResponse)
        client.close()
    }

    @Test
    fun `the settings follow the client to another root domain`() = runBlocking {
        val client = clientAnswering(PikPakDomain.MYPIKPAK_NET) { """{"password":"fresh678"}""" }
        val renewed = client.renewWebDavPassword(existing)
        assertEquals("fresh678", renewed.password)
        assertEquals("https://api-dav.mypikpak.net/webdav/v1/renew-password", sent.single().url)
        client.close()
    }
}
