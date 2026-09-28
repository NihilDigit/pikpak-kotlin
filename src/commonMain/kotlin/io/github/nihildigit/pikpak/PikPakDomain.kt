package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.HttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The root domain the client reaches PikPak's API under; see [PikPakClient.domain].
 *
 * These four are the roots PikPak's own web client knows its API by (its bundle lists exactly
 * them). Measured 2026-09-28: `api-drive` and `user` under each resolve to different addresses
 * within one provider's range (edge hosts resolve to identical addresses under every root),
 * present a valid certificate for that root, and serve the same account on the same tokens —
 * quota, profile and file details came back identical, and a refresh-token exchange and a
 * captcha refresh both succeeded under `mypikpak.net`.
 *
 * What moves with the root and what does not:
 *  - `api-drive.*` and `user.*`, every API call, sign-in, token refresh and captcha included.
 *  - Download links follow on their own: a detail fetched under a root hands out links under
 *    that root (`dl-z01a-0047.mypikpak.net`), resolving to the same edge addresses as the
 *    `mypikpak.com` name. The SDK never rewrites a link's root.
 *  - The captcha actions keep naming the official host (`POST:https://user.mypikpak.com/...`).
 *    They are strings the server signs, not addresses. The only action that names a host is
 *    sign-in's, and whether sign-in under another root wants its own root there is unverified.
 *  - Upload endpoints and share links come from the server and are used as given; share-link
 *    parsing recognises `mypikpak.com` only.
 *
 * Not verified on another root: password sign-in, which the measurements could not exercise
 * without signing the account out elsewhere.
 *
 * What differs between roots is the name, in DNS, the TLS SNI and the Host header, and for the
 * API hosts the address, which is what a network filtering or shaping by name or address sees.
 * No speed difference was measured,
 * direct or through a proxy, outside the evening peak. Choosing is left to the caller;
 * [probeDomain] gives it the figures.
 *
 * Not used, on any root: `access.<root>/access_controller/v1/area_accessible`, the region check.
 * PikPak's web client asks it on every route change, for all four roots at once, and shows its
 * "not available in your region" page unless one of them says accessible — or all of them fail,
 * or none answers in five seconds, in which case it lets the user in. It is a gate in the
 * client, not in the API: measured 2026-09-28 from an address it reports as mainland China,
 * the drive and user APIs, file details, captcha init and ranged CDN reads all answered
 * normally. A client that does not ask is not gated, so the SDK does not ask.
 */
enum class PikPakDomain(val root: String) {
    MYPIKPAK_COM("mypikpak.com"),
    MYPIKPAK_NET("mypikpak.net"),
    PIKPAK_ME("pikpak.me"),
    PIKPAKDRIVE_COM("pikpakdrive.com"),
    ;

    /** [url] moved under this root when it names an official API host; anything else unchanged. */
    internal fun apiUrl(url: String): String {
        if (this == MYPIKPAK_COM) return url
        for (sub in API_SUBDOMAINS) {
            val official = "https://$sub.${MYPIKPAK_COM.root}/"
            if (url.startsWith(official)) return "https://$sub.$root/" + url.removePrefix(official)
        }
        return url
    }

    private companion object {
        val API_SUBDOMAINS = listOf("api-drive", "user")
    }
}

/** What [probeDomain] found for one root. */
data class DomainProbe(
    val domain: PikPakDomain,
    /** Both API hosts answered as PikPak's gateway does; see [probeDomain]. */
    val usable: Boolean,
    /**
     * The first request on a new connection: name lookup, TCP, TLS and one exchange. Null if it
     * failed. The platform may resume a TLS session it holds for that host, which makes a root
     * already in use look cheaper than one never contacted; compare [warmRequest] for that.
     */
    val firstRequest: Duration?,
    /** The same request again on the open connection: one exchange, which is what every later call pays. */
    val warmRequest: Duration?,
    /** Why the root is not [usable], for a log line. */
    val failure: String? = null,
)

/**
 * Measures [domain] without switching to it: one request that opens a connection to its
 * `api-drive` host, the same request again on that connection, then one to its `user` host.
 *
 * The requests carry no token, so they cost no rate-limit token, need no captcha and cannot
 * disturb the session. What they check is that the name resolves, the certificate covers it and
 * PikPak's own gateway is behind it: both hosts refuse an unauthenticated call with HTTP 401 and
 * `error_code` 16, on every root. A captive portal, a filtering box or another service behind a
 * valid wildcard certificate answers something else. That the same token works on every root
 * was measured separately; see [PikPakDomain].
 *
 * Nothing is cached and nothing runs in the background: each call makes its requests now.
 * When this client built its own HTTP client, the probe opens a fresh one and closes it, so
 * [DomainProbe.firstRequest] really includes the handshake; with an injected client it uses
 * that one, whose pool may already hold a connection.
 */
suspend fun PikPakClient.probeDomain(domain: PikPakDomain, timeout: Duration = 10.seconds): DomainProbe {
    val client = if (ownsHttpClient) HttpEngine.defaultClient() else apiClient
    try {
        val drive = domain.apiUrl("${PikPakConstants.DRIVE_BASE}/drive/v1/about")
        val first = client.gatewayCheck(drive, timeout)
        if (first.failure != null) return DomainProbe(domain, false, null, null, first.failure)
        val warm = client.gatewayCheck(drive, timeout)
        if (warm.failure != null) return DomainProbe(domain, false, first.elapsed, null, warm.failure)
        // Token refresh and sign-in go to this host; a root whose drive host works and user host does not is no use
        val user = client.gatewayCheck(domain.apiUrl("${PikPakConstants.USER_BASE}/v1/user/me"), timeout)
        return DomainProbe(domain, user.failure == null, first.elapsed, warm.elapsed, user.failure)
    } finally {
        if (client !== apiClient) client.close()
    }
}

private class GatewayCheck(val elapsed: Duration, val failure: String?)

private suspend fun HttpClient.gatewayCheck(url: String, timeout: Duration): GatewayCheck {
    val started = TimeSource.Monotonic.markNow()
    val host = url.substringAfter("://").substringBefore('/')
    val failure = try {
        withTimeout(timeout) {
            val response = get(url) {
                expectSuccess = false
                header(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
            }
            val code = runCatching {
                Json.parseToJsonElement(response.bodyAsText()).jsonObject["error_code"]?.jsonPrimitive?.intOrNull
            }.getOrNull()
            if (response.status.value == 401 && code == UNAUTHENTICATED) null
            else "$host answered HTTP ${response.status.value}, error_code $code"
        }
    } catch (e: TimeoutCancellationException) {
        "$host did not answer within $timeout"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A refused handshake, a certificate for another name, an unresolvable host: each is the finding
        "$host: ${e::class.simpleName} ${e.message.orEmpty()}"
    }
    return GatewayCheck(started.elapsedNow(), failure)
}

/** What both API hosts answer a request without a token, on every root. */
private const val UNAUTHENTICATED = 16
