package io.github.midihl.wastickersimporter

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.midihl.wastickersimporter.model.PackManifest
import io.github.midihl.wastickersimporter.model.StickerNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

object TelegramExporter {
    private const val ACTION = "org.telegram.messenger.CREATE_STICKER_PACK"
    private const val EXTRA_EMOJIS = "STICKER_EMOJIS"
    private const val EXTRA_IMPORTER = "IMPORTER"
    private const val STATIC_LIMIT = 512 * 1024L
    private const val CACHE_LIFETIME_MS = 24 * 60 * 60 * 1000L
    private val preparingActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    fun launch(activity: Activity, pack: PackManifest) {
        if (!canShow(activity) || activity in preparingActivities) return
        if (pack.animatedStickerPack) {
            if (pack.sourceType == "telegram" && pack.telegramShortName.isNotBlank()) {
                openOriginalPack(activity, pack.telegramShortName)
            } else {
                showError(activity, activity.getString(R.string.telegram_export_animated_unsupported))
            }
            return
        }
        val owner = activity as? LifecycleOwner
        if (owner == null) {
            showError(activity, activity.getString(R.string.telegram_export_failed))
            return
        }
        val progress = LinearLayout(activity).apply {
            gravity = android.view.Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(ProgressBar(activity))
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.add_to_telegram)
            .setMessage(R.string.telegram_export_preparing)
            .setView(progress)
            .setCancelable(false)
            .show()
        preparingActivities.add(activity)
        var preparation: Job? = null
        var handedOff = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (!handedOff) preparation?.cancel()
                dialog.dismiss()
            }
        }
        owner.lifecycle.addObserver(observer)
        preparation = owner.lifecycleScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { prepareIntent(activity.applicationContext, pack) }
                if (canShow(activity)) {
                    try {
                        activity.startActivity(intent)
                        handedOff = true
                    } catch (_: ActivityNotFoundException) {
                        showError(activity, activity.getString(R.string.telegram_export_no_receiver))
                    } catch (_: SecurityException) {
                        showError(activity, activity.getString(R.string.telegram_export_cannot_open))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ExportException) {
                showError(activity, failure.message.orEmpty())
            } catch (_: Exception) {
                showError(activity, activity.getString(R.string.telegram_export_failed))
            } finally {
                owner.lifecycle.removeObserver(observer)
                preparingActivities.remove(activity)
                dialog.dismiss()
            }
        }
    }

    private fun openOriginalPack(activity: Activity, shortName: String) {
        if (!shortName.matches(Regex("[A-Za-z0-9_]+"))) {
            showError(activity, activity.getString(R.string.telegram_export_invalid_link))
            return
        }
        val uri = Uri.Builder().scheme("https").authority("t.me")
            .appendPath("addstickers").appendPath(shortName).build()
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            showError(activity, activity.getString(R.string.telegram_export_no_link_receiver))
        } catch (_: SecurityException) {
            showError(activity, activity.getString(R.string.telegram_export_cannot_open))
        }
    }

    private suspend fun prepareIntent(context: Context, pack: PackManifest): Intent {
        if (pack.stickers.isEmpty()) throw ExportException(context.getString(R.string.telegram_export_empty))
        val packDir = PackRepository.packDir(context, pack.identifier).canonicalFile
        val packsRoot = PackRepository.packsRoot(context).canonicalFile
        if (packDir.parentFile != packsRoot) throw IOException("Invalid pack directory")
        val root = File(context.cacheDir, "sticker_share").apply {
            if (!isDirectory && !mkdirs()) throw IOException("Cannot create sharing directory")
        }
        val oldest = System.currentTimeMillis() - CACHE_LIFETIME_MS
        root.listFiles()?.filter { it.isDirectory && it.lastModified() < oldest }?.forEach {
            currentCoroutineContext().ensureActive()
            it.deleteRecursively()
        }
        val shareDir = File(root, UUID.randomUUID().toString())
        if (!shareDir.mkdir()) throw IOException("Cannot create sharing session")
        try {
            val uris = ArrayList<Uri>(pack.stickers.size)
            val emojis = ArrayList<String>(pack.stickers.size)
            pack.stickers.forEachIndexed { index, sticker ->
                currentCoroutineContext().ensureActive()
                val source = File(packDir, sticker.imageFileName).canonicalFile
                if (source.parentFile != packDir || !source.isFile) {
                    throw ExportException(context.getString(R.string.telegram_export_missing_file, index + 1))
                }
                if (source.length() !in 1..STATIC_LIMIT) {
                    throw ExportException(context.getString(R.string.telegram_export_too_large, index + 1))
                }
                val bytes = source.readBytes()
                val webp = bytes.size >= 21 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
                    bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII))
                val png = bytes.size >= 8 && bytes.copyOfRange(0, 8)
                    .contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
                if (webp && StickerNames.isAnimatedWebp(bytes)) {
                    throw ExportException(context.getString(R.string.telegram_export_animated_unsupported))
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if ((!webp && !png) || bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512 ||
                    maxOf(bounds.outWidth, bounds.outHeight) != 512
                ) {
                    throw ExportException(context.getString(R.string.telegram_export_invalid_image, index + 1))
                }
                val target = File(shareDir, "sticker_${index + 1}.${if (webp) "webp" else "png"}")
                target.writeBytes(bytes)
                uris += FileProvider.getUriForFile(context, "${context.packageName}.sharefiles", target)
                emojis += sticker.emojis.firstOrNull { it.isNotBlank() } ?: DEFAULT_EMOJI
            }
            // 官方 Android 示例使用 EXTRA_STREAM 和 STICKER_EMOJIS，两个列表按位置对应。
            // https://github.com/DrKLO/TelegramStickersImport/blob/main/app/src/main/java/org/telegram/stickersimportexample/MainActivity.java
            return Intent(ACTION).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putStringArrayListExtra(EXTRA_EMOJIS, emojis)
                putExtra(EXTRA_IMPORTER, context.packageName)
                clipData = ClipData.newUri(context.contentResolver, pack.name, uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // 完成的共享副本保留至少 24 小时，切换到 Telegram 不撤销权限或删除文件。
                shareDir.setLastModified(System.currentTimeMillis())
            }
        } catch (failure: Exception) {
            shareDir.deleteRecursively()
            throw failure
        }
    }

    private fun canShow(activity: Activity): Boolean =
        !activity.isFinishing && !activity.isDestroyed &&
            ((activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false)

    private fun showError(activity: Activity, message: String) {
        if (!canShow(activity)) return
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.add_to_telegram)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        val owner = activity as? LifecycleOwner ?: return
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) dialog.dismiss()
        }
        dialog.setOnDismissListener { owner.lifecycle.removeObserver(observer) }
        owner.lifecycle.addObserver(observer)
    }

    private class ExportException(message: String) : Exception(message)
    private const val DEFAULT_EMOJI = "\uD83D\uDE00"
}
