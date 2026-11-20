package io.github.midihl.wastickersimporter

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.midihl.wastickersimporter.update.AppRelease
import io.github.midihl.wastickersimporter.update.ReleaseAsset
import io.github.midihl.wastickersimporter.update.UpdateController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class SettingsUpdateUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun updateCardPrecedesLanguageAndRemembersOptOut() = withSettings { fixture ->
        var activity = fixture.launch()
        onMain {
            val toggle = activity.findViewById<MaterialSwitch>(R.id.update_auto_switch)
            val language = activity.findViewById<MaterialButton>(R.id.language_button)
            val toggleBounds = Rect(); val languageBounds = Rect()
            toggle.getGlobalVisibleRect(toggleBounds); language.getGlobalVisibleRect(languageBounds)
            assertTrue(toggle.isChecked)
            assertTrue(toggleBounds.height() >= 48 * activity.resources.displayMetrics.density)
            assertTrue(toggleBounds.bottom < languageBounds.top)
            toggle.performClick()
            assertFalse(toggle.isChecked)
            assertFalse(fixture.preferences.getBoolean("automatic", true))
        }
        val original = activity
        onMain { original.recreate() }
        await("Settings should recreate") { fixture.activities.any { it !== original && !it.isDestroyed } && original.isDestroyed }
        activity = fixture.activities.last() as SettingsActivity
        instrumentation.waitForIdleSync()
        onMain { assertFalse(activity.findViewById<MaterialSwitch>(R.id.update_auto_switch).isChecked) }
    }

    @Test
    fun aboutRowsOpenAuthorRepositoryAndCommunityHttpsLinks() = withSettings { fixture ->
        val activity = fixture.launch()
        val outgoing = mutableListOf<Intent>()
        val monitor = browserMonitor(outgoing)
        try {
            val expected = listOf(
                Triple(R.id.about_author, "github.com/midi-HL", "https://github.com/midi-HL"),
                Triple(R.id.about_repository, "midi-HL/wastickers-importer", "https://github.com/midi-HL/wastickers-importer"),
                Triple(R.id.about_group, "t.me/sticker_studio_android", "https://t.me/sticker_studio_android")
            )
            for ((id, label, url) in expected) {
                onMain {
                    val row = activity.findViewById<TextView>(id)
                    assertTrue(row.text.toString().contains(label))
                    assertTrue(row.isClickable && row.isFocusable)
                    row.performClick()
                }
                assertEquals(url, outgoing.last().dataString)
                assertEquals(Intent.ACTION_VIEW, outgoing.last().action)
                assertTrue(outgoing.last().hasCategory(Intent.CATEGORY_BROWSABLE))
            }
            assertEquals(3, outgoing.size)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test
    fun oldReleaseAndMissingReleaseNeverOfferADowngrade() = withSettings { fixture ->
        val activity = fixture.launch()
        onMain { UpdateController(activity) { release("1.0.0") }.bindSettings(); checkButton(activity).performClick() }
        await("Old release should finish checking") { status(activity) == activity.getString(R.string.update_current, BuildConfig.VERSION_NAME, "1.0.0") }
        assertFalse(hasText(activity.getString(R.string.update_available_title)))
        onMain { UpdateController(activity) { null }.bindSettings(); checkButton(activity).performClick() }
        await("No-release status should be visible") { status(activity) == activity.getString(R.string.update_no_release) }
        onMain { assertTrue(checkButton(activity).isEnabled) }
    }

    @Test
    fun newerReleaseOffersTheMatchingApkAndOpensItsDownload() = withSettings { fixture ->
        val activity = fixture.launch()
        val newest = release("999.0.0")
        val outgoing = mutableListOf<Intent>()
        val monitor = browserMonitor(outgoing)
        try {
            onMain { UpdateController(activity) { newest }.bindSettings(); checkButton(activity).performClick() }
            await("Update dialog should appear") { hasText(activity.getString(R.string.update_download)) }
            val expected = newest.assets.first()
            assertTrue(hasText(expected.name))
            clickText(activity.getString(R.string.update_download))
            await("Download should leave through the browser") { outgoing.isNotEmpty() }
            assertEquals(expected.downloadUrl, outgoing.single().dataString)
            assertTrue(outgoing.single().hasCategory(Intent.CATEGORY_BROWSABLE))
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test
    fun repeatedClicksShareOneRequestAndFailureAllowsRetry() = withSettings { fixture ->
        val activity = fixture.launch()
        val pending = CompletableDeferred<AppRelease?>()
        var calls = 0
        try {
            onMain {
                UpdateController(activity) { if (++calls == 1) pending.await() else release("1.0.0") }.bindSettings()
                checkButton(activity).performClick(); checkButton(activity).performClick()
            }
            assertEquals(1, calls)
            onMain { assertFalse(checkButton(activity).isEnabled) }
            pending.completeExceptionally(IOException("offline fixture"))
            await("Failure must restore a retry") { status(activity) == activity.getString(R.string.update_failed) }
            onMain { assertTrue(checkButton(activity).isEnabled); checkButton(activity).performClick() }
            await("Retry should finish") { status(activity).contains("1.0.0") }
            assertEquals(2, calls)
        } finally { pending.cancel() }
    }

    @Test
    fun stoppingASlowRequestAllowsRetryAndLateResultKeepsTheNewRequestIntact() = withSettings { fixture ->
        val activity = fixture.launch()
        val slow = CompletableDeferred<AppRelease?>()
        val retry = CompletableDeferred<AppRelease?>()
        var calls = 0
        try {
            onMain {
                UpdateController(activity) {
                    if (++calls == 1) withContext(NonCancellable) { slow.await() } else retry.await()
                }.bindSettings()
                checkButton(activity).performClick()
            }
            val covering = fixture.launch()
            await("First settings should stop") { !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            onMain { assertTrue(checkButton(activity).isEnabled); covering.finish() }
            await("First settings should resume") { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            onMain { checkButton(activity).performClick() }
            assertEquals(2, calls)
            slow.complete(release("999.0.0"))
            instrumentation.waitForIdleSync()
            onMain { assertFalse(checkButton(activity).isEnabled) }
            assertEquals(activity.getString(R.string.update_checking), status(activity))
            assertFalse(hasText(activity.getString(R.string.update_available_title)))
            retry.complete(release("1.0.0"))
            await("Current retry must complete normally") { status(activity).contains("1.0.0") }
        } finally { slow.cancel(); retry.cancel() }
    }

    private fun release(version: String): AppRelease {
        val abi = Build.SUPPORTED_ABIS.first()
        val url = "https://github.com/midi-HL/wastickers-importer/releases/download/v$version/"
        return AppRelease("v$version", version, "https://github.com/midi-HL/wastickers-importer/releases/tag/v$version", "Update fixture",
            listOf(ReleaseAsset("app-$abi-release.apk", url + "app-$abi-release.apk", 100),
                ReleaseAsset("app-universal-release.apk", url + "app-universal-release.apk", 200)))
    }

    private fun checkButton(activity: Activity) = activity.findViewById<MaterialButton>(R.id.update_check_button)
    private fun status(activity: Activity): String {
        var value = ""
        onMain { value = activity.findViewById<TextView>(R.id.update_status).text.toString() }
        return value
    }
    private fun browserMonitor(outgoing: MutableList<Intent>): Instrumentation.ActivityMonitor {
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                outgoing += Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        return monitor
    }
    private fun hasText(text: String): Boolean = instrumentation.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByText(text)?.isNotEmpty() == true
    private fun clickText(text: String) {
        val node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
            ?: throw AssertionError("Missing action: $text")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private inner class Fixture {
        val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        private val old = preferences.all.toMap()
        val activities = mutableListOf<Activity>()
        private val token = UUID.randomUUID().toString()
        private val application = context.applicationContext as Application
        private val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity.intent.getStringExtra("updates_fixture") == token) activities += activity
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        fun install() = onMain {
            application.registerActivityLifecycleCallbacks(callback)
            preferences.edit().clear().commit()
        }
        fun launch() = (instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra("updates_fixture", token)) as SettingsActivity).also { instrumentation.waitForIdleSync() }
        fun close() {
            onMain { activities.asReversed().filterNot { it.isDestroyed }.forEach { it.finish() } }
            await("Fixture activities should finish") { activities.all { it.isDestroyed } }
            onMain {
                application.unregisterActivityLifecycleCallbacks(callback)
                val edit = preferences.edit().clear()
                for ((key, value) in old) when (value) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Long -> edit.putLong(key, value)
                    is String -> edit.putString(key, value)
                }
                assertTrue(edit.commit())
            }
        }
    }
    private fun withSettings(action: (Fixture) -> Unit) {
        val fixture = Fixture(); fixture.install()
        try { action(fixture) } finally { fixture.close() }
    }
    private fun onMain(action: () -> Unit) { instrumentation.runOnMainSync(action) }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }
}
