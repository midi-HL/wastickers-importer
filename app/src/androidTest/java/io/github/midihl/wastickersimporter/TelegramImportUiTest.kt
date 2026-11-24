package io.github.midihl.wastickersimporter

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import io.github.midihl.wastickersimporter.telegram.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TelegramImportUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val imports get() = (context.applicationContext as ImporterApplication).telegramImports
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(25) }
        throw AssertionError("Timed out waiting for import UI")
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("Missing screenshot")
        try { File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun threadsSliderDefaultsToTwoAndPersistsTheMaximumAcrossSettingsInstances() {
        val prefs = context.getSharedPreferences("telegram_import", android.content.Context.MODE_PRIVATE)
        val had = prefs.contains("threads")
        val old = prefs.getInt("threads", 2)
        val activities = mutableListOf<Activity>()
        try {
            onMain { prefs.edit().clear().commit(); imports.workers.resize(TelegramImportSettings.threads(context)) }
            val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            activities += activity
            instrumentation.waitForIdleSync()
            onMain {
                val slider = activity.findViewById<Slider>(R.id.import_threads_slider)
                assertEquals(2f, slider.value, 0f)
                assertEquals(1f, slider.valueFrom, 0f)
                assertEquals(6f, slider.valueTo, 0f)
                val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 6f) }
                assertTrue(slider.accessibilityNodeProvider.performAction(0, AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
                assertEquals(6, imports.workers.threadCount)
                assertEquals(6, TelegramImportSettings.threads(context))
                val card = (slider.parent as View).parent as View
                var parent = card.parent
                while (parent !is androidx.core.widget.NestedScrollView) parent = parent.parent
                parent.scrollTo(0, (card.top - 8 * activity.resources.displayMetrics.density).toInt())
            }
            instrumentation.waitForIdleSync()
            screenshot("import-settings-six-threads")
            val second = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as SettingsActivity
            activities += second
            onMain { assertEquals(6f, second.findViewById<Slider>(R.id.import_threads_slider).value, 0f) }
        } finally {
            onMain {
                activities.asReversed().forEach { it.finish() }
                if (had) prefs.edit().putInt("threads", old).commit() else prefs.edit().remove("threads").commit()
                imports.workers.resize(TelegramImportSettings.threads(context))
            }
        }
    }

    @Test fun multipleTaskCardsKeepInputEnabledAndCancelSeparatelyAcrossRecreation() {
        val first = CompletableDeferred<TelegramImportCoordinator.Result>()
        val second = CompletableDeferred<TelegramImportCoordinator.Result>()
        val activities = mutableListOf<Activity>()
        try {
            onMain {
                imports.queue.clearFinished()
                imports.queue.add("Design_Stickers") { progress -> progress(ImportProgress(4, 12, ImportStage.CONVERT)); first.await() }
                imports.queue.add("Motion_Stickers") { progress -> progress(ImportProgress(2, 9, ImportStage.DOWNLOAD)); second.await() }
            }
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activities += activity
            onMain { activity.findViewById<BottomNavigationView>(R.id.main_navigation).selectedItemId = R.id.nav_import }
            instrumentation.waitForIdleSync()
            val list = activity.findViewById<RecyclerView>(R.id.telegram_tasks)
            onMain {
                assertEquals(2, list.adapter!!.itemCount)
                assertTrue(activity.findViewById<View>(R.id.telegram_link_input).isEnabled)
                assertTrue(activity.findViewById<View>(R.id.telegram_fetch_button).isEnabled)
                activity.findViewById<android.widget.ScrollView>(R.id.import_page).fullScroll(View.FOCUS_DOWN)
            }
            instrumentation.waitForIdleSync()
            screenshot("import-multiple-active-tasks")
            onMain { assertTrue(list.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<MaterialButton>(R.id.task_action).performClick()) }
            await { imports.queue.tasks.value.first().state == ImportTaskState.CANCELLED }
            assertEquals(ImportTaskState.RUNNING, imports.queue.tasks.value.last().state)
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                onMain { activity.recreate() }
                val recreated = instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as MainActivity
                activities += recreated
                instrumentation.waitForIdleSync()
                onMain {
                    val recreatedList = recreated.findViewById<RecyclerView>(R.id.telegram_tasks)
                    assertEquals(2, recreatedList.adapter!!.itemCount)
                    assertEquals(recreated.getString(R.string.telegram_task_retry),
                        recreatedList.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<TextView>(R.id.task_action).text)
                    assertTrue(recreated.findViewById<View>(R.id.telegram_fetch_button).isEnabled)
                }
            } finally { instrumentation.removeMonitor(monitor) }
        } finally {
            first.cancel(); second.cancel()
            onMain { imports.queue.tasks.value.filter { it.active }.forEach { imports.queue.cancel(it.id) }; activities.asReversed().filterNot { it.isDestroyed }.forEach { it.finish() } }
            await { !imports.queue.hasActiveTasks }
            onMain { imports.queue.clearFinished() }
        }
    }
}
