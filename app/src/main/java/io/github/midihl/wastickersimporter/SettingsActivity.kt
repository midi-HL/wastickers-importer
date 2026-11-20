package io.github.midihl.wastickersimporter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import io.github.midihl.wastickersimporter.telegram.TelegramApiException
import io.github.midihl.wastickersimporter.telegram.TelegramBotClient
import io.github.midihl.wastickersimporter.telegram.TelegramTokenStore
import io.github.midihl.wastickersimporter.update.UpdateController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root)) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val toolbar = findViewById<MaterialToolbar>(R.id.settings_toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        UpdateController(this).bindSettings()
        bindAboutLink(R.id.about_author, "https://github.com/midi-HL")
        bindAboutLink(R.id.about_repository, "https://github.com/midi-HL/wastickers-importer")
        bindAboutLink(R.id.about_group, "https://t.me/sticker_studio_android")

        val languageButton = findViewById<MaterialButton>(R.id.language_button)
        val languageLabels = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_chinese),
            getString(R.string.language_english)
        )
        val languageTags = arrayOf("", "zh-CN", "en")
        val currentLocale = AppCompatDelegate.getApplicationLocales()[0]
        val selectedLanguage = when (currentLocale?.language) {
            "zh" -> 1
            "en" -> 2
            else -> 0
        }
        languageButton.text = languageLabels[selectedLanguage]
        languageButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(languageLabels, selectedLanguage) { dialog, which ->
                    dialog.dismiss()
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(languageTags[which])
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        val dynamicSwitch = findViewById<MaterialSwitch>(R.id.dynamic_switch)
        val seedSlider = findViewById<Slider>(R.id.seed_slider)
        val supportsWallpaperColors = ThemeSettings.isDynamicColorSupported()
        dynamicSwitch.isEnabled = supportsWallpaperColors
        dynamicSwitch.isChecked = supportsWallpaperColors && ThemeSettings.dynamicEnabled(this)
        if (!supportsWallpaperColors) {
            findViewById<TextView>(R.id.dynamic_color_summary).setText(R.string.settings_dynamic_unavailable)
            findViewById<TextView>(R.id.seed_color_summary).setText(R.string.settings_seed_available)
        }
        seedSlider.value = ThemeSettings.seedIndex(this).toFloat()
        seedSlider.isEnabled = !dynamicSwitch.isChecked
        dynamicSwitch.setOnCheckedChangeListener { _, checked ->
            ThemeSettings.setDynamicEnabled(this, checked)
            seedSlider.isEnabled = !checked
        }
        seedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) ThemeSettings.setSeedIndex(this, value.toInt())
        }

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

        val tokenInput = findViewById<TextInputEditText>(R.id.telegram_token_input)
        val status = findViewById<TextView>(R.id.telegram_bot_status)
        tokenInput.setText(TelegramTokenStore.token(this))
        status.text = TelegramTokenStore.botName(this).takeIf { it.isNotBlank() }
            ?.let { getString(R.string.telegram_bot_verified, it) }
            ?: getString(R.string.telegram_bot_not_verified)

        findViewById<MaterialButton>(R.id.telegram_save_button).setOnClickListener {
            val token = tokenInput.text?.toString()?.trim().orEmpty()
            if (token.isBlank()) {
                status.text = getString(R.string.telegram_token_empty)
                return@setOnClickListener
            }
            status.text = getString(R.string.telegram_testing)
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { TelegramBotClient(token).getMe() }
                }
                result.fold(
                    onSuccess = { info ->
                        TelegramTokenStore.save(this@SettingsActivity, token, info.username)
                        status.text = getString(R.string.telegram_bot_verified, "@${info.username}")
                        snack(getString(R.string.telegram_token_saved))
                    },
                    onFailure = { error ->
                        status.text = when (error) {
                            is TelegramApiException -> if (error.code == 401) {
                                getString(R.string.telegram_token_invalid)
                            } else getString(R.string.telegram_network_error, error.description)
                            else -> getString(R.string.telegram_network_error, error.message.orEmpty())
                        }
                        MaterialAlertDialogBuilder(this@SettingsActivity)
                            .setTitle(R.string.error_title)
                            .setMessage(status.text)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                )
            }
        }

        findViewById<MaterialButton>(R.id.telegram_clear_button).setOnClickListener {
            TelegramTokenStore.clear(this)
            tokenInput.setText("")
            status.text = getString(R.string.telegram_token_cleared)
        }

        findViewById<TextView>(R.id.about_version).text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
    }

    private fun snack(message: String) =
        Snackbar.make(findViewById(R.id.settings_root), message, Snackbar.LENGTH_LONG).show()

    private fun bindAboutLink(id: Int, url: String) {
        findViewById<TextView>(id).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) {
                snack(getString(R.string.about_link_unavailable))
            } catch (_: SecurityException) {
                snack(getString(R.string.about_link_unavailable))
            }
        }
    }
}
