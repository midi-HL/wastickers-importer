package io.github.midihl.wastickersimporter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import io.github.midihl.wastickersimporter.model.PackManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lightweight first-frame decoder; animated WebP previews show frame 1, as the spec allows. */
object ImageLoaders {
    private val cache = mutableMapOf<String, Bitmap>()

    fun loadSticker(image: ImageView, context: android.content.Context, pack: PackManifest, fileName: String) {
        val key = "${pack.identifier}/$fileName"
        cache[key]?.let { image.setImageBitmap(it); return }
        CoroutineScope(Dispatchers.Main).launch {
            val bitmap = withContext(Dispatchers.IO) {
                val file = File(PackRepository.packDir(context.applicationContext, pack.identifier), fileName)
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(file.absolutePath, options)
            }
            if (bitmap != null) {
                cache[key] = bitmap
                image.setImageBitmap(bitmap)
            } else {
                image.setImageResource(R.drawable.ic_sticker_placeholder)
            }
        }
    }

    fun clear() = cache.clear()
}
