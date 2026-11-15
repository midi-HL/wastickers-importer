package io.github.midihl.wastickersimporter.telegram

import java.util.Locale

/** T1: parse Telegram sticker-set links and bare short names. Pure JVM for unit tests. */
object TelegramLinkParser {
    private val SHORT_NAME = Regex("[A-Za-z0-9_]+")

    fun parse(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) throw ParseError("empty")
        val candidate = when {
            value.contains("://") || value.startsWith("t.me/", true) || value.startsWith("telegram.me/", true) -> {
                val normalized = if (value.contains("://")) value else "https://$value"
                val uri = java.net.URI(normalized)
                val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
                if (host != "t.me" && host != "telegram.me" && host != "www.t.me" && host != "www.telegram.me") {
                    throw ParseError("unsupported_host")
                }
                val segments = uri.rawPath.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
                if (segments.firstOrNull()?.equals("addstickers", ignoreCase = true) != true) {
                    throw ParseError("not_addstickers")
                }
                segments.getOrNull(1)?.substringBefore('?') ?: throw ParseError("missing_short_name")
            }
            value.startsWith("addstickers/", true) -> value.substringAfter('/').substringBefore('?').substringBefore('/')
            else -> value.substringBefore('?').substringBefore('/')
        }
        if (!candidate.matches(SHORT_NAME)) throw ParseError("invalid_short_name")
        return candidate
    }

    fun isTelegramStickerLink(uri: android.net.Uri?): Boolean {
        if (uri == null) return false
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        return uri.scheme in setOf("http", "https") &&
            (host == "t.me" || host == "telegram.me") &&
            uri.path.orEmpty().startsWith("/addstickers/", ignoreCase = true)
    }

    class ParseError(val reason: String) : IllegalArgumentException(reason)
}
