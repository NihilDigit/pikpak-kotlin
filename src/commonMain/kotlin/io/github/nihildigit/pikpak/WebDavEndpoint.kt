package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One WebDAV client of the account: a login a WebDAV application uses against [WebDav.ENDPOINT].
 *
 * The server generates [username] and [password]; [renewWebDavPassword] replaces the password.
 * The flags arrive as 0 or 1.
 */
@Serializable
data class WebDavClient(
    val id: Long = 0,
    @SerialName("application_name") val name: String = "",
    val username: String = "",
    val password: String = "",
    /** A directory listing stops at this many entries. Large values make listings time out. */
    @SerialName("max_entries_in_response") val maxEntriesInResponse: Int = 0,
    @SerialName("optimized_for_media_play") private val mediaPlayFlag: Int = 0,
    @SerialName("read_only") private val readOnlyFlag: Int = 0,
    @SerialName("enable") private val enabledFlag: Int = 0,
    /** ISO-8601 with offset. */
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_active_at") val lastActiveAt: String = "",
    @SerialName("last_active_ip") val lastActiveIp: String = "",
) {
    val optimizedForMediaPlay: Boolean get() = mediaPlayFlag == 1
    val readOnly: Boolean get() = readOnlyFlag == 1
    val enabled: Boolean get() = enabledFlag == 1
}

/** Response of [getWebDav]. */
@Serializable
data class WebDavSettings(
    /** The account-wide switch; clients connect only while it is on. */
    @SerialName("webdav_enable") val enabled: Boolean = false,
    /** WebDAV is a premium feature: a free account cannot turn it on. */
    @SerialName("is_premium") val isPremium: Boolean = false,
    @SerialName("applications") val clients: List<WebDavClient> = emptyList(),
)

/**
 * Where WebDAV applications connect, with a [WebDavClient]'s credentials over Basic auth. Both
 * hosts answer on HTTP port 80 and HTTPS port 443. Read-only: PikPak rejects PUT.
 *
 * Reading a file is a 302 to a signed CDN link, the same kind [getFile] returns, not the bytes
 * themselves (measured 2026-10-05, with [WebDavClient.optimizedForMediaPlay] on).
 */
object WebDav {
    /** What PikPak's settings page shows first. */
    const val ENDPOINT = "https://dav.pikpak.ai"

    /** The settings page's alternative. */
    const val ALTERNATIVE_ENDPOINT = "https://dav.mypikpak.com"
}

/** The account's WebDAV switch and clients (`GET /webdav/v1/applications`). */
suspend fun PikPakClient.getWebDav(): WebDavSettings {
    val response = http.request(
        method = HttpMethod.Get,
        url = "$DAV/applications",
        captchaAction = "GET:/webdav/v1/applications",
    )
    return json.decodeFromJsonElement(WebDavSettings.serializer(), response)
}

/** Turns WebDAV on or off for the whole account (`POST /webdav/v1/toggle-enable`). Fails on a free account. */
suspend fun PikPakClient.setWebDavEnabled(enabled: Boolean) {
    val response = http.request(
        method = HttpMethod.Post,
        url = "$DAV/toggle-enable",
        captchaAction = "POST:/webdav/v1/toggle-enable",
    ) { jsonBody(json, buildJsonObject { put("enable", enabled) }) }
    requireSuccess(response, "toggle-enable")
}

/** Adds a client named [name]; the server generates its login (`POST /webdav/v1/application`). */
suspend fun PikPakClient.createWebDavClient(name: String): WebDavClient {
    require(name.isNotEmpty()) { "name must not be empty" }
    val response = http.request(
        method = HttpMethod.Post,
        url = "$DAV/application",
        captchaAction = "POST:/webdav/v1/application",
    ) { jsonBody(json, buildJsonObject { put("application_name", name) }) }
    return json.decodeFromJsonElement(WebDavClient.serializer(), response)
}

/**
 * Changes what is given and leaves the rest (`PATCH /webdav/v1/application`). The client is named
 * by its current login, which is why [client] is taken whole.
 */
suspend fun PikPakClient.updateWebDavClient(
    client: WebDavClient,
    name: String? = null,
    maxEntriesInResponse: Int? = null,
    optimizedForMediaPlay: Boolean? = null,
    readOnly: Boolean? = null,
) {
    val changes = buildJsonObject {
        name?.let { put("application_name", it) }
        maxEntriesInResponse?.let { put("max_entries_in_response", it) }
        optimizedForMediaPlay?.let { put("optimized_for_media_play", it) }
        readOnly?.let { put("read_only", it) }
    }
    if (changes.isEmpty()) return
    val response = http.request(
        method = HttpMethod.Patch,
        url = "$DAV/application",
        captchaAction = "PATCH:/webdav/v1/application",
    ) { jsonBody(json, client.login { put("modify_props", changes) }) }
    requireSuccess(response, "update")
}

/** Removes [client]; applications signed in with it stop connecting (`DELETE /webdav/v1/application`). */
suspend fun PikPakClient.deleteWebDavClient(client: WebDavClient) {
    val response = http.request(
        method = HttpMethod.Delete,
        url = "$DAV/application",
        captchaAction = "DELETE:/webdav/v1/application",
    ) { jsonBody(json, client.login {}) }
    requireSuccess(response, "delete")
}

/**
 * Replaces [client]'s password and returns it with the new one (`POST /webdav/v1/renew-password`).
 * Applications using the old password are refused from their next connection.
 */
suspend fun PikPakClient.renewWebDavPassword(client: WebDavClient): WebDavClient {
    val response = http.request(
        method = HttpMethod.Post,
        url = "$DAV/renew-password",
        captchaAction = "POST:/webdav/v1/renew-password",
    ) { jsonBody(json, client.login {}) }
    val password = ((response as? JsonObject)?.get("password") as? JsonPrimitive)?.contentOrNull
    if (password.isNullOrEmpty()) throw PikPakException(-1, "renew-password returned no password", rawBody = response.toString())
    return client.copy(password = password)
}

private val DAV = "${PikPakConstants.DAV_BASE}/webdav/v1"

private fun WebDavClient.login(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("username", username)
    put("password", password)
    extra()
}

// These answer 200 with {"success": false} rather than an error status when they decline
private fun requireSuccess(response: JsonElement, action: String) {
    val success = ((response as? JsonObject)?.get("success") as? JsonPrimitive)?.booleanOrNull
    if (success != true) throw PikPakException(-1, "WebDAV $action was not accepted", rawBody = response.toString())
}
