package io.github.midihl.wastickersimporter.telegram

/** Pure JVM metadata mapping fixture used by both the Bot API client and unit tests. */
data class TelegramRecordedSticker(
    val fileId: String,
    val emoji: String,
    val isAnimated: Boolean,
    val isVideo: Boolean,
    val thumbnailFileId: String = ""
)

object TelegramMetadataMapper {
    fun map(
        shortName: String,
        title: String,
        stickerType: String,
        stickers: List<TelegramRecordedSticker>,
        thumbnailFileId: String = ""
    ): TelegramStickerSet = TelegramStickerSet(
        name = shortName,
        title = title.ifBlank { shortName },
        stickerType = stickerType.ifBlank { "regular" },
        stickers = stickers.map {
            TelegramSticker(
                fileId = it.fileId,
                emoji = it.emoji.ifBlank { "😀" },
                isAnimated = it.isAnimated,
                isVideo = it.isVideo,
                thumbnailFileId = it.thumbnailFileId
            )
        },
        thumbnailFileId = thumbnailFileId
    )
}
