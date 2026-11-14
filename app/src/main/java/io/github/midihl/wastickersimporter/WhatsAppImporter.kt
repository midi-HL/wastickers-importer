package io.github.midihl.wastickersimporter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContract

/**
 * WhatsApp's ENABLE_STICKER_PACK API. Constants and result handling follow the official
 * AddStickerPackActivity / StickerPackDetailsActivity sample.
 */
object WhatsAppImporter {
    const val CONSUMER_PACKAGE = "com.whatsapp"
    const val BUSINESS_PACKAGE = "com.whatsapp.w4b"
    const val ACTION = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    const val EXTRA_ID = "sticker_pack_id"
    const val EXTRA_AUTHORITY = "sticker_pack_authority"
    const val EXTRA_NAME = "sticker_pack_name"
    const val EXTRA_VALIDATION_ERROR = "validation_error"

    fun installedTargets(context: Context): List<String> {
        val pm = context.packageManager
        return listOf(CONSUMER_PACKAGE, BUSINESS_PACKAGE).filter {
            runCatching { pm.getApplicationInfo(it, 0).enabled }.getOrDefault(false)
        }
    }

    fun intent(identifier: String, name: String): Intent = Intent(ACTION).apply {
        putExtra(EXTRA_ID, identifier)
        putExtra(EXTRA_AUTHORITY, BuildConfig.CONTENT_PROVIDER_AUTHORITY)
        putExtra(EXTRA_NAME, name)
    }

    /** Human-readable explanation for WhatsApp's terse `validation_error` result. */
    fun describeResult(context: Context, resultCode: Int, data: Intent?): String {
        if (resultCode == Activity.RESULT_OK) return context.getString(R.string.import_result_success)
        if (resultCode != Activity.RESULT_CANCELED) return context.getString(R.string.import_result_unknown)
        val raw = data?.getStringExtra(EXTRA_VALIDATION_ERROR).orEmpty()
        val lower = raw.lowercase()
        val hint = when {
            lower.contains("size") && lower.contains("500") -> R.string.hint_animated_size
            lower.contains("size") -> R.string.hint_static_size
            lower.contains("512") || lower.contains("dimension") -> R.string.hint_dimensions
            lower.contains("3") && lower.contains("30") -> R.string.hint_count
            lower.contains("emoji") -> R.string.hint_emoji
            lower.contains("tray") -> R.string.hint_tray
            lower.contains("webp") -> R.string.hint_webp
            else -> R.string.import_result_cancelled
        }
        val prefix = context.getString(hint)
        return if (raw.isBlank()) prefix else "$prefix\n${context.getString(R.string.import_whatsapp_detail, raw)}"
    }
}

class AddPackContract : ActivityResultContract<Intent, Pair<Int, Intent?>>() {
    override fun createIntent(context: Context, input: Intent): Intent = input
    override fun parseResult(resultCode: Int, intent: Intent?): Pair<Int, Intent?> = resultCode to intent
}
