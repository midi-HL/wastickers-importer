package io.github.midihl.wastickersimporter.model

/**
 * Emoji helpers shared by JVM tests and Android code. Telegram's Bot API returns
 * emoji strings directly, so no filesystem-safe transformation is needed there.
 */
object StickerMetadata {
    fun emojisFromFileName(fileName: String, fallback: String = "😀"): List<String> =
        StickerNames.emojisFromFileName(fileName, fallback)

    fun apiEmojis(value: String, fallback: String = "😀"): List<String> =
        value.codePoints().toArray().take(3).map { String(Character.toChars(it)) }
            .ifEmpty { listOf(fallback) }
}
