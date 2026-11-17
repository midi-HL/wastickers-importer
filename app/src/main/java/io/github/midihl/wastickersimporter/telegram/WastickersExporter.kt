package io.github.midihl.wastickersimporter.telegram

import android.content.Context
import android.net.Uri
import io.github.midihl.wastickersimporter.PackRepository
import io.github.midihl.wastickersimporter.model.PackManifest
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** T6: export any stored manifest as a v1-compatible .wastickers ZIP. No token is ever written. */
object WastickersExporter {
    fun exportToUri(context: Context, manifest: PackManifest, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output).use { zip -> writeEntries(zip, context, manifest) }
        } ?: throw IllegalStateException("cannot open export destination")
    }

    private fun writeEntries(zip: ZipOutputStream, context: Context, manifest: PackManifest) {
        val dir = PackRepository.packDir(context, manifest.identifier)
        zip.putNextEntry(ZipEntry("title.txt"))
        zip.write(manifest.name.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("author.txt"))
        zip.write(manifest.publisher.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        writeFile(zip, dir, manifest.trayImageFile, "tray.png")
        manifest.stickers.forEachIndexed { index, sticker ->
            val entryName = String.format(Locale.ROOT, "sticker_%03d_%s.webp", index + 1, sticker.emojis.joinToString(""))
            writeFile(zip, dir, sticker.imageFileName, entryName)
        }
    }

    fun exportToCache(context: Context, manifest: PackManifest): File {
        val file = File(context.cacheDir, "${safeName(manifest.name)}.wastickers")
        ZipOutputStream(file.outputStream()).use { zip -> writeEntries(zip, context, manifest) }
        return file
    }

    private fun writeFile(zip: ZipOutputStream, dir: File, name: String, entryName: String) {
        val source = File(dir, name)
        if (!source.isFile) throw FileNotFoundException("导出资源不存在：$name")
        zip.putNextEntry(ZipEntry(entryName))
        source.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "stickers" }
}
