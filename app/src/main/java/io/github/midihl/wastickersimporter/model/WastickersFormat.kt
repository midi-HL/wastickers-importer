package io.github.midihl.wastickersimporter.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object WastickersFormat {
    const val MAX_BYTES = 256L * 1024 * 1024

    class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Parse a `.wastickers` ZIP. Pure JVM: no Android types, so unit tests can exercise it. */
    fun parse(input: InputStream, fallbackName: String): ParsedPack {
        val entries = ArrayList<ZipEntryData>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/')
                    if (name.isEmpty() || name.startsWith(".")) continue
                    val bytes = readBounded(zip)
                    total += bytes.size
                    if (total > MAX_BYTES) throw ParseException("ZIP 内容超过 256 MB，文件可能损坏")
                    entries += ZipEntryData(name, bytes)
                    zip.closeEntry()
                }
            }
        } catch (e: ParseException) {
            throw e
        } catch (e: Exception) {
            throw ParseException("不是有效的 .wastickers ZIP 文件，或文件已损坏", e)
        }
        if (entries.isEmpty()) throw ParseException("ZIP 内没有可用的贴纸文件")

        var title: String? = null
        var author: String? = null
        val pngs = ArrayList<ZipEntryData>()
        val images = ArrayList<ZipEntryData>()
        for (entry in entries) {
            val lower = entry.name.lowercase()
            when {
                lower == "title.txt" -> title = firstLine(entry.bytes)
                lower == "author.txt" -> author = firstLine(entry.bytes)
                lower.endsWith(".png") -> pngs += entry
                lower.endsWith(".webp") || lower.endsWith(".png") -> images += entry
            }
        }
        // Preserve ZIP entry order for both WebP and non-tray PNG stickers.
        val tray = chooseTray(pngs)
        val stickerCandidates = entries.filter {
            val lower = it.name.lowercase()
            (lower.endsWith(".webp") || lower.endsWith(".png")) && it !== tray
        }
        if (stickerCandidates.isEmpty()) throw ParseException("包内没有找到 .webp 或 .png 贴纸")

        val name = StickerNames.truncateField(title ?: stripExtension(fallbackName), "Sticker Pack")
        val publisher = StickerNames.truncateField(author ?: "Unknown", "Unknown")
        val stickers = stickerCandidates.map {
            RawSticker(it.name, it.bytes, StickerNames.isAnimatedWebp(it.bytes))
        }
        return ParsedPack(name, publisher, tray?.bytes, stickers)
    }

    private fun readBounded(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var count = 0
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            count += read
            if (count > MAX_BYTES) throw ParseException("单个 ZIP 条目过大，文件可能损坏")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun firstLine(bytes: ByteArray): String =
        bytes.toString(Charsets.UTF_8).lineSequence().firstOrNull()?.trim().orEmpty()

    private fun stripExtension(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return base.substringBeforeLast('.', base).ifBlank { "Sticker Pack" }
    }

    /** Smallest PNG by pixel area wins; ties and failures keep the first ZIP entry. */
    fun chooseTray(pngs: List<ZipEntryData>): ZipEntryData? {
        if (pngs.isEmpty()) return null
        return pngs.minByOrNull { pngSize(it.bytes).let { (w, h) -> w.toLong() * h } } ?: pngs.first()
    }

    private fun pngSize(bytes: ByteArray): Pair<Int, Int> {
        if (bytes.size < 24) return 512 to 512
        return try {
            val stream = ByteArrayInputStream(bytes)
            val header = ByteArray(24)
            if (stream.read(header) != 24) return 512 to 512
            val w = ((header[16].toInt() and 0xff) shl 24) or
                ((header[17].toInt() and 0xff) shl 16) or
                ((header[18].toInt() and 0xff) shl 8) or
                (header[19].toInt() and 0xff)
            val h = ((header[20].toInt() and 0xff) shl 24) or
                ((header[21].toInt() and 0xff) shl 16) or
                ((header[22].toInt() and 0xff) shl 8) or
                (header[23].toInt() and 0xff)
            if (w > 0 && h > 0) w to h else 512 to 512
        } catch (e: Exception) {
            512 to 512
        }
    }

    data class ZipEntryData(val name: String, val bytes: ByteArray)
}
