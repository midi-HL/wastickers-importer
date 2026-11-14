package io.github.midihl.wastickersimporter

import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import android.widget.TextView

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<MaterialToolbar>(R.id.settings_toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val dynamicSwitch = findViewById<MaterialSwitch>(R.id.dynamic_switch)
        dynamicSwitch.isChecked = ThemeSettings.dynamicEnabled(this)
        dynamicSwitch.setOnCheckedChangeListener { _, checked ->
            ThemeSettings.setDynamicEnabled(this, checked)
            UiMotion.snackbar(findViewById(R.id.settings_root), getString(R.string.settings_restart_hint)).show()
        }

        val seedSlider = findViewById<Slider>(R.id.seed_slider)
        seedSlider.value = ThemeSettings.seedIndex(this).toFloat()
        seedSlider.addOnChangeListener { _, value, _ -> ThemeSettings.setSeedIndex(this, value.toInt()) }

        val modes = listOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES
        )
        val radios = listOf(
            findViewById<MaterialRadioButton>(R.id.mode_system),
            findViewById<MaterialRadioButton>(R.id.mode_light),
            findViewById<MaterialRadioButton>(R.id.mode_dark)
        )
        radios[modes.indexOf(ThemeSettings.mode(this)).coerceAtLeast(0)].isChecked = true
        radios.forEachIndexed { index, radio ->
            radio.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    radios.forEachIndexed { other, button -> if (other != index) button.isChecked = false }
                    ThemeSettings.setMode(this, modes[index])
                }
            }
        }

        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME)
    }
}
