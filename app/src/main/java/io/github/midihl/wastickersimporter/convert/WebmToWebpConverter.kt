package io.github.midihl.wastickersimporter.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/** T4 WebM: FFmpeg-Kit LGPL decodes VP9 alpha frames to PNG, then mux to animated WebP. */
object WebmToWebpConverter {
    private const val FPS = 30

    fun convert(source: File, workDir: File): ByteArray? {
        val pattern = File(workDir, "frame_%03d.png")
        pattern.parentFile?.mkdirs()
        workDir.listFiles()?.filter { it.name.startsWith("frame_") && it.name.endsWith(".png") }?.forEach { it.delete() }
        val command = arrayOf(
            "-y", "-i", source.absolutePath,
            "-vf", "fps=$FPS,scale=512:512:force_original_aspect_ratio=decrease,pad=512:512:(ow-iw)/2:(oh-ih)/2:color=0x00000000,format=rgba",
            "-start_number", "0", pattern.absolutePath
        )
        val session = FFmpegKit.executeWithArguments(command)
        if (!ReturnCode.isSuccess(session.returnCode)) return null
        val images = workDir.listFiles()
            ?.filter { it.name.startsWith("frame_") && it.name.endsWith(".png") }
            ?.sortedBy { it.name }.orEmpty()
        if (images.isEmpty()) return null
        val frames = ArrayList<AnimatedWebpEncoder.Frame>()
        for (file in images.take(90)) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
            frames += AnimatedWebpEncoder.Frame(bitmap, 1000 / FPS)
        }
        val encoded = AnimatedWebpEncoder.encodeWithinLimit(frames)
        frames.forEach { it.bitmap.recycle() }
        images.forEach { it.delete() }
        return encoded?.takeIf { it.size <= AnimatedWebpEncoder.MAX_BYTES }
    }
}
