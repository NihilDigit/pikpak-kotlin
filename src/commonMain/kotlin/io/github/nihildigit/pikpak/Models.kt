package io.github.nihildigit.pikpak

import kotlin.time.Instant
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
    /**
     * PikPak's gcid content hash, 40 uppercase hex. Present on every file,
     * including the ones an offline task produced — checked against 96 of them
     * on 2026-09-11, none empty. This is the stable identity of the content:
     * [id] is one account's handle on it and dies with the file, while the gcid
     * survives and can create the file again through an instant upload.
     */
    val hash: String = "",
    val phase: String = "",
    val trashed: Boolean = false,
    /**
     * When a trashed item is purged for good, RFC 3339; empty outside the trash.
     * Observed 2026-09-23: fifteen days after it was trashed, on a platinum
     * account. The server sets it, so read it rather than add a fixed period.
     */
    @SerialName("delete_time") val deleteTime: String = "",
    /**
     * Server-supplied extras. `url` holds where the item came from: the magnet
     * for anything an offline task produced, which is the only place that
     * association is recorded — the tasks endpoint cannot be queried by URL or
     * info hash — or the `https://mypikpak.com/s/<shareId>` link for anything
     * restored from a share (see [shareIdFromUrl]). Also carries `duration`,
     * `width`, `height` for media.
     */
    val params: Map<String, String> = emptyMap(),
    /**
     * System tags on the item. A starred item carries one named `STAR`; this is
     * the only place a listing reports it — the `starred` field of the detail
     * response stayed false on starred items when probed on 2026-09-24.
     */
    val tags: List<FileTag> = emptyList(),
) {
    val isFolder: Boolean get() = kind == FileKind.FOLDER

    /** Whether the item is starred; see [tags]. */
    val isStarred: Boolean get() = tags.any { it.name == FileTag.STAR }
    val isFile: Boolean get() = kind == FileKind.FILE
    val sizeBytes: Long get() = size.toLongOrNull() ?: 0L

    /** Where the item came from: a magnet or a share link, see [params]. Null when neither. */
    val sourceUrl: String? get() = params["url"]
}

/** One entry of [FileStat.tags]. */
@Serializable
data class FileTag(
    val id: String = "",
    val name: String = "",
    val type: Int = 0,
) {
    companion object {
        const val STAR = "STAR"
    }
}

@Serializable
data class FileListPage(
    @SerialName("next_page_token") val nextPageToken: String = "",
    val files: List<FileStat> = emptyList(),
)

@Serializable
data class DownloadLink(
    val url: String = "",
    val token: String = "",
    /** Raw `expire` field as PikPak sends it. Prefer [expiresAt]. */
    val expire: String = "",
) {
    /**
     * When the signature on [url] stops being accepted, or null when PikPak
     * gave nothing parseable. Past this moment the CDN answers 403 with an
     * empty body, which the SDK surfaces as [UrlExpiredException].
     *
     * Three encodings have been observed in the wild for the same field, so
     * all three are accepted: RFC 3339, epoch seconds, epoch milliseconds. If
     * the field is absent the `expire` query parameter on [url] is used, which
     * is what the CDN actually signs.
     */
    val expiresAt: Instant?
        get() = parseExpiry(expire) ?: parseExpiry(url.queryParam("expire"))
}

private fun parseExpiry(raw: String?): Instant? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val numeric = value.toLongOrNull()
    if (numeric != null) {
        // Anything past this magnitude cannot be seconds — 10^12 seconds is
        // the year 33658, while 10^12 milliseconds is 2001.
        return if (numeric >= 1_000_000_000_000L) {
            Instant.fromEpochMilliseconds(numeric)
        } else {
            Instant.fromEpochSeconds(numeric)
        }
    }
    return try {
        Instant.parse(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun String.queryParam(name: String): String? {
    val q = substringAfter('?', "")
    if (q.isEmpty()) return null
    for (pair in q.split('&')) {
        val eq = pair.indexOf('=')
        if (eq > 0 && pair.substring(0, eq) == name) return pair.substring(eq + 1)
    }
    return null
}

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
    /** gcid content hash; see [FileStat.hash]. Identical in listing and detail. */
    val hash: String = "",
    val phase: String = "",
    val revision: String = "",
    /** Stayed false on starred items when probed on 2026-09-24; read [FileStat.isStarred] from a listing instead. */
    val starred: Boolean = false,
    @SerialName("web_content_link") val webContentLink: String = "",
    /**
     * Signed download links keyed by content type. PikPak has been observed to
     * return more than one; each carries its own signature and therefore its
     * own 8-connection budget, which is the reason not to collapse them to the
     * one entry the SDK used to model.
     */
    val links: Map<String, DownloadLink> = emptyMap(),
    /**
     * Alternate representations of this file. Empty for non-media files and for
     * media that hasn't been transcoded yet. See [MediaVariant] for byte-range
     * streaming context.
     */
    val medias: List<MediaVariant> = emptyList(),
    val trashed: Boolean = false,
    val writable: Boolean = true,
    /** Server-supplied extras; see [FileStat.params]. */
    val params: Map<String, String> = emptyMap(),
) {
    val sizeBytes: Long get() = size.toLongOrNull() ?: 0L

    /** Where the item came from: a magnet or a share link, see [FileStat.params]. Null when neither. */
    val sourceUrl: String? get() = params["url"]

    /** The `application/octet-stream` link — the raw file — or an empty one. */
    val octetStream: DownloadLink get() = links[OCTET_STREAM] ?: DownloadLink()

    val downloadUrl: String? get() = octetStream.url.takeIf { it.isNotBlank() }

    companion object {
        const val OCTET_STREAM = "application/octet-stream"
    }
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
