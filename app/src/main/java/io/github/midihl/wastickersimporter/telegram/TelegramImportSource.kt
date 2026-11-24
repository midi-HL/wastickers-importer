package io.github.midihl.wastickersimporter.telegram

import java.io.File

/** Bot API boundary. Device tests use recorded packs without network or credentials. */
interface TelegramImportSource {
    fun getStickerSet(shortName: String): TelegramStickerSet
    fun downloadFile(fileId: String, destination: File): File
}
