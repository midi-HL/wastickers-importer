package io.github.midihl.wastickersimporter

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.UUID
import com.google.android.material.R as MaterialR

@RunWith(AndroidJUnit4::class)
class AppearanceUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun returningFromSettingsAppliesWallpaperToggleAndSeedWithoutLosingInput() = withAppearance { fixture ->
        fixture.setAppearance(dynamic = true, seed = 0, night = AppCompatDelegate.MODE_NIGHT_NO)
        var main = fixture.main()
        onMain {
            main.findViewById<BottomNavigationView>(R.id.main_navigation).selectedItemId = R.id.nav_import
            main.findViewById<TextInputEditText>(R.id.telegram_link_input).setText("unsent_sticker_pack")
        }
        val before = primary(main)
        val settings = fixture.settings(main)
        onMain {
            val toggle = settings.findViewById<MaterialSwitch>(R.id.dynamic_switch)
            if (toggle.isChecked) toggle.performClick()
            assertFalse(toggle.isChecked)
            assertFalse(ThemeSettings.dynamicEnabled(settings))
            val slider = settings.findViewById<Slider>(R.id.seed_slider)
            assertTrue(slider.isEnabled)
            repeat(3) {
                val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                assertTrue(slider.onKeyDown(down.keyCode, down))
                slider.onKeyUp(up.keyCode, up)
            }
            assertEquals(3, ThemeSettings.seedIndex(settings))
            settings.finish()
        }
        val previous = main
        main = fixture.awaitMainReplacement(previous)
        onMain {
            assertFalse(ThemeSettings.dynamicEnabled(main))
            assertEquals(3, ThemeSettings.seedIndex(main))
            assertEquals(R.id.nav_import, main.findViewById<BottomNavigationView>(R.id.main_navigation).selectedItemId)
            assertEquals("unsent_sticker_pack", main.findViewById<TextInputEditText>(R.id.telegram_link_input).text.toString())
            assertEquals(ContextCompat.getColor(main, R.color.seed3_primary), primary(main))
            assertEquals(ContextCompat.getColor(main, R.color.seed3_primary_container),
                main.findViewById<BottomNavigationView>(R.id.main_navigation).itemActiveIndicatorColor!!.defaultColor)
            assertEquals(ContextCompat.getColor(main, R.color.seed3_primary_container),
                main.findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.open_file_fab)
                    .backgroundTintList!!.defaultColor)
        }
        assertNotEquals(before, primary(main))
    }

    @Test
    fun wallpaperPaletteMatchesSystemAndCanBeRestoredAfterASeedPalette() = withAppearance { fixture ->
        if (!ThemeSettings.isDynamicColorSupported()) return@withAppearance
        fixture.setAppearance(dynamic = false, seed = 2, night = AppCompatDelegate.MODE_NIGHT_YES)
        var main = fixture.main()
        val settings = fixture.settings(main)
        onMain {
            val toggle = settings.findViewById<MaterialSwitch>(R.id.dynamic_switch)
            toggle.performClick()
            assertTrue(toggle.isChecked)
            assertTrue(ThemeSettings.dynamicEnabled(settings))
            assertFalse(settings.findViewById<Slider>(R.id.seed_slider).isEnabled)
            settings.finish()
        }
        main = fixture.awaitMainReplacement(main)
        onMain {
            assertTrue(ThemeSettings.dynamicEnabled(main))
            assertEquals(Configuration.UI_MODE_NIGHT_YES,
                main.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            val systemOverlay = android.view.ContextThemeWrapper(main, MaterialR.style.ThemeOverlay_Material3_DynamicColors_Dark)
            assertEquals(MaterialColors.getColor(systemOverlay, MaterialR.attr.colorPrimary, 0), primary(main))
            if (Build.VERSION.SDK_INT >= 34) {
                assertEquals(ContextCompat.getColor(main, android.R.color.system_primary_dark), primary(main))
            }
        }
    }

    @Test
    fun initialDarkAndLightSeedPalettesAndLocalizedNamesAreConsistent() = withAppearance { fixture ->
        for (night in listOf(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)) {
            fixture.setAppearance(dynamic = false, seed = 4, night = night)
            val main = fixture.main()
            onMain {
                assertEquals(ContextCompat.getColor(main, R.color.seed4_primary), primary(main))
                assertEquals(if (night == AppCompatDelegate.MODE_NIGHT_YES) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                    main.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                for ((locale, expected) in listOf(Locale.SIMPLIFIED_CHINESE to "贴纸工坊", Locale.ENGLISH to "Sticker Studio")) {
                    val config = Configuration(main.resources.configuration).apply { setLocale(locale) }
                    assertEquals(expected, main.createConfigurationContext(config).getString(R.string.app_name))
                }
                main.finish()
            }
            await("Main should finish before changing appearance") { main.isDestroyed }
        }
    }

    @Test
    fun monochromeFaceAndToolbarSymbolsKeepTransparentDetailsWhenTinted() = withAppearance { fixture ->
        fixture.setAppearance(dynamic = false, seed = 0, night = AppCompatDelegate.MODE_NIGHT_NO)
        val main = fixture.main()
        onMain {
            fun render(id: Int, side: Int): Bitmap {
                val drawable = requireNotNull(AppCompatResources.getDrawable(main, id)).mutate()
                drawable.setTint(Color.CYAN)
                drawable.setBounds(0, 0, side, side)
                return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { drawable.draw(Canvas(it)) }
            }
            val mono = render(R.drawable.ic_launcher_monochrome, 216)
            try {
                assertEquals(0, Color.alpha(mono.getPixel(86, 92)))
                assertEquals(0, Color.alpha(mono.getPixel(120, 92)))
                assertEquals(0, Color.alpha(mono.getPixel(103, 118)))
                assertTrue(Color.alpha(mono.getPixel(103, 78)) > 240)
                assertEquals(Color.CYAN, mono.getPixel(103, 78))
                val pixels = IntArray(216 * 216)
                mono.getPixels(pixels, 0, 216, 0, 0, 216, 216)
                for (y in 0 until 216) for (x in 0 until 216) {
                    if (Color.alpha(pixels[y * 216 + x]) > 32) {
                        val dx = x / 2f - 54f
                        val dy = y / 2f - 54f
                        assertTrue("Logo must stay inside the adaptive safe circle", dx * dx + dy * dy <= 33f * 33f)
                    }
                }
            } finally { mono.recycle() }
            for (id in listOf(R.drawable.ic_settings, R.drawable.ic_empty_sticker)) {
                val icon = render(id, 96)
                try {
                    val emptyPixelY = if (id == R.drawable.ic_settings) 48 else 32
                    assertEquals("Outline interior must not become a solid block", 0, Color.alpha(icon.getPixel(48, emptyPixelY)))
                    val pixels = IntArray(96 * 96)
                    icon.getPixels(pixels, 0, 96, 0, 0, 96, 96)
                    val coverage = pixels.count { Color.alpha(it) > 64 }.toFloat() / pixels.size
                    assertTrue("Symbol needs visible strokes and a hollow interior: $coverage", coverage in .05f.. .45f)
                } finally { icon.recycle() }
            }
            val icon = ContextCompat.getDrawable(main, R.mipmap.ic_launcher)
            if (Build.VERSION.SDK_INT >= 26) assertTrue(icon is AdaptiveIconDrawable)
        }
    }

    private fun primary(activity: Activity): Int {
        var result = 0
        onMain { result = MaterialColors.getColor(activity, MaterialR.attr.colorPrimary, 0) }
        return result
    }

    private inner class AppearanceFixture {
        private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        private val oldValues = prefs.all.toMap()
        private val oldNight = AppCompatDelegate.getDefaultNightMode()
        private val token = UUID.randomUUID().toString()
        private val activities = mutableListOf<Activity>()
        private val application = context.applicationContext as Application
        private val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity.intent.getStringExtra("appearance_fixture") == token) activities += activity
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

        fun install() = onMain { application.registerActivityLifecycleCallbacks(callback) }

        fun setAppearance(dynamic: Boolean, seed: Int, night: Int) = onMain {
            ThemeSettings.setDynamicEnabled(context, dynamic)
            ThemeSettings.setSeedIndex(context, seed)
            ThemeSettings.setMode(context, night)
        }

        fun main(): MainActivity = (instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra("appearance_fixture", token)
        ) as MainActivity).also { instrumentation.waitForIdleSync() }

        fun settings(main: MainActivity): SettingsActivity {
            val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
            return try {
                onMain { main.startActivity(Intent(main, SettingsActivity::class.java).putExtra("appearance_fixture", token)) }
                (instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? SettingsActivity
                    ?: throw AssertionError("Settings should open")).also { instrumentation.waitForIdleSync() }
            } finally { instrumentation.removeMonitor(monitor) }
        }

        fun awaitMainReplacement(old: MainActivity): MainActivity {
            var replacement: MainActivity? = null
            await("Returning from settings must recreate the stale palette") {
                onMain { replacement = activities.filterIsInstance<MainActivity>().lastOrNull { it !== old && !it.isDestroyed } }
                old.isDestroyed && replacement != null
            }
            instrumentation.waitForIdleSync()
            return requireNotNull(replacement)
        }

        fun close() {
            try {
                onMain { activities.asReversed().filterNot { it.isDestroyed }.forEach { it.finish() } }
                await("Appearance fixture activities should close") { activities.all { it.isDestroyed } }
            } finally {
                onMain {
                    application.unregisterActivityLifecycleCallbacks(callback)
                    val editor = prefs.edit()
                    for (key in listOf("dynamic_color", "seed_color", "night_mode")) {
                        when (val value = oldValues[key]) {
                            null -> editor.remove(key)
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            else -> throw AssertionError("Unexpected appearance preference type")
                        }
                    }
                    assertTrue(editor.commit())
                    AppCompatDelegate.setDefaultNightMode(oldNight)
                }
            }
        }
    }

    private fun withAppearance(block: (AppearanceFixture) -> Unit) {
        val fixture = AppearanceFixture()
        fixture.install()
        try { block(fixture) } finally { fixture.close() }
    }

    private fun onMain(action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action()
        else instrumentation.runOnMainSync(action)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
        } while (SystemClock.uptimeMillis() < end)
        throw AssertionError(message)
    }
}
