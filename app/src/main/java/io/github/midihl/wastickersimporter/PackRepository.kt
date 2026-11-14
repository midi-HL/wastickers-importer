package io.github.midihl.wastickersimporter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import io.github.midihl.wastickersimporter.model.PackManifest
import io.github.midihl.wastickersimporter.model.ParsedPack
import io.github.midihl.wastickersimporter.model.RawSticker
import io.github.midihl.wastickersimporter.model.StickerEntry
import io.github.midihl.wastickersimporter.model.StickerNames
import io.github.midihl.wastickersimporter.model.WastickersFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Materializes packs into internal storage. Every pack directory owns a `manifest.json`;
 * there is no database and the source `.wastickers` URI is never needed after import.
 */
object PackRepository {
    private const val MANIFEST = "manifest.json"
    private const val TRAY = "tray.png"
    private const val STICKER_LIMIT = 30
    private const val MIN_STICKERS = 3
    private const val STATIC_LIMIT = 100 * 1024
    private const val ANIMATED_LIMIT = 500 * 1024
    private const val TRAY_LIMIT = 50 * 1024

    class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class ImportResult(val manifests: List<PackManifest>, val warnings: List<String>)

    fun packsRoot(context: Context): File = File(context.filesDir, "packs").apply { mkdirs() }

    fun packDir(context: Context, identifier: String): File = File(packsRoot(context), identifier)

    fun manifests(context: Context): List<PackManifest> = packsRoot(context)
        .listFiles().orEmpty()
        .filter { it.isDirectory && File(it, MANIFEST).isFile }
        .mapNotNull { runCatching { readManifest(it) }.getOrNull() }
        .sortedBy { it.name }

    fun get(context: Context, identifier: String): PackManifest? =
        manifests(context).firstOrNull { it.identifier == identifier }

    fun updateMetadata(context: Context, identifier: String, name: String, publisher: String): PackManifest? {
        val dir = packDir(context, identifier)
        val manifest = runCatching { readManifest(dir) }.getOrNull() ?: return null
        manifest.name = StickerNames.truncateField(name, manifest.name)
        manifest.publisher = StickerNames.truncateField(publisher, "Unknown")
        writeManifest(dir, manifest)
        return manifest
    }

    fun delete(context: Context, identifier: String): Boolean {
        val dir = packDir(context, identifier)
        return dir.exists() && dir.deleteRecursively()
    }

    /** Copy source URI to internal storage first, then parse and convert from the stable copy. */
    fun importFromUri(context: Context, uri: android.net.Uri, suggestedName: String): ImportResult {
        val cache = File(context.cacheDir, "incoming.wastickers")
        context.contentResolver.openInputStream(uri)?.use { input: InputStream ->
            FileOutputStream(cache).use { output -> input.copyTo(output) }
        } ?: throw ImportException("无法读取所选文件，请重新选择或换用系统分享")
        try {
            val parsed = try {
                WastickersFormat.parse(cache.inputStream(), suggestedName)
            } catch (e: WastickersFormat.ParseException) {
                throw ImportException(e.message ?: "解析失败", e)
            }
            return materialize(context, parsed, cache.readBytes())
        } finally {
            cache.delete()
        }
    }

    private fun materialize(context: Context, parsed: ParsedPack, sourceBytes: ByteArray): ImportResult {
        val sourceHash = StickerNames.shortHash(sourceBytes)
        val warnings = mutableListOf<String>()
        val trayBytes = makeTray(parsed)
        val animated = parsed.stickers.any { it.isAnimatedWebp }
        val converted = ArrayList<Pair<String, ByteArray>>()
        parsed.stickers.forEachIndexed { index, raw ->
            val result = normalizeSticker(context, raw, animated, index + 1, warnings)
            if (result != null) converted += result
        }
        if (converted.size < MIN_STICKERS) {
            throw ImportException("有效贴纸不足 3 张（当前 ${converted.size} 张），WhatsApp 要求每包至少 3 张")
        }
        val chunks = converted.chunked(STICKER_LIMIT)
        val totalParts = chunks.size
        if (totalParts > 1) warnings += context.getString(R.string.import_split_notice, converted.size, totalParts)
        val manifests = chunks.mapIndexed { part, chunk ->
            val suffix = if (totalParts > 1) "-part${part + 1}of$totalParts" else ""
            val name = if (totalParts > 1) "${parsed.name} (${part + 1}/$totalParts)" else parsed.name
            val identifier = StickerNames.identifier(parsed.name, sourceHash, suffix)
            val dir = packDir(context, identifier)
            if (!dir.exists() && !dir.mkdirs()) throw ImportException("无法创建贴纸包目录")
            val stickers = ArrayList<StickerEntry>()
            for ((i, pair) in chunk.withIndex()) {
                val fileName = String.format("sticker_%03d.webp", i + 1)
                File(dir, fileName).writeBytes(pair.second)
                stickers += StickerEntry(fileName, StickerNames.emojisFromFileName(pair.first), "", pair.second.size.toLong())
            }
            File(dir, TRAY).writeBytes(trayBytes)
            val manifest = PackManifest(
                identifier = identifier,
                name = StickerNames.truncateField(name, "Sticker Pack"),
                publisher = StickerNames.truncateField(parsed.publisher, "Unknown"),
                trayImageFile = TRAY,
                imageDataVersion = "1",
                animatedStickerPack = animated,
                stickers = stickers,
                sourceHash = sourceHash
            )
            writeManifest(dir, manifest)
            manifest
        }
        return ImportResult(manifests, warnings)
    }

