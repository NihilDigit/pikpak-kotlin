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

/**
 * Per-track media metadata for a [MediaVariant]. Populated for video files;
 * other file types omit this entirely. Nullable on [MediaVariant] because some
 * transcode entries appear in the API response before their video metadata is
 * available (still-transcoding state).
 */
@Serializable
data class VideoInfo(
    val height: Int = 0,
    val width: Int = 0,
    /** Duration of the underlying media, in seconds. */
    val duration: Long = 0L,
    /** Average bit rate in bits per second. Near-CBR for transcoded variants. */
    @SerialName("bit_rate") val bitRate: Long = 0L,
    @SerialName("frame_rate") val frameRate: Int = 0,
    @SerialName("video_codec") val videoCodec: String = "",
    @SerialName("audio_codec") val audioCodec: String = "",
    /** Container format. Observed values: "matroska,webm" (origin), "mpegts" (transcode). */
    @SerialName("video_type") val videoType: String = "",
    @SerialName("hdr_type") val hdrType: String = "",
)

/**
 * One alternate representation of a media file (e.g. the original 1080P mkv and
 * 3 transcoded MPEG-TS variants at 1080P/720P/480P). Returned in [FileDetail.medias].
 *
 * The [link] field carries a signed CDN URL with the same lifetime semantics as
 * [FileDetail.downloadUrl] — short-lived, refresh by re-fetching the parent file
 * detail. Transcode-variant URLs accept byte-range requests and serve raw
 * MPEG-TS bytes, which is what enables time-based clipping without downloading
 * the whole file.
 */
@Serializable
data class MediaVariant(
    @SerialName("media_id") val mediaId: String = "",
    /** Display name from the API. Observed: "Original", "1080P", "720P", "480P". */
    @SerialName("media_name") val mediaName: String = "",
    val video: VideoInfo? = null,
    val link: DownloadLink = DownloadLink(),
    /** True for the unmodified source; false for transcoded variants. */
    @SerialName("is_origin") val isOrigin: Boolean = false,
    /** True for the variant PikPak's clients pick by default for in-app playback. */
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("is_visible") val isVisible: Boolean = true,
    val priority: Int = 0,
    /** Resolution label, e.g. "1080P". Distinct from [mediaName] only for the origin entry. */
    @SerialName("resolution_name") val resolutionName: String = "",
    /** "category_origin" or "category_transcode". */
    val category: String = "",
) {
    val url: String? get() = link.url.takeIf { it.isNotBlank() }
}

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
    /**
     * Alternate representations of this file. Empty for non-media files and for
     * media that hasn't been transcoded yet. See [MediaVariant] for byte-range
     * streaming context.
     */
    val medias: List<MediaVariant> = emptyList(),
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
