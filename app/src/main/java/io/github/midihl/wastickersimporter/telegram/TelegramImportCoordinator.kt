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
import io.github.midihl.wastickersimporter.model.StickerMetadata
import io.github.midihl.wastickersimporter.model.StickerNames
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** T3/T4/T5: coordinates download, conversion and unified in-app storage. */
class TelegramImportCoordinator(
    private val context: Context,
    token: String,
    private val workers: ImportWorkerPool,
    private val cancelled: () -> Boolean = { false },
    private val client: TelegramImportSource = TelegramBotClient(token, cancelled)
) {
    data class Result(val manifests: List<PackManifest>, val warnings: List<String>, val sourceHash: String)
    private data class ConvertedSticker(val fileName: String, val bytes: ByteArray, val emojis: List<String>)

    private val cacheDir = File(context.cacheDir, "telegram/${UUID.randomUUID()}").apply { mkdirs() }
    private data class Processed(val sticker: ConvertedSticker?, val downloaded: Boolean, val warning: String?)

    private fun checkCancelled() { if (cancelled()) throw CancelledException() }

    suspend fun fetchAndImport(shortName: String, onProgress: (ImportProgress) -> Unit): Result = try {
        onProgress(ImportProgress(stage = ImportStage.FETCH))
        val set = withContext(workers.dispatcher) { checkCancelled(); client.getStickerSet(shortName) }
        coroutineContext.ensureActive()
        val total = set.stickers.size
        val completed = AtomicInteger()
        val next = AtomicInteger()
        val results = arrayOfNulls<Processed>(total)
        val progressLock = Any()
        fun report(stage: ImportStage, complete: Boolean = false) = synchronized(progressLock) {
            if (complete) completed.incrementAndGet()
            onProgress(ImportProgress(completed.get(), total, stage))
        }
        coroutineScope {
            List(minOf(ImportWorkerPool.MAX_THREADS, total)) {
                async(Dispatchers.Default) {
                    while (true) {
                        ensureActive()
                        val index = next.getAndIncrement()
                        if (index >= total) break
                        val sticker = set.stickers[index]
                        val rawFile = File(cacheDir, "$index.bin")
                        var downloaded = false
                        results[index] = try {
                            report(ImportStage.DOWNLOAD)
                            val bytes = withContext(workers.dispatcher) {
                                checkCancelled()
                                client.downloadFile(sticker.fileId, rawFile)
                                downloaded = true
                                rawFile.readBytes()
                            }
                            report(ImportStage.CONVERT)
                            suspend fun convert(): ByteArray? = withContext(workers.dispatcher) {
                                checkCancelled()
                                when {
                                    sticker.isAnimated -> TgsToWebpConverter.convert(context, bytes, cancelled)
                                    sticker.isVideo -> WebmToWebpConverter.convert(rawFile, File(cacheDir, "frames_$index"), cancelled)
                                    else -> StaticImageNormalizer.normalize(bytes)
                                }.also { checkCancelled() }
                            }
                            val output = if (sticker.isAnimated || sticker.isVideo) workers.animatedSlots.withPermit { convert() } else convert()
                            if (output == null) Processed(null, downloaded, context.getString(R.string.telegram_sticker_conversion_failed, index + 1))
                            else Processed(ConvertedSticker("${index + 1}_${sanitizeFileName(sticker.emoji)}.webp", output,
                                StickerMetadata.apiEmojis(sticker.emoji)), downloaded, null)
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) {
                            checkCancelled()
                            Processed(null, downloaded, context.getString(
                                if (downloaded) R.string.telegram_sticker_conversion_failed else R.string.telegram_sticker_download_failed, index + 1))
                        }
                        report(ImportStage.CONVERT, complete = true)
                    }
                }
            }.awaitAll()
        }
        // Always reduce in source order, independent of worker completion order.
        withContext(workers.dispatcher) {
            checkCancelled()
            val converted = results.mapNotNull { it?.sticker }
            val warnings = results.mapNotNull { it?.warning }
            val digest = MessageDigest.getInstance("SHA-256")
            results.forEachIndexed { index, result ->
                checkCancelled()
                if (result?.downloaded == true) File(cacheDir, "$index.bin").inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { checkCancelled(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
            }
            if (converted.size < 3) throw ImportFailure(context.getString(R.string.telegram_too_few_valid, converted.size))
            val sourceHash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }.take(12)
            val trayBytes = resolveTray(set, converted.first().bytes)
            val displayName = set.title.ifBlank { shortName }
            val emojisByFileName = converted.associate { it.fileName to it.emojis }
            val parsed = ParsedPack(
                name = shortName,
                publisher = "Telegram · $shortName",
                trayIconBytes = trayBytes,
                stickers = converted.map { RawSticker(it.fileName, it.bytes, StickerNames.isAnimatedWebp(it.bytes)) }
            )
            checkCancelled()
            onProgress(ImportProgress(total, total, ImportStage.SAVE))
            // Only the final disk commit is serialized, not fetching/download/conversion.
            synchronized(PackRepository) {
                checkCancelled()
                val materialized = PackRepository.materialize(context, parsed, parsed.name.toByteArray()) { fileName ->
                    emojisByFileName[fileName].orEmpty().ifEmpty { listOf("😀") }
                }
                val groupId = StickerNames.identifier(shortName, sourceHash)
                val now = System.currentTimeMillis()
                val animated = parsed.stickers.any { it.isAnimatedWebp }
                val oldParts = PackRepository.manifests(context)
                    .filter { it.sourceType == "telegram" && it.telegramShortName.equals(shortName, ignoreCase = true) }

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
                Result(manifests, warnings + materialized.warnings, sourceHash)
            }
        }
    } finally {
        // coroutineScope waits for every worker before removing this task's files.
        withContext(NonCancellable + workers.dispatcher) { cacheDir.deleteRecursively() }
    }

    private fun sanitizeFileName(value: String): String =
        value.codePoints().toArray().joinToString("") { cp ->
            String(Character.toChars(cp)).takeIf { Character.isLetterOrDigit(it.codePointAt(0)) || cp == 0x5F || cp == 0x2D } ?: "e"
        }.take(24).ifBlank { "emoji" }

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
            .filter { it.sourceType == "telegram" && it.telegramShortName.equals(shortName, ignoreCase = true) && it.identifier !in keep }
            .forEach { PackRepository.delete(context, it.identifier) }
    }

    class CancelledException : CancellationException("cancelled")
    class ImportFailure(message: String) : Exception(message)
}