    /** Static fallback conversion: exactly 512x512 WebP, <=100 KB; animated WebP passes through. */
    private fun normalizeSticker(
        context: Context,
        raw: RawSticker,
        animatedPack: Boolean,
        position: Int,
        warnings: MutableList<String>
    ): Pair<String, ByteArray>? {
        if (animatedPack) {
            if (!raw.isAnimatedWebp) {
                warnings += context.getString(R.string.import_mixed_animated_skip, position)
                return null
            }
            if (raw.bytes.size > ANIMATED_LIMIT) {
                throw ImportException(context.getString(R.string.import_animated_too_large, position, raw.bytes.size / 1024))
            }
            return raw.originalName to raw.bytes
        }
        if (raw.isAnimatedWebp) {
            warnings += context.getString(R.string.import_mixed_animated_skip, position)
            return null
        }
        if (isCompliantWebp(raw.bytes)) return raw.originalName to raw.bytes
        val bitmap = BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size)
            ?: run {
                warnings += context.getString(R.string.import_decode_skip, position)
                return null
            }
        val normalized = if (bitmap.width == 512 && bitmap.height == 512) bitmap else letterbox(bitmap)
        val bytes = compressWebp(normalized)
        if (normalized !== bitmap) normalized.recycle()
        bitmap.recycle()
        if (bytes == null || bytes.size > STATIC_LIMIT) {
            warnings += context.getString(R.string.import_static_too_large, position, (bytes?.size ?: 0) / 1024)
            return null
        }
        return raw.originalName to bytes
    }

    private fun isCompliantWebp(bytes: ByteArray): Boolean {
        if (bytes.size > STATIC_LIMIT || bytes.size < 20) return false
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return false
        if (String(bytes, 8, 4, Charsets.US_ASCII) != "WEBP") return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth == 512 && options.outHeight == 512
    }

    private fun letterbox(source: Bitmap): Bitmap {
        val scale = minOf(512f / source.width, 512f / source.height)
        val w = maxOf(1, (source.width * scale).toInt())
        val h = maxOf(1, (source.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val target = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.TRANSPARENT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(scaled, (512 - w) / 2f, (512 - h) / 2f, paint)
        if (scaled !== source) scaled.recycle()
        return target
    }

    private fun compressWebp(bitmap: Bitmap): ByteArray? {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        for (quality in listOf(90, 80, 70, 60, 50, 40, 30)) {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(format, quality, out)) return null
            if (out.size() <= STATIC_LIMIT) return out.toByteArray()
        }
        return null
    }

    private fun makeTray(parsed: ParsedPack): ByteArray {
        val source = parsed.trayIconBytes ?: parsed.stickers.firstOrNull()?.bytes
            ?: throw ImportException("包内没有可用图片生成托盘图标")
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size)
            ?: throw ImportException("托盘图标或首张贴纸无法解码")
        val scale = minOf(96f / bitmap.width, 96f / bitmap.height)
        val w = maxOf(1, (bitmap.width * scale).toInt())
        val h = maxOf(1, (bitmap.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val tray = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tray)
        canvas.drawColor(Color.TRANSPARENT)
        canvas.drawBitmap(scaled, (96 - w) / 2f, (96 - h) / 2f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val out = ByteArrayOutputStream()
        tray.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (out.size() > TRAY_LIMIT) {
            val reduced = Bitmap.createScaledBitmap(tray, 64, 64, true)
            val small = ByteArrayOutputStream()
            reduced.compress(Bitmap.CompressFormat.PNG, 100, small)
            return small.toByteArray()
        }
        return out.toByteArray()
    }

    private fun readManifest(dir: File): PackManifest {
        val json = JSONObject(File(dir, MANIFEST).readText())
        val stickers = json.getJSONArray("stickers")
        val list = (0 until stickers.length()).map { i ->
            val s = stickers.getJSONObject(i)
            val emojis = s.optJSONArray("emojis") ?: JSONArray()
            StickerEntry(
                s.getString("image_file"),
                (0 until emojis.length()).map { emojis.getString(it) }.ifEmpty { listOf("😀") },
                s.optString("accessibility_text", ""),
                s.optLong("size_bytes", 0)
            )
        }
        return PackManifest(
            identifier = json.getString("identifier"),
            name = json.getString("name"),
            publisher = json.getString("publisher"),
            trayImageFile = json.getString("tray_image_file"),
            imageDataVersion = json.optString("image_data_version", "1"),
            avoidCache = json.optBoolean("avoid_cache", false),
            animatedStickerPack = json.optBoolean("animated_sticker_pack", false),
            stickers = list,
            sourceHash = json.optString("source_hash", "")
        )
    }

    private fun writeManifest(dir: File, manifest: PackManifest) {
        val stickers = JSONArray()
        manifest.stickers.forEach {
            stickers.put(
                JSONObject()
                    .put("image_file", it.imageFileName)
                    .put("emojis", JSONArray(it.emojis))
                    .put("accessibility_text", it.accessibilityText)
                    .put("size_bytes", it.sizeBytes)
            )
        }
        val json = JSONObject()
            .put("identifier", manifest.identifier)
            .put("name", manifest.name)
            .put("publisher", manifest.publisher)
            .put("tray_image_file", manifest.trayImageFile)
            .put("image_data_version", manifest.imageDataVersion)
            .put("avoid_cache", manifest.avoidCache)
            .put("animated_sticker_pack", manifest.animatedStickerPack)
            .put("stickers", stickers)
            .put("source_hash", manifest.sourceHash)
        File(dir, MANIFEST).writeText(json.toString())
    }
}
