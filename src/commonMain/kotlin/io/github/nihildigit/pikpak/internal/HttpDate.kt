package io.github.nihildigit.pikpak.internal

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * RFC 1123 formatter for HTTP-date / OSS Date header.
 * Example: `Sun, 06 Nov 1994 08:49:37 GMT`. Independent of Java's
 * SimpleDateFormat so it works across all KMP targets.
 */
internal fun Instant.formatHttpDate(): String {
    val dt = toLocalDateTime(TimeZone.UTC)
    val day = DAYS[(dt.dayOfWeek.ordinal)]
    val mon = MONTHS[dt.month.number - 1]
    val dom = dt.day.padded(2)
    val hh = dt.hour.padded(2)
    val mm = dt.minute.padded(2)
    val ss = dt.second.padded(2)
    return "$day, $dom $mon ${dt.year} $hh:$mm:$ss GMT"
}

private val DAYS = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private fun Int.padded(width: Int): String {
    val s = toString()
    if (s.length >= width) return s
    return "0".repeat(width - s.length) + s
}
