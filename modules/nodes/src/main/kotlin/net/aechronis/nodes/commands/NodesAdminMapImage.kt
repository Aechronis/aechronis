package net.aechronis.nodes.commands

import java.net.URI

internal fun validMapImageUrl(value: String): Boolean {
    if (value.length > 2048) return false
    return runCatching {
        val uri = URI(value)
        val http = uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)
        http && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)
}
