package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.PikPakException
import io.ktor.http.Headers
import io.ktor.http.fromHttpToGmtDate
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal val PikPakException.isThrottlePressure: Boolean
    get() = httpStatus == 429 || httpStatus == 503 || isConcurrencyRestriction

private val XOS_CONCURRENCY_RESTRICTIONS = setOf(
    "netdisc_urlconnectioncountrestriction",
    "netdisc_userconnectioncountrestriction",
    "netdisc_urlipconnectioncountrestriction",
    "netdisc_ipcountrestriction",
    "netdisc_ipdaycountrestriction",
    "netdisc_uacountrestriction",
    "netdisc_referencecountrestriction",
    "netdisc_requestrangeconflict",
)

private val XOS_SIGNATURE_ERRORS = setOf(
    "netdisc_urlsignaturefault",
    "netdisc_urlexpired",
    "netdisc_uadisable",
    "netdisc_uadisabled",
)

private val XOS_COLD_ARCHIVE_ERRORS = setOf(
    "5",
    "netdiscfilearchived",
    "netdiscfilethawtime",
)

internal fun isXosConcurrencyRestriction(xosErrDesc: String?): Boolean {
    val normalized = xosErrDesc?.trim()?.lowercase() ?: return false
    return normalized in XOS_CONCURRENCY_RESTRICTIONS
}

internal fun isXosUrlSignatureError(xosErrDesc: String?): Boolean {
    val normalized = xosErrDesc?.trim()?.lowercase() ?: return false
    return normalized in XOS_SIGNATURE_ERRORS
}

internal fun isXosColdArchive(xosErrDesc: String?, storageClass: String?): Boolean {
    val err = xosErrDesc?.trim()?.lowercase()
    if (err != null && err in XOS_COLD_ARCHIVE_ERRORS) return true
    val storage = storageClass?.trim()?.lowercase()
    return storage == "archive"
}

internal fun Headers.isConcurrencyRestriction(): Boolean =
    isXosConcurrencyRestriction(this["X-Xos-Err-Desc"])

internal fun Headers.isUrlSignatureError(): Boolean =
    isXosUrlSignatureError(this["X-Xos-Err-Desc"])

internal fun Headers.isColdArchive(): Boolean =
    isXosColdArchive(this["X-Xos-Err-Desc"], this["X-Xos-Storage-Class"])

internal fun PikPakException.retryAfter(): Duration? =
    parseRetryAfter(headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value?.firstOrNull())

/** Share the HTTP layer's thirty-second bound across range and API retry owners. */
internal fun parseRetryAfter(raw: String?, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): Duration? {
    val value = raw?.trim() ?: return null
    value.toLongOrNull()?.let { return if (it < 0) null else it.coerceAtMost(30).seconds }
    return try {
        (value.fromHttpToGmtDate().timestamp - nowMillis).coerceIn(0L, 30_000L).milliseconds
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}
