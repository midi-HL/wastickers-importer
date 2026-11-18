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
    fun emptyLibraryImportTargetDoesNotOverlapCreateStickerFab() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        ) as MainActivity
        try {
            onMain {
                activity.findViewById<View>(R.id.empty_state).visibility = View.VISIBLE
                activity.window.decorView.requestLayout()
            }
            await("首页空状态控件应完成布局") {
                activity.findViewById<View>(R.id.empty_open_button).width > 0 &&
                    activity.findViewById<View>(R.id.open_file_fab).width > 0
            }
            onMain {
                val importButton = activity.findViewById<View>(R.id.empty_open_button)
                val createButton = activity.findViewById<View>(R.id.open_file_fab)
                val importBounds = android.graphics.Rect()
                val createBounds = android.graphics.Rect()
                assertTrue(importButton.getGlobalVisibleRect(importBounds))
                assertTrue(createButton.getGlobalVisibleRect(createBounds))
                assertFalse(
                    "空状态导入按钮与制作贴纸按钮的触控区域不能交叠: $importBounds / $createBounds",
                    android.graphics.Rect.intersects(importBounds, createBounds)
                )
                assertTrue(importButton.height >= (48 * activity.resources.displayMetrics.density).toInt())
            }
        } finally {
            onMain { activity.finish() }
        }
    }

    @Test
    fun newDraftKeepsBlankStickerAfterEditorCancelAndBack() = withFixture { fixture ->
        val studio = fixture.launch()
        val work = fixture.workspace(studio)
        assertTrue(File(work, "draft.json").isFile)
        assertEquals(0, draft(work).getJSONArray("items").length())

        val monitor = instrumentation.addMonitor(StickerEditorActivity::class.java.name, null, false)
        try {
            clickButton(studio, R.string.studio_add_sticker)
            clickSourceOption(studio.getString(R.string.studio_text_sticker))
            val editor = instrumentation.waitForMonitorWithTimeout(monitor, TIMEOUT_MS)
                ?: throw AssertionError("文字贴纸应打开编辑器")
            fixture.track(editor)
            await("文字入口应直接进入文字工具") { editorCanvas(editor as StickerEditorActivity).tool == StickerCanvasView.Tool.TEXT }
            onMain {
                assertTrue(descendants(editor.window.decorView).filterIsInstance<TextInputEditText>().any { it.hasFocus() })
            }
            clickButton(editor, R.string.editor_cancel)
            clickButton(editor, R.string.editor_text)
            await("从工具栏进入文字也应自动聚焦输入框") {
                editorCanvas(editor as StickerEditorActivity).tool == StickerCanvasView.Tool.TEXT &&
                    descendants(editor.window.decorView).filterIsInstance<TextInputEditText>().any { it.hasFocus() }
            }
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

    @Test
    fun staticImportOpensEditorImmediatelyAndSavingAdvancesToTheNextImage() = withFixture { fixture ->
        val source = fixture.createPhoto()
        val monitor = instrumentation.addMonitor(StickerEditorActivity::class.java.name, null, false)
        try {
            val studio = fixture.launch(media = arrayListOf(android.net.Uri.fromFile(source), android.net.Uri.fromFile(source)))
            val work = fixture.workspace(studio)
            val first = instrumentation.waitForMonitorWithTimeout(monitor, TIMEOUT_MS) as? StickerEditorActivity
                ?: throw AssertionError("导入后必须立即进入编辑器")
            fixture.track(first)
            await("第一张应完成加载") { editorCanvas(first).isEnabled }
            assertFalse(first.intent.getBooleanExtra("start_text", false))
            onMain { pinchImageToHalf(editorCanvas(first)) }
            val nextMonitor = instrumentation.addMonitor(StickerEditorActivity::class.java.name, null, false)
            val second = try {
                clickButton(first, R.string.editor_save)
                instrumentation.waitForMonitorWithTimeout(nextMonitor, TIMEOUT_MS) as? StickerEditorActivity
                    ?: throw AssertionError("保存后必须继续编辑下一张")
            } finally {
                instrumentation.removeMonitor(nextMonitor)
            }
            fixture.track(second)
            assertNotSame(first, second)
            await("第二张应完成加载") { editorCanvas(second).isEnabled }
            clickButton(second, R.string.editor_save)
            await("最后一张保存后应回到工作室") { second.isDestroyed && draft(work).optInt("editing") == -1 }
            assertEquals(2, draft(work).getJSONArray("items").length())
            assertEquals(0, draft(work).getJSONArray("edit_queue").length())
            for (i in 0..1) assertEquals(work.canonicalFile, File(draft(work).getJSONArray("items").getJSONObject(i).getString("path")).canonicalFile.parentFile)
            val saved = android.graphics.BitmapFactory.decodeFile(draft(work).getJSONArray("items").getJSONObject(0).getString("path"))
            try {
                assertEquals(0, android.graphics.Color.alpha(saved.getPixel(16, 16)))
                assertEquals(android.graphics.Color.RED, saved.getPixel(256, 256))
            } finally { saved.recycle() }
            clickButton(studio, R.string.studio_discard)
            clickDialogText(studio.getString(R.string.delete_confirm))
            await("丢弃应清理本次导入与编辑输出") { studio.isDestroyed && !work.exists() }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test
    fun splitArchiveImportReturnsToTheVisibleLibrary() = withFixture { fixture ->
        val archive = fixture.createSplitArchive()
        val main = instrumentation.startActivitySync(
            Intent(fixture.context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        ) as MainActivity
        fixture.track(main)
        onMain {
            main.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.main_navigation)
                .selectedItemId = R.id.nav_import
            assertEquals(View.VISIBLE, main.findViewById<View>(R.id.import_page).visibility)
            instrumentation.callActivityOnNewIntent(main, Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, android.net.Uri.fromFile(archive))
                putExtra(Intent.EXTRA_TITLE, "split.wastickers")
            })
        }
        await("拆包导入成功后应回到可见的贴纸库") {
            var ready = false
            onMain {
                val navigation = main.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.main_navigation)
                ready = navigation.selectedItemId == R.id.nav_library &&
                    main.findViewById<View>(R.id.library_page).visibility == View.VISIBLE &&
                    main.findViewById<View>(R.id.import_page).visibility == View.GONE &&
                    main.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pack_recycler).visibility == View.VISIBLE
            }
            ready
        }
        assertEquals(listOf(28, 3), fixture.importedPackIds.map { PackRepository.get(fixture.context, it)!!.stickerCount })
    }

    @Test
    fun stickerGridWrapsDownwardsAndMetadataUsesOutlinedInputs() = withFixture { fixture ->
        val packId = fixture.createPack()
        val studio = fixture.launch(identifier = packId)
        onMain {
            val grids = descendants(studio.window.decorView).filterIsInstance<android.widget.GridLayout>().single()
            val inputs = descendants(studio.window.decorView).filterIsInstance<com.google.android.material.textfield.TextInputLayout>().toList()
            assertEquals(2, inputs.size)
            assertTrue(inputs.all { it.boxBackgroundMode == com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE })
            assertEquals(3, grids.columnCount)
        }
        repeat(4) {
            onMain { StudioActivity::class.java.getDeclaredMethod("actions", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(studio, 0) }
            clickDialogText(studio.getString(R.string.studio_duplicate))
        }
        onMain {
            val grid = descendants(studio.window.decorView).filterIsInstance<android.widget.GridLayout>().single()
            assertEquals(5, grid.childCount)
            assertEquals(grid.getChildAt(0).top, grid.getChildAt(2).top)
            assertTrue(grid.getChildAt(3).top > grid.getChildAt(0).bottom)
            assertTrue((0 until grid.childCount).all { grid.getChildAt(it).right <= grid.width })
        }
    }

    private fun pinchImageToHalf(view: StickerCanvasView) {
        val side = minOf(view.width - view.paddingLeft - view.paddingRight, view.height - view.paddingTop - view.paddingBottom).toFloat()
        assertTrue("Canvas must have visible space", side > 0f)
        val left = view.paddingLeft + (view.width - view.paddingLeft - view.paddingRight - side) / 2f
        val top = view.paddingTop + (view.height - view.paddingTop - view.paddingBottom - side) / 2f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, xs: List<Float>, tick: Int) {
            val properties = Array(xs.size) { i -> android.view.MotionEvent.PointerProperties().apply { id = if (i == 0) 3 else 7; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(xs.size) { i -> android.view.MotionEvent.PointerCoords().apply { x = left + xs[i] * side; y = top + side / 2; pressure = 1f; size = 1f } }
            val event = android.view.MotionEvent.obtain(down, down + tick * 20L, action, xs.size, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        send(android.view.MotionEvent.ACTION_DOWN, listOf(.25f), 0)
        send(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(.25f, .75f), 1)
        send(android.view.MotionEvent.ACTION_MOVE, listOf(.375f, .625f), 2)
        send(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(.375f, .625f), 3)
        send(android.view.MotionEvent.ACTION_UP, listOf(.375f), 4)
    }

    private fun editorCanvas(activity: StickerEditorActivity): StickerCanvasView {
        lateinit var view: StickerCanvasView
        onMain { view = descendants(activity.window.decorView).filterIsInstance<StickerCanvasView>().single() }
        return view
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
        val importedPackIds = mutableListOf<String>()
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
            draftAuthor: String? = null,
            media: ArrayList<android.net.Uri>? = null
        ): StudioActivity {
            // Each launch must create an instance; NEW_TASK alone can only bring the existing root to front.
            val intent = Intent(context, StudioActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(FIXTURE_KEY, token)
            draftKey?.let { intent.putExtra("draft_key", it) }
            identifier?.let { intent.putExtra("identifier", it) }
            draftName?.let { intent.putExtra("draft_name", it) }
            draftAuthor?.let { intent.putExtra("draft_author", it) }
            media?.let { intent.putParcelableArrayListExtra("media_uris", it) }
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

        fun createPhoto(): File {
            cache.mkdirs(); ownsCache = true
            val file = File(cache, "photo.png")
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            try { StickerImageTools.writePng(bitmap, file) } finally { bitmap.recycle() }
            return file
        }

        fun createSplitArchive(): File {
            val source = createPhoto().readBytes()
            val tray = java.io.ByteArrayOutputStream().use { stream ->
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
                finally { bitmap.recycle() }
                stream.toByteArray()
            }
            val archive = File(cache, "split.wastickers")
            java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry("title.txt", packId.toByteArray())
                entry("author.txt", "UI test".toByteArray())
                entry("tray.png", tray)
                repeat(31) { index -> entry("sticker_$index.png", source) }
            }
            val hash = io.github.midihl.wastickersimporter.model.StickerNames.shortHash(archive.readBytes())
            importedPackIds += (1..2).map { part ->
                io.github.midihl.wastickersimporter.model.StickerNames.identifier(packId, hash, "-part${part}of2")
            }
            assertTrue(importedPackIds.none { PackRepository.packDir(context, it).exists() })
            return archive
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
                    importedPackIds.forEach { PackRepository.delete(context, it) }
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

    private fun clickSourceOption(text: String) {
        assertDialogText(text)
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: throw AssertionError("Source sheet missing")
        var node = root.findAccessibilityNodeInfosByText(text).first { it.text?.toString() == text || it.contentDescription?.toString()?.startsWith(text) == true }
        while (!node.isClickable) node = node.parent ?: throw AssertionError("Source option must be clickable")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
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
