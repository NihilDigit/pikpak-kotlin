package io.github.nihildigit.pikpak

/**
 * Which representation of a media file to read.
 *
 * PikPak returns the uploaded file plus, for video, a set of transcoded
 * MPEG-TS variants. They are separate resources with separate signed links and
 * different byte counts, so the choice has to be made before the first read
 * and cannot change afterwards.
 */
sealed interface VariantPreference {
    /** The unmodified source, via the octet-stream link. */
    data object Original : VariantPreference

    /**
     * A transcoded variant by resolution name, e.g. "1080P". Falls back to
     * [Original] when the file has no such variant or it is not ready yet.
     */
    data class Resolution(val name: String) : VariantPreference
}

/**
 * The variant a [VariantPreference] resolved to on one [FileDetail].
 *
 * [mediaId] is the durable handle. Persist it and pass it back to
 * [PikPakClient.rangeReader] to reopen the same bytes after the signed link
 * has expired.
 */
data class ResolvedVariant(
    /**
     * Null for the original file (octet-stream link); otherwise
     * [MediaVariant.mediaId].
     */
    val mediaId: String?,
    /** "Original" or the resolution name. */
    val label: String,
    val isOrigin: Boolean,
    val link: DownloadLink,
    /**
     * Known for the original ([FileDetail.sizeBytes]); null for transcodes,
     * whose length is not in the metadata. Use [PikPakClient.remoteSize].
     */
    val sizeBytes: Long?,
    val video: VideoInfo?,
)

/** Label the SDK reports for the octet-stream representation. */
private const val ORIGINAL_LABEL = "Original"

/**
 * True when this entry is a transcode that can actually be read: still-
 * transcoding entries appear in `medias` with no video metadata, and a
 * finished entry can still carry a blank link.
 */
private val MediaVariant.isPlayableTranscode: Boolean
    get() = !isOrigin && video != null && link.url.isNotBlank()

private fun FileDetail.originalVariant(): ResolvedVariant {
    val link = octetStream
    if (link.url.isBlank()) {
        throw PikPakException(-1, "resolveVariant: file $id has no octet-stream link")
    }
    return ResolvedVariant(
        mediaId = null,
        label = ORIGINAL_LABEL,
        isOrigin = true,
        link = link,
        sizeBytes = sizeBytes,
        video = medias.firstOrNull { it.isOrigin }?.video,
    )
}

private fun MediaVariant.toResolved(): ResolvedVariant = ResolvedVariant(
    mediaId = mediaId,
    label = resolutionName.ifBlank { mediaName },
    isOrigin = false,
    link = link,
    sizeBytes = null,
    video = video,
)

/**
 * Selects a variant, falling back to the original when the preferred one is
 * absent or not ready.
 *
 * Fallback happens here and only here. Once a caller holds a [ResolvedVariant]
 * the choice is fixed: re-running selection later, on a detail fetched after
 * a link expired, could land on a different variant and therefore a different
 * byte stream under an offset the caller already committed to.
 *
 * @throws PikPakException when the preference resolves to the original and the
 * file has no octet-stream link.
 */
fun FileDetail.resolveVariant(preference: VariantPreference): ResolvedVariant = when (preference) {
    is VariantPreference.Original -> originalVariant()
    is VariantPreference.Resolution -> {
        val match = medias.firstOrNull {
            it.isPlayableTranscode &&
                (it.resolutionName == preference.name || it.mediaName == preference.name)
        }
        match?.toResolved() ?: originalVariant()
    }
}

/**
 * Looks up the variant a [mediaId] names. Null [mediaId] means the original.
 *
 * Returns null when the id is no longer present in [FileDetail.medias]. There
 * is deliberately no fallback: another variant would be different bytes, and
 * a caller reading at a fixed offset would get silent corruption instead of
 * an error.
 */
fun FileDetail.variant(mediaId: String?): ResolvedVariant? {
    if (mediaId == null) return originalVariant()
    val media = medias.firstOrNull { it.mediaId == mediaId } ?: return null
    return media.toResolved()
}

/** [getFile] followed by [resolveVariant]. */
suspend fun PikPakClient.resolveVariant(
    fileId: String,
    preference: VariantPreference,
): ResolvedVariant = getFile(fileId).resolveVariant(preference)

/**
 * Total size of the resource behind a signed URL.
 *
 * Transcoded variants do not carry their length in the file metadata, so the
 * only way to learn it is to ask the CDN: a one-byte range request answers
 * with `Content-Range: bytes 0-0/TOTAL`.
 *
 * @throws PikPakException when the server does not report a total (`"*"` or no
 * Content-Range header at all).
 */
suspend fun PikPakClient.remoteSize(url: String): Long {
    val probed = streamRangeFromUrl(url, start = 0L, length = 1L) { it.totalSize }
    if (probed == null || probed <= 0L) {
        throw PikPakException(
            -1,
            "remoteSize: probe did not return a parseable Content-Range total " +
                "(got totalSize=$probed); server may have returned \"*\" or omitted Content-Range",
        )
    }
    return probed
}

/**
 * A [RangeReader] locked to one variant of [fileId].
 *
 * On expiry the reader re-fetches the file detail and takes the link belonging
 * to the same [mediaId]. It never re-runs preference selection: switching
 * variants mid-file would change the byte stream under offsets the caller has
 * already read past. A variant that has disappeared from the file detail fails
 * the read rather than silently resolving to another one.
 *
 * @param mediaId [ResolvedVariant.mediaId]; null reads the original file.
 */
fun PikPakClient.rangeReader(
    fileId: String,
    mediaId: String?,
    connectionBudget: Int = this.connectionBudget,
): RangeReader = RangeReader(
    client = this,
    urlProvider = {
        val resolved = getFile(fileId).variant(mediaId)
            ?: throw PikPakException(
                -1,
                "rangeReader: variant $mediaId is no longer present on file $fileId",
            )
        resolved.link.url.takeIf { it.isNotBlank() }
            ?: throw PikPakException(
                -1,
                "rangeReader: variant $mediaId on file $fileId has no link",
            )
    },
    connectionBudget = connectionBudget,
)
