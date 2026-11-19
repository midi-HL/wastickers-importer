package io.github.midihl.wastickersimporter

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.google.android.material.R as MaterialR

object ThemeSettings {
    private const val PREFS = "appearance"
    private const val KEY_DYNAMIC = "dynamic_color"
    private const val KEY_SEED = "seed_color"
    private const val KEY_MODE = "night_mode"

    private val lightPalettes = intArrayOf(
        R.style.SeedOverlay_0_Light, R.style.SeedOverlay_1_Light, R.style.SeedOverlay_2_Light,
        R.style.SeedOverlay_3_Light, R.style.SeedOverlay_4_Light, R.style.SeedOverlay_5_Light
    )
    private val darkPalettes = intArrayOf(
        R.style.SeedOverlay_0_Dark, R.style.SeedOverlay_1_Dark, R.style.SeedOverlay_2_Dark,
        R.style.SeedOverlay_3_Dark, R.style.SeedOverlay_4_Dark, R.style.SeedOverlay_5_Dark
    )

    fun dynamicEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DYNAMIC, true)

    fun setDynamicEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DYNAMIC, enabled).apply()
    }

    fun seedIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SEED, 0).coerceIn(lightPalettes.indices)

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

    fun isDynamicColorSupported(): Boolean = DynamicColors.isDynamicColorAvailable()

    internal data class Palette(@StyleRes val overlay: Int, val systemColors: List<Int> = emptyList())

    private val systemColorRoles = intArrayOf(
        MaterialR.attr.colorPrimary, MaterialR.attr.colorPrimaryContainer,
        MaterialR.attr.colorOnPrimaryContainer, MaterialR.attr.colorSecondaryContainer,
        MaterialR.attr.colorSurface, MaterialR.attr.colorOnSurface, MaterialR.attr.colorSurfaceContainer
    ).apply { sort() }

    internal fun palette(context: Context): Palette {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val dark = nightMode == Configuration.UI_MODE_NIGHT_YES
        if (!dynamicEnabled(context) || !isDynamicColorSupported()) {
            val palettes = if (dark) darkPalettes else lightPalettes
            return Palette(palettes[seedIndex(context)])
        }
        val overlay = if (dark) MaterialR.style.ThemeOverlay_Material3_DynamicColors_Dark
        else MaterialR.style.ThemeOverlay_Material3_DynamicColors_Light
        // A wallpaper update can change the resolved colors without changing the overlay ID.
        val colors = ContextThemeWrapper(context, overlay).obtainStyledAttributes(systemColorRoles)
        return try {
            Palette(overlay, systemColorRoles.indices.map { colors.getColor(it, 0) })
        } finally {
            colors.recycle()
        }
    }
}
