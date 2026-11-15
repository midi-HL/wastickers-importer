package io.github.midihl.wastickersimporter.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import java.io.ByteArrayOutputStream

object StaticImageNormalizer {
    const val MAX_BYTES = 100 * 1024

    fun normalize(bytes: ByteArray): ByteArray? {
        if (isCompliantWebp(bytes)) return bytes
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val normalized = if (source.width == 512 && source.height == 512) source else letterbox(source)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        var result: ByteArray? = null
        for (quality in listOf(90, 80, 70, 60, 50, 40, 30)) {
            val out = ByteArrayOutputStream()
            if (!normalized.compress(format, quality, out)) break
            if (out.size() <= MAX_BYTES) { result = out.toByteArray(); break }
        }
        if (normalized !== source) normalized.recycle()
        source.recycle()
        return result
    }

    fun isCompliantWebp(bytes: ByteArray): Boolean {
        if (bytes.size > MAX_BYTES || bytes.size < 20) return false
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
        Canvas(target).apply {
            drawColor(Color.TRANSPARENT)
            drawBitmap(scaled, (512 - w) / 2f, (512 - h) / 2f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        if (scaled !== source) scaled.recycle()
        return target
    }
}
