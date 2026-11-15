package io.github.midihl.wastickersimporter.telegram

import android.content.Context
import io.github.midihl.wastickersimporter.PackRepository
import io.github.midihl.wastickersimporter.R
import io.github.midihl.wastickersimporter.convert.StaticImageNormalizer
import io.github.midihl.wastickersimporter.convert.TgsToWebpConverter
import io.github.midihl.wastickersimporter.convert.WebmToWebpConverter
import io.github.midihl.wastickersimporter.model.PackManifest
import io.github.midihl.wastickersimporter.model.ParsedPack
import io.github.midihl.wastickersimporter.model.RawSticker
import io.github.midihl.wastickersimporter.model.StickerNames
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** T3/T4/T5: coordinates download, conversion and unified in-app storage. */
class TelegramImportCoordinator(
    private val context: Context,
    private val token: String,
    private val cancelled: () -> Boolean = { false }
) {
    data class Progress(val done: Int, val total: Int, val stage: String)
    data class Result(val manifests: List<PackManifest>, val warnings: List<String>, val sourceHash: String)

    private val client = TelegramBotClient(token)
    private val cacheDir = File(context.cacheDir, "telegram").apply { mkdirs() }

    fun fetchAndImport(shortName: String, onProgress: (Progress) -> Unit): Result {
        val set = client.getStickerSet(shortName)
        if (cancelled()) throw CancelledException()
        val total = set.stickers.size
        val converted = ArrayList<Pair<String, ByteArray>>()
        val warnings = ArrayList<String>()
        val digest = MessageDigest.getInstance("SHA-256")

        for ((index, sticker) in set.stickers.withIndex()) {
            if (cancelled()) throw CancelledException()
            onProgress(Progress(index, total, context.getString(R.string.telegram_stage_download)))
            val rawFile = File(cacheDir, "${shortName}_${index}.bin")
            val bytes = runCatching { client.downloadFile(sticker.fileId, rawFile); rawFile.readBytes() }
                .getOrElse {
                    warnings += context.getString(R.string.telegram_sticker_download_failed, index + 1)
                    rawFile.delete()
                    null
                }
            if (bytes != null) {
                digest.update(bytes)
                onProgress(Progress(index, total, context.getString(R.string.telegram_stage_convert)))
                val output = when {
                    sticker.isAnimated -> TgsToWebpConverter.convert(context, bytes)
                    sticker.isVideo -> WebmToWebpConverter.convert(rawFile, File(cacheDir, "frames_${shortName}_$index"))
                    else -> StaticImageNormalizer.normalize(bytes)
                }
                if (output != null) {
                    converted += "${index + 1}${sticker.emoji}.webp" to output
                } else {
                    warnings += context.getString(R.string.telegram_sticker_conversion_failed, index + 1)
                }
            }
            rawFile.delete()
        }
        if (cancelled()) throw CancelledException()
        if (converted.size < 3) {
            throw ImportFailure(context.getString(R.string.telegram_too_few_valid, converted.size))
        }
        val sourceHash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }.take(12)
        val trayBytes = resolveTray(set, converted.first().second)
        val parsed = ParsedPack(
            name = set.title.ifBlank { shortName },
            publisher = "Telegram · $shortName",
            trayIconBytes = trayBytes,
            stickers = converted.map { RawSticker(it.first, it.second, StickerNames.isAnimatedWebp(it.second)) }
        )
        val materialized = PackRepository.materialize(
            context,
            parsed.copy(name = shortName),
            parsed.name.toByteArray()
        )
        val groupId = StickerNames.identifier(shortName, sourceHash)
        val now = System.currentTimeMillis()
        val animated = parsed.stickers.any { it.isAnimatedWebp }
        val oldParts = PackRepository.manifests(context)
            .filter { it.sourceType == "telegram" && it.telegramShortName == shortName }
        val manifests = materialized.manifests.mapIndexed { index, manifest ->
            val suffix = if (materialized.manifests.size > 1) "-part${index + 1}of${materialized.manifests.size}" else ""
            val identifier = StickerNames.identifier(shortName, sourceHash, suffix)
            val dir = PackRepository.packDir(context, identifier)
            val prior = oldParts.firstOrNull { it.identifier == identifier }
                ?: oldParts.firstOrNull { it.partIndex == index }
            val dataVersion = ((prior?.imageDataVersion?.toIntOrNull() ?: 0) + 1).toString()
            val upgraded = manifest.copy(
                identifier = identifier,
                name = prior?.name?.takeIf { it.isNotBlank() } ?: manifest.name,
                publisher = prior?.publisher?.takeIf { it.isNotBlank() } ?: manifest.publisher,
                imageDataVersion = dataVersion,
                animatedStickerPack = animated,
                sourceHash = sourceHash,
                sourceType = "telegram",
                telegramShortName = shortName,
                fetchedAt = now,
                groupId = groupId,
                partIndex = index,
                partCount = materialized.manifests.size
            )
            PackRepository.writeManifest(dir, upgraded)
            upgraded
        }
        // materialize() used title-based identifiers; move generated files to the short-name identifier.
        materialized.manifests.forEachIndexed { index, old ->
            val targetIdentifier = manifests[index].identifier
            if (old.identifier == targetIdentifier) return@forEachIndexed
            val src = PackRepository.packDir(context, old.identifier)
            val dst = PackRepository.packDir(context, targetIdentifier)
            dst.deleteRecursively()
            src.renameTo(dst)
        }
        cleanupOldTelegramParts(shortName, manifests.map { it.identifier }.toSet())
        cacheDir.deleteRecursively()
        return Result(manifests, warnings + materialized.warnings, sourceHash)
    }

    private fun resolveTray(set: TelegramStickerSet, firstSticker: ByteArray): ByteArray? {
        val thumbId = set.thumbnailFileId.ifBlank { set.stickers.firstOrNull()?.thumbnailFileId.orEmpty() }
        if (thumbId.isNotBlank()) {
            val file = File(cacheDir, "tray.bin")
            runCatching { client.downloadFile(thumbId, file) }
                .mapCatching { PackRepository.makeTray(ParsedPack("", "", file.readBytes(), listOf(RawSticker("tray", file.readBytes(), false)))) }
                .getOrNull()?.let { file.delete(); return it }
            file.delete()
        }
        return runCatching {
            PackRepository.makeTray(ParsedPack("", "", firstSticker, listOf(RawSticker("first", firstSticker, false))))
        }.getOrNull()
    }

    private fun cleanupOldTelegramParts(shortName: String, keep: Set<String>) {
        PackRepository.manifests(context)
            .filter { it.sourceType == "telegram" && it.telegramShortName == shortName && it.identifier !in keep }
            .forEach { PackRepository.delete(context, it.identifier) }
    }

    class CancelledException : Exception("cancelled")
    class ImportFailure(message: String) : Exception(message)
}
