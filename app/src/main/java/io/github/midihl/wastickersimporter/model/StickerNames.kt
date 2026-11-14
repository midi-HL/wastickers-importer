package io.github.midihl.wastickersimporter.model

import java.security.MessageDigest
import java.util.Locale

/**
 * Identifier and emoji rules live in a JVM-only object so the `test` source set can verify
 * them without an Android SDK. No Android classes are referenced here.
 */
object StickerNames {
    private val IDENTIFIER_ALLOWED = Regex("[^A-Za-z0-9._-]")
    private val EMOJI_RANGES = listOf(
        0x1F000..0x1FAFF,
        0x2600..0x27BF,
        0x2190..0x21FF,
        0x2B00..0x2BFF,
        0xFE00..0xFE0F,
        0x1F1E6..0x1F1FF,
        0x00A9..0x00AE,
        0x203C..0x3299
    )

    fun shortHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }.take(12)
    }

    fun identifier(name: String, sourceHash: String, suffix: String = ""): String {
        val cleaned = name.trim().replace(IDENTIFIER_ALLOWED, "_").trimStart('.')
            .take(96).trimEnd('.', '_', '-')
        val base = if (cleaned.isEmpty()) "pack-$sourceHash" else "$cleaned-$sourceHash"
        return (base + suffix).take(128)
    }

    /** Extract up to three emoji code-point clusters from a ZIP entry name. */
    fun emojisFromFileName(fileName: String, fallback: String = "😀"): List<String> {
        val found = mutableListOf<String>()
        var index = 0
        while (index < fileName.length) {
            val cp = fileName.codePointAt(index)
            index += Character.charCount(cp)
            if (isEmojiCodePoint(cp)) {
                val symbol = String(Character.toChars(cp))
                if (symbol !in found) found += symbol
                if (found.size == 3) break
            }
        }
        return if (found.isEmpty()) listOf(fallback) else found
    }

    private fun isEmojiCodePoint(cp: Int): Boolean = EMOJI_RANGES.any { cp in it }

    fun truncateField(value: String, fallback: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.isEmpty() -> fallback
            trimmed.length > 128 -> trimmed.take(128)
            else -> trimmed
        }
    }

    /** True when WebP bytes declare the ANIM chunk (animated WebP, passed through untouched). */
    fun isAnimatedWebp(bytes: ByteArray): Boolean {
        if (bytes.size < 20) return false
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val webp = String(bytes, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || webp != "WEBP") return false
        val vp8 = String(bytes, 12, 4, Charsets.US_ASCII)
        return vp8 == "VP8X" && (bytes[20].toInt() and 0x02) != 0
    }
}
