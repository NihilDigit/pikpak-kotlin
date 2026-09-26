package io.github.nihildigit.pikpak

/** Lowercase hex, the form every hash on the PikPak wire takes except the gcid; see [canonicalGcid]. */
internal fun ByteArray.toHex(): String = joinToString("") {
    val v = it.toInt() and 0xff
    val hi = v ushr 4
    val lo = v and 0x0f
    "${hexChar(hi)}${hexChar(lo)}"
}

/**
 * The one spelling of a gcid the SDK hands out: upper case, as the server lists
 * it. The server accepts either case in a request, but callers store gcids and
 * compare them with `==`, and a local hash in lower case read as a different file.
 */
internal fun String.canonicalGcid(): String = uppercase()

private fun hexChar(v: Int): Char = if (v < 10) ('0' + v) else ('a' + (v - 10))
