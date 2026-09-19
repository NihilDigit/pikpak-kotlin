package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserProfileMockTest {

    @Test
    fun `user me maps onto the profile including the avatar under its wire name`() = runBlocking {
        val client = clientWithAuth { req ->
            if (req.url.encodedPath == "/v1/user/me") {
                respondJson(
                    """{
                      "sub":"UID42",
                      "name":"nickname",
                      "picture":"https://cdn/avatar.png",
                      "email":"a***@example.com",
                      "phone_number":"+86 138****0000",
                      "password":"SET",
                      "status":"ACTIVE",
                      "created_at":"2021-05-01T00:00:00.000Z",
                      "password_updated_at":"2024-01-02T03:04:05.000Z",
                      "providers":[{"id":"google","provider_user_id":"G1","name":"Google"}],
                      "some_field_added_next_year":{"nested":true}
                    }""",
                )
            } else respond404()
        }

        val profile = client.getUserProfile()

        assertEquals("UID42", profile.sub)
        assertEquals("nickname", profile.name)
        // The field is `picture`. An `avatar` mapping would decode to null here
        // and the downstream UI would show a placeholder with no error.
        assertEquals("https://cdn/avatar.png", profile.avatarUrl)
        assertEquals("a***@example.com", profile.email)
        assertEquals("+86 138****0000", profile.phoneNumber)
        assertTrue(profile.hasPassword)
        assertEquals("Google", profile.providers.single().name)
        assertEquals("G1", profile.providers.single().providerUserId)
        client.close()
    }

    @Test
    fun `an OAuth-only account reports no password and no avatar`() = runBlocking {
        val client = clientWithAuth { req ->
            if (req.url.encodedPath == "/v1/user/me") {
                respondJson("""{"sub":"UID","status":"ACTIVE","providers":[]}""")
            } else respond404()
        }

        val profile = client.getUserProfile()

        assertFalse(profile.hasPassword)
        assertNull(profile.avatarUrl)
        client.close()
    }

    @Test
    fun `the captcha action for user me is the full URL and not the bare path`() = runBlocking {
        // Actions on the user host are signed with the whole URL — the signin
        // flow does it that way. A bare path here is accepted by captcha/init
        // and then rejected by user/me forever, which reads as an auth bug.
        var action: String? = null
        var served = 0
        val client = clientWithAuth(
            onCaptchaInit = { body -> action = Regex(""""action":"([^"]*)"""").find(body)?.groupValues?.get(1) },
        ) { req ->
            if (req.url.encodedPath == "/v1/user/me") {
                served++
                if (served == 1) respondJson("""{"error_code":9,"error":"captcha_invalid"}""")
                else respondJson("""{"sub":"UID"}""")
            } else respond404()
        }

        assertEquals("UID", client.getUserProfile().sub)
        assertEquals("GET:https://user.mypikpak.com/v1/user/me", action)
        client.close()
    }

    @Test
    fun `vip is live only when the lookup was accepted and the membership is ok`() = runBlocking {
        val client = clientWithAuth { req ->
            if (req.url.encodedPath == "/drive/v1/privilege/vip") {
                respondJson(
                    """{"result":"ACCEPTED","message":"success",
                       "data":{"expire":"2027-03-01T00:00:00.000Z","status":"ok",
                               "type":"platinum","user_id":"UID"}}""",
                )
            } else respond404()
        }

        val vip = client.getVipInfo()

        assertTrue(vip.isVip)
        assertEquals("platinum", vip.data?.type)
        assertEquals("2027-03-01T00:00:00Z", vip.expiresAt?.toString())
        client.close()
    }

    @Test
    fun `a rejected vip lookup is not a membership even when it carries data`() = runBlocking {
        // The envelope is not PikPak's usual one: this arrives as a 2xx with
        // error_code absent, so nothing below this model can reject it.
        val client = clientWithAuth { req ->
            if (req.url.encodedPath == "/drive/v1/privilege/vip") {
                respondJson("""{"result":"REJECTED","data":{"status":"ok","type":"platinum"}}""")
            } else respond404()
        }

        assertFalse(client.getVipInfo().isVip)
        client.close()
    }

    private fun clientWithAuth(
        onCaptchaInit: (String) -> Unit = {},
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PikPakClient {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/v1/shield/captcha/init") -> {
                    onCaptchaInit(req.body.asText())
                    respondJson("""{"captcha_token":"CAP"}""")
                }
                path.endsWith("/v1/auth/signin") -> respondJson(
                    """{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""",
                )
                else -> handler(req)
            }
        }
        return PikPakClient(
            account = "mock@x",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter.unlimited(),
            httpClient = HttpClient(engine),
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun MockRequestHandleScope.respond404() = respond(
        content = "",
        status = HttpStatusCode.NotFound,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun io.ktor.http.content.OutgoingContent.asText(): String = when (this) {
        is io.ktor.http.content.TextContent -> text
        is io.ktor.http.content.ByteArrayContent -> bytes().decodeToString()
        else -> ""
    }
}
