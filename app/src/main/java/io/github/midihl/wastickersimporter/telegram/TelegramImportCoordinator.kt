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
        if (converted.size < 3) throw ImportFailure(context.getString(R.string.telegram_too_few_valid, converted.size))
        val sourceHash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }.take(12)
        val trayBytes = resolveTray(set, converted.first().second)
        val displayName = set.title.ifBlank { shortName }
        val parsed = ParsedPack(
            name = shortName,
            publisher = "Telegram · $shortName",
            trayIconBytes = trayBytes,
            stickers = converted.map { RawSticker(it.first, it.second, StickerNames.isAnimatedWebp(it.second)) }
        )
        val materialized = PackRepository.materialize(context, parsed, parsed.name.toByteArray())
        val groupId = StickerNames.identifier(shortName, sourceHash)
        val now = System.currentTimeMillis()
        val animated = parsed.stickers.any { it.isAnimatedWebp }
        val oldParts = PackRepository.manifests(context)
            .filter { it.sourceType == "telegram" && it.telegramShortName == shortName }

        // Move all generated files first; write final manifests only after the move.
        val targets = materialized.manifests.mapIndexed { index, old ->
            val totalParts = materialized.manifests.size
            val suffix = if (totalParts > 1) "-part${index + 1}of$totalParts" else ""
            val identifier = StickerNames.identifier(shortName, sourceHash, suffix)
            val src = PackRepository.packDir(context, old.identifier)
            val dst = PackRepository.packDir(context, identifier)
            if (old.identifier != identifier) {
                dst.deleteRecursively()
                if (!src.renameTo(dst)) throw ImportFailure("cannot materialize pack directory")
            }
            val prior = oldParts.firstOrNull { it.identifier == identifier }
                ?: oldParts.firstOrNull { it.partIndex == index }
            val dataVersion = ((prior?.imageDataVersion?.toIntOrNull() ?: 0) + 1).toString()
            val suffixName = if (totalParts > 1) " (${index + 1}/$totalParts)" else ""
            val finalName = prior?.name?.takeIf { it.isNotBlank() } ?: "$displayName$suffixName"
            finalName to old.copy(
                identifier = identifier,
                name = StickerNames.truncateField(finalName, displayName),
                publisher = prior?.publisher?.takeIf { it.isNotBlank() } ?: "Telegram · $shortName",
                imageDataVersion = dataVersion,
                animatedStickerPack = animated,
                sourceHash = sourceHash,
                sourceType = "telegram",
                telegramShortName = shortName,
                fetchedAt = now,
                groupId = groupId,
                partIndex = index,
                partCount = totalParts
            )
        }
        val manifests = targets.map { (_, manifest) ->
            PackRepository.writeManifest(PackRepository.packDir(context, manifest.identifier), manifest)
            manifest
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
