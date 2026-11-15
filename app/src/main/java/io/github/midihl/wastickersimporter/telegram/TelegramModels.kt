package io.github.midihl.wastickersimporter.telegram

data class TelegramFileRef(val fileId: String, val filePath: String = "")

data class TelegramSticker(
    val fileId: String,
    val emoji: String,
    val isAnimated: Boolean,
    val isVideo: Boolean,
    val thumbnailFileId: String = ""
)

data class TelegramStickerSet(
    val name: String,
    val title: String,
    val stickerType: String,
    val stickers: List<TelegramSticker>,
    val thumbnailFileId: String = ""
)

data class TelegramBotInfo(val username: String)

class TelegramApiException(val code: Int, val description: String) : Exception(description)
