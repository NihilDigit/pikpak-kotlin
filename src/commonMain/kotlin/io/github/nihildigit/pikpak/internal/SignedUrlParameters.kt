package io.github.nihildigit.pikpak.internal

import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.URLParserException

/** Decode once without repairing malformed signed queries or including URL fragments. */
internal fun signedUrlParameters(url: String): Parameters? {
    if (url.any { it.isWhitespace() || it.code < 0x20 || it == '\\' }) return null
    val query = url.substringBefore('#').substringAfter('?', "")
    if (query.isNotEmpty()) {
        for (pair in query.split('&')) {
            if (pair.indexOf('=') <= 0) return null
        }
        for (index in query.indices) {
            if (query[index] == '%' && (index + 2 >= query.length ||
                    query[index + 1].digitToIntOrNull(16) == null ||
                    query[index + 2].digitToIntOrNull(16) == null)) return null
        }
    }
    return try {
        val parsed = Url(url)
        if (!url.startsWith("${parsed.protocol.name}://") ||
            parsed.protocol.name !in listOf("http", "https") || parsed.host.isEmpty()) return null
        parsed.parameters.takeUnless { params ->
            params.entries().any { (key, values) ->
                '\uFFFD' in key || values.any { '\uFFFD' in it }
            }
        }
    } catch (_: URLParserException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
