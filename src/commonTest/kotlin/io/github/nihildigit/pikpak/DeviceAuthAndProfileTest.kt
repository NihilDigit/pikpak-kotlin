package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.readText
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeviceAuthAndProfileTest {

    @Test
    fun `startDeviceAuthorization builds official double-encoded Google jump URL even on mirror domain`() = runTest {
        var capturedDeviceId: String? = null
        var capturedClientIdHeader: String? = null
        var capturedHost: String? = null
        var capturedBody = ""

        val engine = MockEngine { req ->
            capturedHost = req.url.host
            capturedDeviceId = req.headers["X-Device-Id"]
            capturedClientIdHeader = req.headers["X-Client-Id"]
            capturedBody = req.bodyText()
            respondJson(
                """{"device_code":"SECRET-DC-123","user_code":"ABCD-EFGH","verification_url":"https://dead.invalid","expires_in":300,"interval":2}""",
            )
        }
        val client = PikPakClient(
            account = "pikpak:bootstrap",
            sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(engine),
            domain = PikPakDomain.MYPIKPAK_NET,
        )
        try {
            val auth = client.startDeviceAuthorization(platformName = "Piko")
            // API request routes through mirror domain, while browser URLs stay on mypikpak.com for Google OAuth whitelist.
            assertEquals("user.mypikpak.net", capturedHost)
            assertEquals(auth.deviceId, capturedDeviceId)
            assertEquals(32, auth.deviceId.length)
            assertEquals(AuthClientProfile.AndroidDefault.clientId, capturedClientIdHeader)
            assertTrue(""""client_id":"YNxT9w7GMdWvEOKa"""" in capturedBody)
            assertTrue(""""scope":"user pan offline"""" in capturedBody)

            val expectedQr =
                "https://mypikpak.com/drive/qr-login?client_id=YNxT9w7GMdWvEOKa&scope=user%20pan%20offline&user_code=ABCD-EFGH&platform=Piko"
            assertEquals(expectedQr, auth.qrLoginUrl)
            assertTrue(auth.googleAuthUrl.startsWith("https://mypikpak.com/auth?type=google&init=1&origin="))
            // Double-encoded `:` (%253A) and `%20` (%252520)
            assertTrue("%253A%252F%252Fmypikpak.com%252Fdrive%252Fqr-login" in auth.googleAuthUrl)
            assertTrue("scope%253Duser%252520pan%252520offline" in auth.googleAuthUrl)
            assertFalse("SECRET-DC-123" in auth.toString(), "deviceCode must be redacted from toString()")
        } finally {
            client.close()
        }
    }

    @Test
    fun `awaitDeviceAuthorization handles pending slow_down transient network drop and JWT sub fallback`() = runTest {
        val jwtPayload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode("""{"sub":"user-sub-999","aud":"YNxT9w7GMdWvEOKa"}""".encodeToByteArray())
        val fakeJwt = "eyJhbGciOiJSUzI1NiJ9.$jwtPayload.sig"

        var pollAttempt = 0
        val pollBodies = mutableListOf<String>()
        val pollDeviceIds = mutableListOf<String?>()

        val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/v1/auth/device/code") ->
                    respondJson("""{"device_code":"DC-1","user_code":"UC-1","expires_in":120}""")
                req.url.encodedPath.endsWith("/v1/auth/token") -> {
                    pollBodies += req.bodyText()
                    pollDeviceIds += req.headers["X-Device-Id"]
                    when (++pollAttempt) {
                        1 -> respondJson(
                            """{"error":"authorization_pending","error_code":4050}""",
                            HttpStatusCode.BadRequest,
                        )
                        2 -> respondJson(
                            """{"error":"slow_down","error_code":4051}""",
                            HttpStatusCode.BadRequest,
                        )
                        3 -> throw IOException("simulated wi-fi blip during polling")
                        else -> respondJson(
                            // Intentionally omit top-level `sub` to verify JWT payload fallback
                            """{"access_token":"$fakeJwt","refresh_token":"RT-GOOGLE-1","expires_in":7200,"scope":"user pan offline"}""",
                        )
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val store = InMemorySessionStore()
        val client = PikPakClient(
            account = "pikpak:user-sub-999",
            sessionStore = store,
            httpClient = HttpClient(engine),
        )
        try {
            val auth = client.startDeviceAuthorization()
            // Missing `interval` defaults to RFC 8628's 5 seconds
            assertEquals(5L, auth.intervalSeconds)

            val observedPolls = mutableListOf<DevicePollResult>()
            val session = client.awaitDeviceAuthorization(auth, commit = true) { observedPolls += it }

            assertEquals(4, pollAttempt)
            assertTrue(pollBodies.all { "client_secret" !in it }, "RFC 8628 device poll must not send client_secret")
            assertTrue(pollDeviceIds.all { it == auth.deviceId }, "every poll must keep the deviceId minted at /device/code")
            assertEquals("user-sub-999", session.sub)
            assertEquals("RT-GOOGLE-1", session.refreshToken)
            assertEquals(auth.deviceId, session.deviceId)
            assertEquals(AuthClientProfile.ANDROID_DEFAULT_ID, session.profileId)
            assertEquals("user pan offline", session.scope)
            assertEquals(3, observedPolls.size)
            assertTrue(observedPolls[0] is DevicePollResult.Pending)
            assertEquals(10L, (observedPolls[1] as DevicePollResult.SlowDown).recommendedIntervalSeconds)
            assertTrue(observedPolls[2] is DevicePollResult.Authorized)

            // Reject duplicate poller on the same transaction
            val dup = assertFailsWith<DeviceAuthorizationException> {
                client.awaitDeviceAuthorization(auth)
            }
            assertEquals(DeviceAuthorizationFailureReason.ALREADY_POLLING, dup.reason)
        } finally {
            client.close()
        }
    }

    @Test
    fun `passwordless session preserves deviceId across cold restart survives 503 on refresh and skips captcha on 4126`() = runTest {
        val store = InMemorySessionStore()
        val persistedDeviceId = "0123456789abcdef0123456789abcdef"
        store.save(
            "pikpak:u1",
            Session(
                accessToken = "EXPIRED-AT",
                refreshToken = "RT-1",
                sub = "u1",
                expiresAt = 1L,
                profileId = AuthClientProfile.ANDROID_DEFAULT_ID,
                deviceId = persistedDeviceId,
                scope = "user pan offline",
            ),
        )

        var mode = "503"
        val calls = mutableListOf<String>()
        val seenDeviceIds = mutableListOf<String?>()
        val notifiedReasons = mutableListOf<InteractiveLoginReason>()

        val engine = MockEngine { req ->
            calls += req.url.encodedPath
            seenDeviceIds += req.headers["X-Device-Id"]
            when (mode) {
                "503" -> respondJson("""{"error_code":500,"error":"unavailable"}""", HttpStatusCode.ServiceUnavailable)
                "ok" -> respondJson("""{"access_token":"FRESH-AT","expires_in":7200}""")
                "4126" -> respondJson("""{"error_code":4126,"error":"refresh_token_invalid"}""", HttpStatusCode.BadRequest)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val client = PikPakClient(
            account = "pikpak:u1",
            passwordSupplier = null,
            sessionStore = store,
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelay = 1.milliseconds, maxDelay = 2.milliseconds),
            httpClient = HttpClient(engine),
        ).also {
            it.onInteractiveLoginRequired = { err -> notifiedReasons += err.reason }
        }
        try {
            // 1. Server 503 on refresh must NOT wipe stored session or throw InteractiveLoginRequiredException
            val serverErr = assertFailsWith<PikPakException> { client.login() }
            assertEquals(503, serverErr.httpStatus)
            assertNotNull(store.load("pikpak:u1"), "transient 503 must preserve persisted session")
            assertEquals(0, calls.count { it.endsWith("/v1/shield/captcha/init") })
            assertTrue(notifiedReasons.isEmpty())

            // 2. Cold recovery succeeds, sends persisted deviceId, and preserves omitted refresh_token/sub/scope
            mode = "ok"
            val refreshed = client.login()
            assertEquals("FRESH-AT", refreshed.accessToken)
            assertEquals("RT-1", refreshed.refreshToken)
            assertEquals("u1", refreshed.sub)
            assertEquals(persistedDeviceId, refreshed.deviceId)
            assertEquals("user pan offline", refreshed.scope)
            assertTrue(seenDeviceIds.all { it == persistedDeviceId })

            // 3. Definitive 4126 clears dead session and throws InteractiveLoginRequiredException with zero captcha calls
            store.save("pikpak:u1", refreshed.copy(expiresAt = 1L))
            client.state.session = null
            mode = "4126"
            calls.clear()
            val interactive = assertFailsWith<InteractiveLoginRequiredException> { client.login() }
            assertEquals(InteractiveLoginReason.REFRESH_EXPIRED, interactive.reason)
            assertEquals(listOf(InteractiveLoginReason.REFRESH_EXPIRED), notifiedReasons)
            assertNull(store.load("pikpak:u1"), "definitive 4126 must clear dead session")
            assertEquals(0, calls.count { it.endsWith("/v1/shield/captcha/init") }, "passwordless client must never call signin captcha")
        } finally {
            client.close()
        }
    }

    @Test
    fun `legacy 4-field Session JSON decodes cleanly unknown profile fails closed and custom authProfile persists deviceId`() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString(
            Session.serializer(),
            """{"access_token":"A","refresh_token":"R","sub":"S","expires_at":9999999999}""",
        )
        assertNull(legacy.profileId)
        assertNull(legacy.deviceId)

        val store = InMemorySessionStore()
        store.save("pikpak:S", legacy.copy(profileId = "unregistered-profile"))
        val badProfileClient = PikPakClient(
            account = "pikpak:S",
            sessionStore = store,
            httpClient = HttpClient(MockEngine { respondJson("{}") }),
        )
        try {
            val err = assertFailsWith<PikPakException> { badProfileClient.login() }
            assertEquals("unknown_auth_profile", err.errorMessage)
        } finally {
            badProfileClient.close()
        }

        // Custom authProfile + password sign-in on deduplicated slot sends custom client_id, loginUsername, and records default deviceId
        val customProfile = AuthClientProfile(
            id = "custom-desktop",
            clientId = "CUSTOM-CID",
            clientSecret = "CUSTOM-SEC",
            userAgent = "CustomUA/1.0",
            packageName = "custom.pkg",
            clientVersion = "9.9.9",
            captchaSalts = listOf("salt1"),
        )
        var signinUsername = ""
        var signinClientId = ""
        var captchaUsername = ""
        val pwStore = InMemorySessionStore()
        val pwClient = PikPakClient(
            account = "pikpak:S",
            password = "secret-password",
            loginUsername = "real.user@example.com",
            authProfile = customProfile,
            sessionStore = pwStore,
            httpClient = HttpClient(
                MockEngine { req ->
                    val parsed = Json.parseToJsonElement(req.bodyText()).jsonObject
                    when {
                        req.url.encodedPath.endsWith("/v1/shield/captcha/init") -> {
                            captchaUsername = parsed["meta"]!!.jsonObject["username"]!!.jsonPrimitive.content
                            respondJson("""{"captcha_token":"CAP"}""")
                        }
                        req.url.encodedPath.endsWith("/v1/auth/signin") -> {
                            signinUsername = parsed["username"]!!.jsonPrimitive.content
                            signinClientId = parsed["client_id"]!!.jsonPrimitive.content
                            respondJson("""{"access_token":"AT","refresh_token":"RT","sub":"S","expires_in":3600}""")
                        }
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                },
            ),
        )
        try {
            val minted = pwClient.login()
            assertEquals("real.user@example.com", captchaUsername)
            assertEquals("real.user@example.com", signinUsername)
            assertEquals("CUSTOM-CID", signinClientId)
            assertEquals("custom-desktop", minted.profileId)
            assertEquals(pwClient.deviceId, minted.deviceId, "default deviceId must be persisted in minted Session")
            assertNotNull(pwStore.load("pikpak:S"), "session is persisted under storage key")
        } finally {
            pwClient.close()
        }
    }

    @Test
    fun `cumulative slow_down is never shrunk by transient errors and single-step poll accumulates interval`() = runTest {
        var step = 0
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/v1/auth/device/code") ->
                    respondJson("""{"device_code":"DC-SD","user_code":"UC-SD","expires_in":180,"interval":5}""")
                req.url.encodedPath.endsWith("/v1/auth/token") -> when (++step) {
                    1, 2, 3 -> respondJson("""{"error":"slow_down","error_code":4051}""", HttpStatusCode.BadRequest)
                    4 -> throw IOException("transient blip after reaching 20s interval")
                    else -> respondJson("""{"access_token":"AT-SD","refresh_token":"RT-SD","sub":"U-SD","expires_in":7200}""")
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = PikPakClient(
            account = "bootstrap",
            sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(engine),
        )
        try {
            val auth = client.startDeviceAuthorization()
            // Direct single-step polls accumulate 5 -> 10 -> 15 -> 20
            val p1 = client.pollDeviceAuthorization(auth, commit = false) as DevicePollResult.SlowDown
            val p2 = client.pollDeviceAuthorization(auth, commit = false) as DevicePollResult.SlowDown
            val p3 = client.pollDeviceAuthorization(auth, commit = false) as DevicePollResult.SlowDown
            assertEquals(10L, p1.recommendedIntervalSeconds)
            assertEquals(15L, p2.recommendedIntervalSeconds)
            assertEquals(20L, p3.recommendedIntervalSeconds)
            assertEquals(20L, auth.currentIntervalSeconds)

            val beforeAwaitMs = testScheduler.currentTime
            val session = client.awaitDeviceAuthorization(auth, commit = false)
            val elapsedAwaitMs = testScheduler.currentTime - beforeAwaitMs
            assertEquals("AT-SD", session.accessToken)
            // Step 4 waited 20s -> IOException -> Step 5 MUST wait >= 20s (never clamped down to 15s!) -> >= 40_000 ms total
            assertTrue(elapsedAwaitMs >= 40_000L, "transient error must not shrink cumulative slow_down interval ($elapsedAwaitMs ms)")
        } finally {
            client.close()
        }
    }

    private suspend fun HttpRequestData.bodyText(): String = when (val b = body) {
        is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> b.readFrom().readRemaining().readText()
        else -> b.toString()
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
