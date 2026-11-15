package io.github.midihl.wastickersimporter.telegram

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** T2: EncryptedSharedPreferences backed by Android Keystore. Token never leaves this device. */
object TelegramTokenStore {
    private const val FILE = "telegram_credentials"
    private const val KEY_TOKEN = "bot_token"
    private const val KEY_BOT_NAME = "bot_username"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun token(context: Context): String = prefs(context).getString(KEY_TOKEN, "").orEmpty()
    fun hasToken(context: Context): Boolean = token(context).isNotBlank()
    fun save(context: Context, token: String, botName: String = "") {
        prefs(context).edit().putString(KEY_TOKEN, token.trim()).putString(KEY_BOT_NAME, botName).apply()
    }
    fun botName(context: Context): String = prefs(context).getString(KEY_BOT_NAME, "").orEmpty()
    fun clear(context: Context) = prefs(context).edit().clear().apply()
}
