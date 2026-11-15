package io.github.midihl.wastickersimporter.telegram

import android.content.Context
import android.net.Uri
import io.github.midihl.wastickersimporter.PackRepository
import io.github.midihl.wastickersimporter.model.PackManifest
import java.io.File
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
        writeFile(zip, dir, manifest.trayImageFile)
        manifest.stickers.forEach { writeFile(zip, dir, it.imageFileName) }
    }

    fun exportToCache(context: Context, manifest: PackManifest): File {
        val file = File(context.cacheDir, "${safeName(manifest.name)}.wastickers")
        ZipOutputStream(file.outputStream()).use { zip -> writeEntries(zip, context, manifest) }
        return file
    }

    private fun writeFile(zip: ZipOutputStream, dir: File, name: String) {
        val source = File(dir, name)
        if (!source.isFile) return
        zip.putNextEntry(ZipEntry(name))
        source.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "stickers" }
}
