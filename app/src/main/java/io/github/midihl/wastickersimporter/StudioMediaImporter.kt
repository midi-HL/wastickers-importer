package io.github.midihl.wastickersimporter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import io.github.midihl.wastickersimporter.convert.AnimatedWebpEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object StudioMediaImporter {
    private const val MAX_INPUT_BYTES = 100L * 1024L * 1024L
    private const val MAX_FRAMES = 45
    private const val FPS = 15

    data class ImportedMedia(val path: String, val animated: Boolean)

    suspend fun import(
        context: Context,
        uri: Uri,
        directory: File,
        startSeconds: Float = 0f,
        durationSeconds: Float = 3f
    ): ImportedMedia = withContext(Dispatchers.IO) {
        require(startSeconds.isFinite() && startSeconds >= 0f) { context.getString(R.string.media_invalid_trim) }
        require(durationSeconds.isFinite() && durationSeconds > 0f) { context.getString(R.string.media_invalid_trim) }
        require(directory.exists() || directory.mkdirs()) { context.getString(R.string.media_conversion_failed) }

        val cacheDir = File(context.cacheDir, "studio-media-${UUID.randomUUID()}")
        check(cacheDir.mkdirs()) { context.getString(R.string.media_conversion_failed) }
        val source = File(cacheDir, "source")
        var resultFile: File? = null
        try {
            copyInput(context, uri, source)
            val mime = mediaType(context, uri)
            if (mime == "image/webp" && isAnimatedWebp(source)) {
                require(source.length() <= AnimatedWebpEncoder.MAX_BYTES && is512Webp(source)) {
                    context.getString(R.string.media_animated_webp_unsupported)
                }
                currentCoroutineContext().ensureActive()
                val output = File(directory, "${UUID.randomUUID()}.webp")
                resultFile = output
                copyFileWithCancellation(source, output)
                ImportedMedia(output.absolutePath, true)
            } else if (mime.startsWith("video/") || mime == "image/gif") {
                val bytes = convertAnimated(source, cacheDir, startSeconds, durationSeconds)
                    ?: throw IllegalArgumentException(context.getString(R.string.media_conversion_failed))
                currentCoroutineContext().ensureActive()
                val output = File(directory, "${UUID.randomUUID()}.webp")
                resultFile = output
                output.writeBytes(bytes)
                check(output.length() <= AnimatedWebpEncoder.MAX_BYTES) {
                    context.getString(R.string.media_conversion_failed)
                }
                ImportedMedia(output.absolutePath, true)
            } else if (mime.startsWith("image/")) {
                val bitmap = decodeStatic(source)
                    ?: throw IllegalArgumentException(context.getString(R.string.media_unsupported))
                val output = File(directory, "${UUID.randomUUID()}.png")
                resultFile = output
                try {
                    FileOutputStream(output).use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    }
                } finally {
                    bitmap.recycle()
                }
                ImportedMedia(output.absolutePath, false)
            } else {
                throw IllegalArgumentException(context.getString(R.string.media_unsupported))
            }
        } catch (error: CancellationException) {
            resultFile?.delete()
            throw error
        } catch (error: Throwable) {
            resultFile?.delete()
            throw error
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private suspend fun copyFileWithCancellation(source: File, destination: File) {
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun mediaType(context: Context, uri: Uri): String {
        if (uri.scheme != "file") return context.contentResolver.getType(uri)?.lowercase().orEmpty()
        return when (File(uri.path.orEmpty()).extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "mov" -> "video/quicktime"
            else -> ""
        }
    }

    private suspend fun copyInput(context: Context, uri: Uri, destination: File) {
        val declaredSize = if (uri.scheme == "file") {
            File(uri.path.orEmpty()).length()
        } else {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L } ?: -1L
        }
        require(declaredSize <= MAX_INPUT_BYTES) { context.getString(R.string.media_too_large) }
        val input = if (uri.scheme == "file") {
            File(uri.path.orEmpty()).inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
        } ?: throw IllegalArgumentException(context.getString(R.string.media_unsupported))
        input.use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_INPUT_BYTES) { context.getString(R.string.media_too_large) }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun is512Webp(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth == 512 && bounds.outHeight == 512
    }

    private fun decodeStatic(source: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val oriented = try {
            val orientation = ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            orientationMatrix(orientation)?.let { matrix ->
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } ?: decoded
        } catch (_: Exception) {
            decoded
        }
        val scale = minOf(512f / oriented.width, 512f / oriented.height)
        val width = (oriented.width * scale).toInt().coerceAtLeast(1)
        val height = (oriented.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(oriented, width, height, true)
        val output = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.TRANSPARENT)
            drawBitmap(scaled, (512 - width) / 2f, (512 - height) / 2f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        if (scaled !== oriented) scaled.recycle()
        oriented.recycle()
        return output
    }

    private fun orientationMatrix(orientation: Int): Matrix? = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            else -> return null
        }
    }

    private fun isAnimatedWebp(file: File): Boolean {
        val header = ByteArray(21)
        file.inputStream().use { if (it.read(header) < header.size) return false }
        return header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" &&
            header.copyOfRange(12, 16).toString(Charsets.US_ASCII) == "VP8X" &&
            (header[20].toInt() and 0x02) != 0
    }

    private suspend fun convertAnimated(source: File, workDir: File, start: Float, duration: Float): ByteArray? {
        val pattern = File(workDir, "frame_%03d.png")
        val session = FFmpegKit.executeWithArguments(arrayOf(
            "-y", "-ss", start.toString(), "-t", duration.coerceAtMost(3f).toString(), "-i", source.absolutePath,
            "-an", "-vf", "fps=$FPS,scale=512:512:force_original_aspect_ratio=decrease,pad=512:512:(ow-iw)/2:(oh-ih)/2:color=0x00000000,format=rgba",
            "-frames:v", MAX_FRAMES.toString(), "-start_number", "0", pattern.absolutePath
        ))
        currentCoroutineContext().ensureActive()
        if (!ReturnCode.isSuccess(session.returnCode)) return null
        val files = workDir.listFiles()?.filter { it.name.startsWith("frame_") && it.extension == "png" }
            ?.sortedBy { it.name }.orEmpty().take(MAX_FRAMES)
        if (files.isEmpty()) return null
        val frames = ArrayList<AnimatedWebpEncoder.Frame>(files.size)
        try {
            for (file in files) {
                currentCoroutineContext().ensureActive()
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                if (options.outWidth != 512 || options.outHeight != 512) continue
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                frames += AnimatedWebpEncoder.Frame(bitmap, 1000 / FPS)
            }
            currentCoroutineContext().ensureActive()
            if (frames.isEmpty()) return null
            return AnimatedWebpEncoder.encodeWithinLimit(frames)
                ?.takeIf { it.size <= AnimatedWebpEncoder.MAX_BYTES }
        } finally {
            frames.forEach { it.bitmap.recycle() }
            files.forEach { it.delete() }
        }
    }
}
