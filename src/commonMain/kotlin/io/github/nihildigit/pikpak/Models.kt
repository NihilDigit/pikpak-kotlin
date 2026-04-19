package io.github.nihildigit.pikpak

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lightweight file/folder summary returned by listing endpoints.
 * Mirrors the most useful fields from /drive/v1/files; unknown fields are ignored
 * by the JSON decoder so future PikPak schema additions don't break the client.
 */
@Serializable
data class FileStat(
    val kind: String = "",
    val id: String = "",
    @SerialName("parent_id") val parentId: String = "",
    val name: String = "",
    @SerialName("user_id") val userId: String = "",
    val size: String = "0",
    @SerialName("file_extension") val fileExtension: String = "",
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("created_time") val createdTime: String = "",
    @SerialName("modified_time") val modifiedTime: String = "",
    @SerialName("icon_link") val iconLink: String = "",
    @SerialName("thumbnail_link") val thumbnailLink: String = "",
    @SerialName("md5_checksum") val md5Checksum: String = "",
    val hash: String = "",
    val phase: String = "",
    val trashed: Boolean = false,
) {
    val isFolder: Boolean get() = kind == FileKind.FOLDER
    val isFile: Boolean get() = kind == FileKind.FILE
    val sizeBytes: Long get() = size.toLongOrNull() ?: 0L
}

@Serializable
internal data class FileListPage(
    @SerialName("next_page_token") val nextPageToken: String = "",
    val files: List<FileStat> = emptyList(),
)

@Serializable
data class DownloadLink(
    val url: String = "",
    val token: String = "",
    val expire: String = "",
)

@Serializable
data class FileDetail(
    val kind: String = "",
    val id: String = "",
    @SerialName("parent_id") val parentId: String = "",
    val name: String = "",
    val size: String = "0",
    @SerialName("file_extension") val fileExtension: String = "",
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("md5_checksum") val md5Checksum: String = "",
    val hash: String = "",
    val phase: String = "",
    val revision: String = "",
    val starred: Boolean = false,
    @SerialName("web_content_link") val webContentLink: String = "",
    val links: Links = Links(),
    val trashed: Boolean = false,
    val writable: Boolean = true,
) {
    @Serializable
    data class Links(
        @SerialName("application/octet-stream") val octetStream: DownloadLink = DownloadLink(),
    )

    val sizeBytes: Long get() = size.toLongOrNull() ?: 0L
    val downloadUrl: String? get() = links.octetStream.url.takeIf { it.isNotBlank() }
}

@Serializable
data class QuotaInfo(
    val kind: String = "",
    val limit: String = "0",
    val usage: String = "0",
    @SerialName("usage_in_trash") val usageInTrash: String = "0",
) {
    val limitBytes: Long get() = limit.toLongOrNull() ?: 0L
    val usageBytes: Long get() = usage.toLongOrNull() ?: 0L
    val remainingBytes: Long get() = limitBytes - usageBytes
}

@Serializable
data class QuotaResponse(
    val kind: String = "",
    val quota: QuotaInfo = QuotaInfo(),
    @SerialName("expires_at") val expiresAt: String = "",
)
