package io.github.midihl.wastickersimporter.convert

import android.graphics.Bitmap
import com.github.penfeizhou.animation.awebpencoder.WebPEncoder

/**
 * libwebp-compatible animated WebP muxing via APNG4Android's WebPEncoder.
 * Frames are supplied one at a time by the caller; this object never retains a whole pack.
 */
object AnimatedWebpEncoder {
    const val MAX_BYTES = 500 * 1024

    init {
        System.loadLibrary("wastickers_webp_jni")
    }

    @JvmStatic
    private external fun nativeEncode(
        width: Int,
        height: Int,
        quality: Int,
        pixels: IntArray,
        durations: IntArray,
        frameCount: Int
    ): ByteArray?

    data class Frame(val bitmap: Bitmap, val durationMs: Int)

    fun encode(frames: List<Frame>, quality: Int): ByteArray {
        require(frames.isNotEmpty()) { "animated sticker needs at least one frame" }
        val first = frames.first().bitmap
        val frameSize = first.width * first.height
        val pixels = IntArray(frameSize * frames.size)
        val durations = IntArray(frames.size)
        frames.forEachIndexed { index, frame ->
            val bitmap = frame.bitmap
            require(bitmap.width == first.width && bitmap.height == first.height) { "all frames must share dimensions" }
            bitmap.getPixels(pixels, index * frameSize, first.width, 0, 0, first.width, first.height)
            durations[index] = frame.durationMs.coerceAtLeast(8)
        }
        val native = nativeEncode(first.width, first.height, quality, pixels, durations, frames.size)
        if (native != null && native.isNotEmpty()) return native
        val encoder = WebPEncoder().loopCount(0).quality(quality).backgroundColor(0)
        frames.forEach { frame ->
            encoder.addFrame(
                WebPEncoder.FrameBuilder()
                    .bitmap(frame.bitmap)
                    .offsetX(0).offsetY(0)
                    .duration(frame.durationMs.coerceAtLeast(8))
                    .blending(false)
                    .disposal(true)
                    .build()
            )
        }
        return encoder.build()
    }

    /** Quality first, then frame-rate reduction (30 -> 20 -> 15 -> 10 fps) in the coordinator. */
    fun encodeWithinLimit(
        frames: List<Frame>,
        frameRates: IntArray = intArrayOf(30, 20, 15, 10),
        qualities: IntArray = intArrayOf(90, 80, 70, 60, 50, 40)
    ): ByteArray? {
        var last: ByteArray? = null
        for (fps in frameRates) {
            val sampled = sampleFrames(frames, fps)
            for (quality in qualities) {
                val bytes = encode(sampled, quality)
                last = bytes
                if (bytes.size <= MAX_BYTES) return bytes
            }
        }
        return last
    }

    private fun sampleFrames(frames: List<Frame>, fps: Int): List<Frame> {
        if (frames.isEmpty()) return emptyList()
        val sourceFps = 1000.0 / frames.first().durationMs.coerceAtLeast(1)
        if (fps >= sourceFps) return frames
        val step = sourceFps / fps
        val out = ArrayList<Frame>()
        var index = 0.0
        while (index < frames.size) {
            out += Frame(frames[index.toInt()].bitmap, (1000.0 / fps).toInt())
            index += step
        }
        return out
    }
}
