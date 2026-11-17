package io.github.midihl.wastickersimporter

import android.graphics.*
import android.media.ExifInterface
import java.io.File
import kotlin.math.*

object StickerImageTools {
    const val SIZE = 512
    fun empty(): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    fun copy(source: Bitmap): Bitmap = source.copy(Bitmap.Config.ARGB_8888, true)

    fun load(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
        val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(-90f)
        }
        val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val result = fit(oriented)
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
        return result
    }

    fun fit(source: Bitmap): Bitmap {
        val result = empty()
        val scale = min(SIZE.toFloat() / source.width, SIZE.toFloat() / source.height)
        val w = source.width * scale; val h = source.height * scale
        Canvas(result).drawBitmap(source, null, RectF((SIZE-w)/2, (SIZE-h)/2, (SIZE+w)/2, (SIZE+h)/2), Paint(3))
        return result
    }

    fun crop(source: Bitmap, path: Path, bounds: RectF): Bitmap {
        val masked = empty()
        val canvas = Canvas(masked)
        canvas.drawPath(path, Paint(3).apply { color = Color.BLACK })
        canvas.drawBitmap(source, 0f, 0f, Paint(3).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
        val left = bounds.left.toInt().coerceIn(0, 511); val top = bounds.top.toInt().coerceIn(0, 511)
        val right = ceil(bounds.right).toInt().coerceIn(left+1, 512)
        val bottom = ceil(bounds.bottom).toInt().coerceIn(top+1, 512)
        val cut = Bitmap.createBitmap(masked, left, top, right-left, bottom-top)
        val result = fit(cut)
        if (cut !== masked) cut.recycle()
        masked.recycle()
        return result
    }

    fun removeConnected(source: Bitmap, x: Int, y: Int, tolerance: Int): Bitmap {
        val result = copy(source)
        val pixels = IntArray(SIZE * SIZE); result.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val seed = y.coerceIn(0,511)*SIZE+x.coerceIn(0,511)
        val target = pixels[seed]
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size); var read = 0; var write = 0
        queue[write++] = seed; visited[seed] = true
        while (read < write) {
            val index = queue[read++]; val c = pixels[index]
            if (maxOf(abs(Color.red(c)-Color.red(target)), abs(Color.green(c)-Color.green(target)), abs(Color.blue(c)-Color.blue(target)), abs(Color.alpha(c)-Color.alpha(target))) > tolerance) continue
            pixels[index] = Color.TRANSPARENT
            fun push(n: Int) { if (!visited[n]) { visited[n] = true; queue[write++] = n } }
            if (index % SIZE > 0) push(index-1)
            if (index % SIZE < SIZE-1) push(index+1)
            if (index >= SIZE) push(index-SIZE)
            if (index < pixels.size-SIZE) push(index+SIZE)
        }
        result.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        return result
    }

    fun transform(source: Bitmap, angle: Float, mirrorX: Boolean, mirrorY: Boolean, scale: Float): Bitmap {
        val result = empty()
        Canvas(result).apply {
            translate(256f,256f); rotate(angle)
            scale(scale * if (mirrorX) -1 else 1, scale * if (mirrorY) -1 else 1)
            drawBitmap(source,-256f,-256f,Paint(3))
        }
        return result
    }

    fun adjust(source: Bitmap, brightness: Float, saturation: Float): Bitmap {
        val result = empty()
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        matrix.postConcat(ColorMatrix(floatArrayOf(1f,0f,0f,0f,brightness, 0f,1f,0f,0f,brightness, 0f,0f,1f,0f,brightness, 0f,0f,0f,1f,0f)))
        Canvas(result).drawBitmap(source,0f,0f,Paint(3).apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return result
    }

    fun outline(source: Bitmap, width: Float, color: Int): Bitmap {
        val result = empty(); val canvas = Canvas(result)
        if (width > 0f) {
            val alpha = source.extractAlpha()
            val paint = Paint(3).apply { this.color = color }
            for (radius in 1..ceil(width).toInt()) {
                for (step in 0 until 48) {
                    val angle = step * Math.PI / 24
                    canvas.drawBitmap(alpha,(cos(angle)*radius).toFloat(),(sin(angle)*radius).toFloat(),paint)
                }
            }
            alpha.recycle()
        }
        canvas.drawBitmap(source,0f,0f,Paint(3))
        return result
    }

    fun writePng(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true); temporary.delete()
        }
    }
}
