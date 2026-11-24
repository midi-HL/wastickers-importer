package io.github.midihl.wastickersimporter.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/** T4 TGS: gzip Lottie JSON -> LottieDrawable逐帧 -> animated WebP. */
object TgsToWebpConverter {
    fun convert(context: Context, tgsBytes: ByteArray, cancelled: () -> Boolean = { false }): ByteArray? {
        val jsonBytes = GZIPInputStream(ByteArrayInputStream(tgsBytes)).use { it.readBytes() }
        val result = LottieCompositionFactory.fromInputStreamSync(
            context, ByteArrayInputStream(jsonBytes), null
        )
        val composition: LottieComposition = result.value ?: return null
        val fps = composition.frameRate.coerceAtLeast(1f).coerceAtMost(60f)
        val start = composition.startFrame.roundToInt()
        val end = composition.endFrame.roundToInt().coerceAtLeast(start + 1)
        val drawable = LottieDrawable().apply {
            setComposition(composition)
            setBounds(0, 0, 512, 512)
        }
        val frames = ArrayList<AnimatedWebpEncoder.Frame>(((end - start) * 30 / fps).toInt().coerceAtMost(90) + 1)
        val step = (fps / 30f).coerceAtLeast(1f)
        try {
        var frame = start.toFloat()
        while (frame < end && frames.size < 90) {
            if (cancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            frames += AnimatedWebpEncoder.Frame(bitmap, (1000f / 30f).toInt())
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)
            drawable.setFrame(frame.roundToInt())
            drawable.draw(canvas)
            frame += step
        }
        return AnimatedWebpEncoder.encodeWithinLimit(frames, cancelled = cancelled)?.takeIf { it.size <= AnimatedWebpEncoder.MAX_BYTES }
        } finally {
            frames.forEach { it.bitmap.recycle() }
            drawable.clearComposition()
        }
    }
}
