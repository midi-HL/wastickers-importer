package io.github.midihl.wastickersimporter

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

object ThemeSettings {
    private const val PREFS = "appearance"
    private const val KEY_DYNAMIC = "dynamic_color"
    private const val KEY_SEED = "seed_color"
    private const val KEY_MODE = "night_mode"

    val SEEDS = intArrayOf(
        0xFF6750A4.toInt(), 0xFF3F6375.toInt(), 0xFF9C4146.toInt(),
        0xFF356A3E.toInt(), 0xFF8A5A18.toInt(), 0xFF4C4B8C.toInt()
    )

    fun dynamicEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DYNAMIC, true)

    fun setDynamicEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DYNAMIC, enabled).apply()
    }

    fun seedIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SEED, 0).coerceIn(0, SEEDS.size - 1)

    fun setSeedIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SEED, index).apply()
    }

    fun mode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(mode(context))
    }

    /** Called before any Activity is created; dynamic color follows the system wallpaper. */
    fun applyToApplication(app: ImporterApplication) {
        val options = DynamicColorsOptions.Builder()
            .setPrecondition { _, _ -> dynamicEnabled(app) && isDynamicColorSupported() }
            .build()
        DynamicColors.applyToActivitiesIfAvailable(app, options)
    }

    /** Applies a pre-generated full MD3 palette when wallpaper colors are unavailable/disabled. */
    fun applySeedToActivity(activity: Activity) {
        if (dynamicEnabled(activity) && isDynamicColorSupported()) return
        val dark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val style = when (seedIndex(activity)) {
            0 -> if (dark) R.style.SeedOverlay_0_Dark else R.style.SeedOverlay_0_Light
            1 -> if (dark) R.style.SeedOverlay_1_Dark else R.style.SeedOverlay_1_Light
            2 -> if (dark) R.style.SeedOverlay_2_Dark else R.style.SeedOverlay_2_Light
            3 -> if (dark) R.style.SeedOverlay_3_Dark else R.style.SeedOverlay_3_Light
            4 -> if (dark) R.style.SeedOverlay_4_Dark else R.style.SeedOverlay_4_Light
            else -> if (dark) R.style.SeedOverlay_5_Dark else R.style.SeedOverlay_5_Light
        }
        activity.theme.applyStyle(style, true)
    }

    private fun isDynamicColorSupported(): Boolean = DynamicColors.isDynamicColorAvailable()
}
