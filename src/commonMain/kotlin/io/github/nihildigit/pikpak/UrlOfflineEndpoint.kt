package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Snapshot of the offline-download task PikPak returns for a URL submission. */
@Serializable
data class OfflineTask(
    val id: String = "",
    val name: String = "",
    val phase: String = "",
    @SerialName("file_id") val fileId: String = "",
    @SerialName("type") val type: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("reference_resource") val referenceResource: String = "",
    val params: Params = Params(),
) {
    @Serializable
    data class Params(
        val url: String = "",
    )
}

/**
 * Submits a remote URL to PikPak's cloud-download (offline download) queue.
 * The returned [OfflineTask] reflects the task's initial phase — typically
 * `PHASE_TYPE_RUNNING`. Pass `""` for [parentId] to drop the result into the
 * root drive.
 */
suspend fun PikPakClient.createUrlFile(parentId: String, url: String): OfflineTask {
    val body = buildJsonObject {
        put("kind", FileKind.FILE)
        put("upload_type", "UPLOAD_TYPE_URL")
        if (parentId.isNotEmpty()) put("parent_id", parentId)
        putJsonObject("url") { put("url", url) }
    }
    val response = http.request(
        method = HttpMethod.Post,
        url = "${PikPakConstants.DRIVE_BASE}/drive/v1/files",
        captchaAction = "POST:/drive/v1/files",
    ) { jsonBody(json, body) }
    val taskNode = (response as JsonObject)["task"]?.jsonObject
        ?: return OfflineTask() // some responses omit task on instant completion
    return json.decodeFromJsonElement(OfflineTask.serializer(), taskNode)
}
