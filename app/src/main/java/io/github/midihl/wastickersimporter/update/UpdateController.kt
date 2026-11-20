package io.github.midihl.wastickersimporter.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.midihl.wastickersimporter.BaseActivity
import io.github.midihl.wastickersimporter.BuildConfig
import io.github.midihl.wastickersimporter.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Checks are foreground-only. APK downloads are handed to the browser for system installation. */
internal class UpdateController(
    private val owner: BaseActivity,
    private val fetchRelease: suspend () -> AppRelease? = {
        withContext(Dispatchers.IO) { GitHubReleaseClient().latestStable() }
    }
) : DefaultLifecycleObserver {
    private val preferences = owner.getSharedPreferences("app_updates", android.content.Context.MODE_PRIVATE)
    private var request: Job? = null
    private var generation = 0
    private var status: TextView? = null
    private var button: MaterialButton? = null
    private var dialog: androidx.appcompat.app.AlertDialog? = null

    init { owner.lifecycle.addObserver(this) }

    fun bindSettings() {
        status = owner.findViewById(R.id.update_status)
        button = owner.findViewById(R.id.update_check_button)
        val automatic = owner.findViewById<MaterialSwitch>(R.id.update_auto_switch)
        automatic.isChecked = preferences.getBoolean("automatic", true)
        automatic.setOnCheckedChangeListener { _, enabled ->
            preferences.edit().putBoolean("automatic", enabled).apply()
        }
        resetStatus()
        button?.setOnClickListener { check(manual = true) }
    }

    fun checkAutomatically() {
        // Side-by-side development packages must never offer a production APK as their own update.
        if (BuildConfig.APPLICATION_ID != "io.github.midihl.wastickersimporter") return
        if (!preferences.getBoolean("automatic", true)) return
        val now = System.currentTimeMillis()
        val last = preferences.getLong("last_attempt", 0L)
        val interval = if (preferences.getBoolean("last_succeeded", false)) DAY else HOUR
        if (last > 0 && now >= last && now - last < interval) return
        check(manual = false)
    }

    private fun check(manual: Boolean) {
        if (request != null || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        button?.isEnabled = false
        status?.setText(R.string.update_checking)
        preferences.edit().putLong("last_attempt", System.currentTimeMillis())
            .putBoolean("last_succeeded", false).apply()
        val ticket = ++generation
        request = owner.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val release = fetchRelease()
                if (ticket != generation || !canShow()) return@launch
                preferences.edit().putBoolean("last_succeeded", true).apply()
                when {
                    release == null -> status?.setText(R.string.update_no_release)
                    ReleaseVersions.isNewer(release.versionName, BuildConfig.VERSION_NAME) -> {
                        status?.text = owner.getString(R.string.update_available_status, release.versionName)
                        // Dismissing an automatic prompt suppresses that tag until a manual check.
                        if (manual || preferences.getString("prompted_tag", null) != release.tag) {
                            showRelease(release)
                            if (!manual) preferences.edit().putString("prompted_tag", release.tag).apply()
                        }
                    }
                    else -> status?.text = owner.getString(
                        R.string.update_current, BuildConfig.VERSION_NAME, release.versionName)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ticket == generation && canShow() && manual) status?.setText(R.string.update_failed)
            } finally {
                if (ticket == generation) {
                    request = null
                    button?.isEnabled = true
                }
            }
        }
        request?.start()
    }

    private fun showRelease(release: AppRelease) {
        val asset = ReleaseAssets.preferred(release, Build.SUPPORTED_ABIS.toList())
        val description = owner.getString(R.string.update_available_body,
            BuildConfig.VERSION_NAME, release.versionName) +
            (asset?.let { "\n\n" + owner.getString(R.string.update_asset, it.name) } ?: "") +
            release.notes.take(2000).takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
        dialog = MaterialAlertDialogBuilder(owner)
            .setTitle(R.string.update_available_title)
            .setMessage(description)
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_release_page) { _, _ -> open(release.pageUrl) }
            .apply {
                if (asset != null) setPositiveButton(R.string.update_download) { _, _ -> open(asset.downloadUrl) }
            }.create().also { it.show() }
    }

    private fun open(url: String) {
        try {
            owner.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(owner, R.string.update_no_browser, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(owner, R.string.update_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetStatus() {
        status?.text = owner.getString(R.string.update_source, BuildConfig.VERSION_NAME)
    }

    private fun canShow() = !owner.isFinishing && !owner.isDestroyed &&
        owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    override fun onStop(owner: LifecycleOwner) {
        val stopped = request
        generation++
        request = null
        stopped?.cancel()
        button?.isEnabled = true
        dialog?.dismiss()
        dialog = null
        resetStatus()
    }

    companion object {
        private const val HOUR = 60 * 60 * 1000L
        private const val DAY = 24 * HOUR
    }
}
