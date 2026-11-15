package io.github.midihl.wastickersimporter.model

data class StickerEntry(
    val imageFileName: String,
    val emojis: List<String>,
    val accessibilityText: String = "",
    val sizeBytes: Long = 0L
)

data class PackManifest(
    val identifier: String,
    var name: String,
    var publisher: String,
    val trayImageFile: String,
    val imageDataVersion: String = "1",
    val avoidCache: Boolean = false,
    val animatedStickerPack: Boolean = false,
    val stickers: List<StickerEntry>,
    val androidPlayStoreLink: String = "",
    val iosAppStoreLink: String = "",
    val publisherEmail: String = "",
    val publisherWebsite: String = "",
    val privacyPolicyWebsite: String = "",
    val licenseAgreementWebsite: String = "",
    var sourceHash: String = "",
    var sourceType: String = "file",
    var telegramShortName: String = "",
    var fetchedAt: Long = 0L,
    var groupId: String = "",
    var partIndex: Int = 0,
    var partCount: Int = 1
) {
    val stickerCount: Int get() = stickers.size
}

/** A parsed but not yet materialized `.wastickers` ZIP, used by both Android code and JVM tests. */
data class ParsedPack(
    var name: String,
    var publisher: String,
    val trayIconBytes: ByteArray?,
    val stickers: List<RawSticker>
)

data class RawSticker(
    val originalName: String,
    val bytes: ByteArray,
    val isAnimatedWebp: Boolean
)
