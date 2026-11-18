package io.github.midihl.wastickersimporter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import io.github.midihl.wastickersimporter.model.PackManifest
import java.io.File
import java.util.concurrent.Executors

/** Bounded first-frame thumbnails. A recycled cell can never receive another file's result. */
object ImageLoaders {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt().coerceIn(2 * 1024 * 1024, 8 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        // ImageViews may still own an evicted bitmap; let the GC release it after they detach.
    }
    private val decoder = Executors.newFixedThreadPool(2)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val inFlight = mutableMapOf<String, MutableList<java.lang.ref.WeakReference<ImageView>>>()
    @Volatile private var generation = 0

    fun loadSticker(image: ImageView, context: android.content.Context, pack: PackManifest, fileName: String) {
        val file = File(PackRepository.packDir(context.applicationContext, pack.identifier), fileName)
        loadFile(image, file, "${pack.identifier}/${pack.imageDataVersion}/$fileName")
    }

    fun loadFile(image: ImageView, file: File, version: String = file.absolutePath) {
        val epoch = generation
        val key = "$epoch/$version"
        image.tag = key
        val cached = cache.get(key)
        if (cached != null) { image.setImageBitmap(cached); return }
        image.setImageResource(R.drawable.ic_sticker_placeholder)
        val target = java.lang.ref.WeakReference(image)
        val shouldDecode = synchronized(inFlight) {
            val targets = inFlight[key]
            if (targets != null) {
                if (targets.none { it.get() === image }) targets += target
                false
            } else {
                inFlight[key] = mutableListOf(target)
                true
            }
        }
        if (!shouldDecode) return
        decoder.execute {
            val bitmap = if (epoch == generation) BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = 2; inPreferredConfig = Bitmap.Config.ARGB_8888
            }) else null
            main.post {
                val targets = synchronized(inFlight) { inFlight.remove(key).orEmpty().mapNotNull { it.get() } }
                if (epoch != generation) {
                    bitmap?.recycle()
                    targets.filter { it.tag == key }.forEach { loadFile(it, file, version) }
                    return@post
                }
                if (bitmap != null) cache.put(key, bitmap)
                if (bitmap != null) targets.filter { it.tag == key }.forEach { it.setImageBitmap(bitmap) }
            }
        }
    }

    fun clear() { generation++; cache.evictAll() }
}
