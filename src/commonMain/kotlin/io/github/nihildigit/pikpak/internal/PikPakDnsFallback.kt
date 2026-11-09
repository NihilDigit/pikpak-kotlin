package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.DnsHostEntry
import kotlin.concurrent.Volatile

/**
 * Process-wide static DNS fallback registry populated from PikPak's
 * `/config/v1/high_availability` endpoint and seeded with baseline gateway IPs.
 *
 * Used by the JVM/Android OkHttp engine when system DNS fails (`UnknownHostException`)
 * or returns bogon/unspecified addresses, and appended behind system-resolved routes
 * so OkHttp's `RouteSelector` can fail over to static gateway IPs if a carrier-resolved
 * IP drops TCP/TLS handshakes.
 */
internal object PikPakDnsFallback {
    private val DEFAULT_ENTRIES: Map<String, List<String>> = mapOf(
        "api-drive.mypikpak.com" to listOf("18.166.197.251", "18.166.9.106", "16.162.88.53", "43.199.139.252"),
        "user.mypikpak.com" to listOf("18.166.197.251", "18.166.9.106", "16.162.88.53", "43.199.139.252"),
        "access.mypikpak.com" to listOf("18.166.197.251", "18.166.9.106", "16.162.88.53", "43.199.139.252"),
        "dl-z01a-0026.mypikpak.com" to listOf("103.168.55.34", "45.43.33.149"),
        "dl-z01a-0027.mypikpak.com" to listOf("45.43.33.148", "103.168.55.56"),
        "dl-z01a-0028.mypikpak.com" to listOf("45.43.33.166", "103.168.55.57"),
        "dl-z01a-0029.mypikpak.com" to listOf("103.168.55.35", "45.43.33.165"),
    )

    @Volatile
    private var table: Map<String, List<String>> = DEFAULT_ENTRIES

    fun update(entries: List<DnsHostEntry>) {
        if (entries.isEmpty()) return
        val merged = LinkedHashMap<String, List<String>>(DEFAULT_ENTRIES)
        for (( host, v4, v6 ) in entries) {
            val normalizedHost = host.trim().lowercase()
            if (normalizedHost.isEmpty()) continue
            val ips = (v4 + v6).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (ips.isNotEmpty()) {
                merged[normalizedHost] = ips
            }
        }
        table = merged
    }

    fun resetForTest() {
        table = DEFAULT_ENTRIES
    }

    /**
     * Returns configured fallback IP literals for [hostname], or an empty list if none apply.
     * Mirror subdomains (e.g. `api-drive.mypikpak.net` or `dl-z01a-0026.mypikpak.net`) fall
     * back to their `.mypikpak.com` entry when no root-specific entry is present.
     */
    fun lookupIps(hostname: String): List<String> {
        val host = hostname.trim().lowercase().trimEnd('.')
        if (host.isEmpty()) return emptyList()
        val snapshot = table
        snapshot[host]?.let { return it }
        val dot = host.indexOf('.')
        if (dot > 0) {
            val sub = host.substring(0, dot)
            if (sub == "api-drive" || sub == "user" || sub == "access" || sub.startsWith("dl-")) {
                snapshot["$sub.mypikpak.com"]?.let { return it }
            }
        }
        return emptyList()
    }
}
