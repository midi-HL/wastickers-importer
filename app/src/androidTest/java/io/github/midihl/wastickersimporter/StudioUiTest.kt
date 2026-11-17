package io.github.midihl.wastickersimporter

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StudioUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun newDraftKeepsBlankStickerAfterEditorCancelAndBack() = withFixture { fixture ->
        val studio = fixture.launch()
        val work = fixture.workspace(studio)
        assertTrue(File(work, "draft.json").isFile)
        assertEquals(0, draft(work).getJSONArray("items").length())

        val monitor = instrumentation.addMonitor(StickerEditorActivity::class.java.name, null, false)
        try {
            clickButton(studio, R.string.studio_text_sticker)
            val editor = instrumentation.waitForMonitorWithTimeout(monitor, TIMEOUT_MS)
                ?: throw AssertionError("文字贴纸应打开编辑器")
            fixture.track(editor)
            onMain {
                editor.setResult(Activity.RESULT_CANCELED)
                editor.finish()
            }
            await("取消编辑器后应回到工作室") { editor.isDestroyed }
        } finally {
            instrumentation.removeMonitor(monitor)
        }

        val item = draft(work).getJSONArray("items").getJSONObject(0)
        val image = File(item.getString("path"))
        assertEquals(work.canonicalFile, image.parentFile!!.canonicalFile)
        assertTrue(image.isFile)
        val originalBytes = image.readBytes()
        setField(studio, R.string.pack_name_hint, "保留的文字草稿")
        back(studio)
        assertTrue(work.isDirectory)
        assertEquals("保留的文字草稿", draft(work).getString("name"))

        val resumed = fixture.launch(draftKey = null)
        assertEquals(work, fixture.workspace(resumed))
        assertEquals("保留的文字草稿", fieldText(resumed, R.string.pack_name_hint))
        assertEquals(1, draft(work).getJSONArray("items").length())
        assertArrayEquals(originalBytes, image.readBytes())
    }

    @Test
    fun existingPackMetadataIsStagedAndDiscardDoesNotChangeLibrary() = withFixture { fixture ->
        val packId = fixture.createPack()
        val manifest = File(PackRepository.packDir(fixture.context, packId), "manifest.json")
        val originalManifest = manifest.readBytes()
        val studio = fixture.launch(identifier = packId, draftName = "暂存名称", draftAuthor = "暂存作者")
        val work = fixture.workspace(studio)
        assertEquals("暂存名称", fieldText(studio, R.string.pack_name_hint))
        assertEquals("暂存作者", fieldText(studio, R.string.pack_author_hint))
        setField(studio, R.string.pack_name_hint, "继续修改名称")
        setField(studio, R.string.pack_author_hint, "继续修改作者")
        back(studio)

        assertEquals("继续修改名称", draft(work).getString("name"))
        assertEquals("继续修改作者", draft(work).getString("author"))
        assertArrayEquals(originalManifest, manifest.readBytes())
        val resumed = fixture.launch(draftKey = null, identifier = packId)
        assertEquals(work, fixture.workspace(resumed))
        assertEquals("继续修改名称", fieldText(resumed, R.string.pack_name_hint))
        assertEquals("继续修改作者", fieldText(resumed, R.string.pack_author_hint))
        clickButton(resumed, R.string.studio_discard)
        clickDialogText(resumed.getString(R.string.delete_confirm))
        await("确认丢弃后应结束工作室") { resumed.isDestroyed }

        assertFalse(work.exists())
        assertArrayEquals(originalManifest, manifest.readBytes())
        val persisted = PackRepository.get(fixture.context, packId)!!
        assertEquals("正式名称", persisted.name)
        assertEquals("正式作者", persisted.publisher)
        assertFalse(fixture.preferences.contains("active_$packId"))
    }

    @Test
    fun simultaneousActivitiesNeverShareWorkspaceOrDiscardEachOthersDraft() = withFixture { fixture ->
        val first = fixture.launch()
        val firstWork = fixture.workspace(first)
        setField(first, R.string.pack_name_hint, "第一个会话")
        val second = fixture.launch(draftKey = firstWork.name)
        val secondWork = fixture.workspace(second)
        assertNotSame(first, second)
        assertNotEquals(firstWork.canonicalFile, secondWork.canonicalFile)
        assertEquals("第一个会话", draft(firstWork).getString("name"))
        setField(second, R.string.pack_name_hint, "第二个会话")
        back(second)
        assertEquals("第二个会话", draft(secondWork).getString("name"))
        assertEquals("第一个会话", fieldText(first, R.string.pack_name_hint))

        clickButton(first, R.string.studio_discard)
        clickDialogText(first.getString(R.string.delete_confirm))
        await("第一个会话应结束") { first.isDestroyed }
        assertFalse(firstWork.exists())
        assertTrue(secondWork.isDirectory)
        assertEquals("第二个会话", draft(secondWork).getString("name"))
        val resumed = fixture.launch(draftKey = null)
        assertEquals(secondWork, fixture.workspace(resumed))
        assertEquals("第二个会话", fieldText(resumed, R.string.pack_name_hint))
    }

    @Test
    fun malformedDraftIsPreservedAndRecoveryShowsMessageInFreshSession() = withFixture { fixture ->
        val brokenWork = fixture.reserveWorkspace()
        assertTrue(brokenWork.mkdirs())
        val source = File(brokenWork, "draft.json").apply { writeText("{malformed-draft") }
        val originalBytes = source.readBytes()
        val studio = fixture.launch(draftKey = brokenWork.name)
        val recoveredWork = fixture.workspace(studio)

        assertNotEquals(brokenWork.canonicalFile, recoveredWork.canonicalFile)
        assertArrayEquals(originalBytes, source.readBytes())
        assertTrue(File(recoveredWork, "draft.json").isFile)
        assertEquals(0, draft(recoveredWork).getJSONArray("items").length())
        assertEquals(recoveredWork.name, fixture.preferences.getString("active_new", null))
        assertDialogText(studio.getString(R.string.studio_draft_recovery_failed))
        clickDialogText(studio.getString(android.R.string.ok))
        back(studio)
        assertArrayEquals(originalBytes, source.readBytes())
    }

    @Test
    fun recreateRestoresTypedNameAuthorAndSameWorkspace() = withFixture { fixture ->
        val original = fixture.launch()
        val work = fixture.workspace(original)
        setField(original, R.string.pack_name_hint, "重建后保留名称")
        setField(original, R.string.pack_author_hint, "重建后保留作者")
        val monitor = instrumentation.addMonitor(StudioActivity::class.java.name, null, false)
        val recreated: StudioActivity
        try {
            onMain { original.recreate() }
            recreated = instrumentation.waitForMonitorWithTimeout(monitor, TIMEOUT_MS) as? StudioActivity
                ?: throw AssertionError("Activity.recreate 应创建新的工作室实例")
            fixture.track(recreated)
            instrumentation.waitForIdleSync()
        } finally {
            instrumentation.removeMonitor(monitor)
        }

        assertNotSame(original, recreated)
        assertEquals(work, fixture.workspace(recreated))
        assertEquals("重建后保留名称", fieldText(recreated, R.string.pack_name_hint))
        assertEquals("重建后保留作者", fieldText(recreated, R.string.pack_author_hint))
        assertEquals("重建后保留名称", draft(work).getString("name"))
        assertEquals("重建后保留作者", draft(work).getString("author"))
    }

    private inner class Fixture {
        val context: Context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("studio_sessions", Context.MODE_PRIVATE)
        private val token = UUID.randomUUID().toString()
        private val activities = mutableListOf<Activity>()
        private val workspaces = linkedSetOf<File>()
        private val packId = "studio-ui-test-$token"
        private val cache = File(context.cacheDir, "studio-ui-test-$token")
        private val savedActiveNew = preferences.all["active_new"]
        private val hadActiveNew = preferences.contains("active_new")
        private var ownsPack = false
        private var ownsCache = false
        private val application = context.applicationContext as Application
        private val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                val ownedStudio = activity is StudioActivity && activity.intent.getStringExtra(FIXTURE_KEY) == token
                val ownedEditor = activity is StickerEditorActivity && workspaces.any {
                    File(activity.intent.getStringExtra("input_path") ?: "").parentFile == it
                }
                if (ownedStudio || ownedEditor) track(activity)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

        fun install() {
            onMain { application.registerActivityLifecycleCallbacks(lifecycle) }
        }

        fun track(activity: Activity) {
            onMain {
                if (activity !in activities) activities += activity
            }
        }

        fun reserveWorkspace(): File {
            val directory = File(context.filesDir, "studio_drafts/ui-test-${UUID.randomUUID()}")
            assertFalse("测试目录必须为本次独占", directory.exists())
            workspaces += directory
            return directory
        }

        fun launch(
            draftKey: String? = reserveWorkspace().name,
            identifier: String? = null,
            draftName: String? = null,
            draftAuthor: String? = null
        ): StudioActivity {
            val intent = Intent(context, StudioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(FIXTURE_KEY, token)
            draftKey?.let { intent.putExtra("draft_key", it) }
            identifier?.let { intent.putExtra("identifier", it) }
            draftName?.let { intent.putExtra("draft_name", it) }
            draftAuthor?.let { intent.putExtra("draft_author", it) }
            val activity = instrumentation.startActivitySync(intent) as StudioActivity
            track(activity)
            instrumentation.waitForIdleSync()
            workspace(activity)
            return activity
        }

        fun workspace(activity: StudioActivity): File {
            lateinit var directory: File
            onMain {
                directory = StudioActivity::class.java.getDeclaredField("work").apply { isAccessible = true }
                    .get(activity) as File
                workspaces += directory
            }
            return directory
        }

        fun createPack(): String {
            assertFalse(PackRepository.packDir(context, packId).exists())
            ownsPack = true
            assertTrue(cache.mkdirs())
            ownsCache = true
            val image = File(cache, "source.png")
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            try {
                image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            PackRepository.saveStudio(context, packId, "正式名称", "正式作者", listOf(image to emptyList<String>()))
            return packId
        }

        fun close() {
            try {
                onMain {
                    activities.filterIsInstance<StudioActivity>().forEach {
                        runCatching { workspace(it) }
                    }
                    activities.asReversed().filterNot { it.isDestroyed }.forEach { it.finish() }
                }
                await("清理前测试 Activity 应全部销毁") { activities.all { it.isDestroyed } }
            } finally {
                onMain { application.unregisterActivityLifecycleCallbacks(lifecycle) }
                // Activity 结束后才删除本 fixture 记录的目录，避免 onPause 重建草稿。
                if (activities.all { it.isDestroyed }) {
                    workspaces.forEach { it.deleteRecursively() }
                    if (ownsPack) PackRepository.delete(context, packId)
                    if (ownsCache) cache.deleteRecursively()
                }
                val editor = preferences.edit()
                if (hadActiveNew) editor.putString("active_new", savedActiveNew as String?)
                else editor.remove("active_new")
                if (ownsPack) editor.remove("active_$packId")
                assertTrue("应恢复测试前的草稿偏好", editor.commit())
            }
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        fixture.install()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else instrumentation.runOnMainSync { action() }
    }

    private fun draft(work: File) = JSONObject(File(work, "draft.json").readText())

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun clickButton(activity: Activity, text: Int) {
        onMain {
            val button = descendants(activity.window.decorView).filterIsInstance<MaterialButton>()
                .single { it.text.toString() == activity.getString(text) }
            assertTrue("按钮应可点击", button.performClick())
        }
        instrumentation.waitForIdleSync()
    }

    private fun input(activity: Activity, hint: Int): TextInputEditText =
        descendants(activity.window.decorView).filterIsInstance<TextInputEditText>()
            .single { it.hint?.toString() == activity.getString(hint) }

    private fun setField(activity: Activity, hint: Int, value: String) {
        onMain { input(activity, hint).setText(value) }
        instrumentation.waitForIdleSync()
    }

    private fun fieldText(activity: Activity, hint: Int): String {
        var value = ""
        onMain { value = input(activity, hint).text.toString() }
        return value
    }

    private fun back(activity: StudioActivity) {
        onMain { activity.onBackPressedDispatcher.onBackPressed() }
        await("返回后工作室应结束") { activity.isDestroyed }
    }

    private fun assertDialogText(text: String) {
        await("对话框应显示：$text") {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            root?.findAccessibilityNodeInfosByText(text).orEmpty().any { it.text?.toString() == text }
        }
    }

    private fun clickDialogText(text: String) {
        assertDialogText(text)
        val root = instrumentation.uiAutomation.rootInActiveWindow
            ?: throw AssertionError("对话框应存在")
        val button = root.findAccessibilityNodeInfosByText(text).single { it.text?.toString() == text && it.isClickable }
        assertTrue("应点击对话框按钮：$text", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(message)
    }

    companion object {
        private const val FIXTURE_KEY = "studio_ui_test_fixture"
        private const val TIMEOUT_MS = 10_000L
    }
}
