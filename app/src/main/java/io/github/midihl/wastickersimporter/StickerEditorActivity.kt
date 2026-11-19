package io.github.midihl.wastickersimporter

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

class StickerEditorActivity : BaseActivity() {
    companion object {
        private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val draftScope = CoroutineScope(SupervisorJob() + worker)
    }
    private lateinit var canvas: StickerCanvasView
    private lateinit var controls: LinearLayout
    private lateinit var controlsScroll: ScrollView
    private lateinit var toolsScroll: ScrollView
    private lateinit var workspace: LinearLayout
    private lateinit var canvasColumn: LinearLayout
    private lateinit var panel: LinearLayout
    private var keyboardVisible = false
    private lateinit var tools: LinearLayout
    private lateinit var applyRow: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var hint: TextView
    private lateinit var undoButton: MaterialButton
    private lateinit var redoButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var draft: File
    private var loaded = false
    private var dirty = false
    private var busy = false
    private var finished = false
    private var previewJob: Job? = null
    private var pendingPreview: ((Bitmap) -> Bitmap)? = null
    private var previewRevision = 0
    private var previewSession = 0
    private var previewRunning = false
    private var color = Color.rgb(103, 80, 164)
    private val colors = intArrayOf(Color.WHITE, Color.BLACK, 0xffe53935.toInt(), 0xffffc107.toInt(), 0xff43a047.toInt(), 0xff1e88e5.toInt(), 0xff8e24aa.toInt(), 0xffec407a.toInt())
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun tone(attr: Int) = MaterialColors.getColor(this, attr, Color.GRAY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = intent.getStringExtra("input_path")?.let { File(it) }
        if (input == null || !isPrivate(input)) { finish(); return }
        val key = MessageDigest.getInstance("SHA-256").digest(input.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }
        draft = File(filesDir, "editor_drafts/$key.png")
        color = tone(com.google.android.material.R.attr.colorPrimary)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tone(com.google.android.material.R.attr.colorSurface))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            if (::controlsScroll.isInitialized) {
                keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                hint.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
                panelTitle.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
                updateWorkspace()
            }
            insets
        }
        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.editor_title); setNavigationIcon(R.drawable.ic_back)
            setNavigationContentDescription(R.string.navigate_back)
            setTitleTextAppearance(this@StickerEditorActivity, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            setNavigationOnClickListener { leave() }
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        undoButton = iconButton(actions, R.string.editor_undo, "undo") { canvas.undo(); dirty = true; persistDraft(); refresh() }
        redoButton = iconButton(actions, R.string.editor_redo, "redo") { canvas.redo(); dirty = true; persistDraft(); refresh() }
        saveButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            setText(R.string.editor_save); textSize = 13f; minWidth = 0
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { save() }
        }
        actions.addView(saveButton, LinearLayout.LayoutParams(-2, dp(48)))
        toolbar.addView(actions, androidx.appcompat.widget.Toolbar.LayoutParams(-2, -1, Gravity.END))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        workspace = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))
        canvasColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        workspace.addView(canvasColumn, LinearLayout.LayoutParams(-1, 0, 1f))
        canvas = StickerCanvasView(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(tone(com.google.android.material.R.attr.colorSurfaceContainerLowest))
        }
        canvasColumn.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
        hint = TextView(this).apply {
            setText(R.string.editor_gesture_intro); textSize = 12f; gravity = Gravity.CENTER
            setTextColor(tone(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(16), dp(6), dp(16), dp(8)); minHeight = dp(32)
        }
        canvasColumn.addView(hint)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(tone(com.google.android.material.R.attr.colorSurfaceContainer))
                cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        workspace.addView(panel)
        panelTitle = TextView(this).apply {
            setText(R.string.editor_tools); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), dp(6))
        }
        panel.addView(panelTitle)
        tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val definitions = listOf(
            Triple(R.string.editor_position, "move") { transform() },
            Triple(R.string.editor_crop_rect, "crop") { crop(StickerCanvasView.Tool.RECT, R.string.editor_crop_rect) },
            Triple(R.string.editor_rounded, "rounded") { crop(StickerCanvasView.Tool.ROUNDED, R.string.editor_rounded) },
            Triple(R.string.editor_text, "text") { text() },
            Triple(R.string.editor_remove, "remove") { remove() },
            Triple(R.string.editor_draw, "draw") { brush(StickerCanvasView.Tool.DRAW, R.string.editor_draw) },
            Triple(R.string.editor_crop_circle, "circle") { crop(StickerCanvasView.Tool.CIRCLE, R.string.editor_crop_circle) },
            Triple(R.string.editor_crop_free, "free") { crop(StickerCanvasView.Tool.FREE, R.string.editor_crop_free) },
            Triple(R.string.editor_adjust, "adjust") { adjust() },
            Triple(R.string.editor_erase, "erase") { brush(StickerCanvasView.Tool.ERASE, R.string.editor_erase) },
            Triple(R.string.editor_restore, "restore") { brush(StickerCanvasView.Tool.RESTORE, R.string.editor_restore) },
            Triple(R.string.editor_outline, "outline") { outline() }
        )
        definitions.chunked(3).forEach { group ->
            val row = LinearLayout(this)
            group.forEach { (title, icon, action) ->
                val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    setText(title); textSize = 12f; maxLines = 2; minWidth = 0; minHeight = dp(76)
                    this.icon = EditorGlyph(icon); iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
                    iconSize = dp(22); iconPadding = dp(3); cornerRadius = dp(18)
                    strokeWidth = 0
                    backgroundTintList = ColorStateList.valueOf(tone(com.google.android.material.R.attr.colorSurfaceContainerHigh))
                    setTextColor(tone(com.google.android.material.R.attr.colorOnSurface)); iconTint = textColors
                    setPadding(dp(4), dp(8), dp(4), dp(8)); insetTop = 0; insetBottom = 0
                    setOnClickListener { if (loaded && !busy) action() }
                }
                row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            tools.addView(row)
        }
        toolsScroll = ScrollView(this).apply { addView(tools); isVerticalScrollBarEnabled = true }
        panel.addView(toolsScroll, LinearLayout.LayoutParams(-1, dp(264)))
        controlsScroll = ScrollView(this).apply { isFillViewport = false; isVerticalScrollBarEnabled = true }
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        controlsScroll.addView(controls)
        panel.addView(controlsScroll, LinearLayout.LayoutParams(-1, dp(192)))
        applyRow = LinearLayout(this)
        actionButton(applyRow, R.string.editor_cancel, false) { canvas.cancel(); closeTool() }
        actionButton(applyRow, R.string.editor_apply, true) {
            if (previewRunning) return@actionButton
            if (canvas.apply()) { dirty = true; closeTool(); persistDraft() }
            else Toast.makeText(this, if (canvas.tool == StickerCanvasView.Tool.TEXT) R.string.editor_text_empty else R.string.editor_select_first, Toast.LENGTH_SHORT).show()
        }
        panel.addView(applyRow)
        workspace.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateWorkspace() }
        canvas.onHistoryChanged = { refresh() }
        canvas.onInteraction = { if (!canvas.inTransaction && canvas.canUndo) { dirty = true; persistDraft() }; refresh() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        refresh()
        lifecycleScope.launch {
            val result = withContext(worker) {
                runCatching {
                    val original = StickerImageTools.load(input)
                    val restored = if (draft.isFile) runCatching { StickerImageTools.load(draft) }.getOrNull() else null
                    Triple(original, restored, restored != null)
                }
            }
            result.onSuccess { (original, restored, wasRestored) ->
                canvas.setImage(restored ?: original, original)
                original.recycle(); restored?.recycle()
                loaded = true; dirty = wasRestored
                if (wasRestored) hint.setText(R.string.editor_draft_restored)
                if (intent.getBooleanExtra("start_text", false) && !wasRestored) text(focus = true)
            }.onFailure { showError(getString(R.string.editor_load_failed)); finish() }
            refresh()
        }
    }

    private fun isPrivate(file: File): Boolean = runCatching {
        val path = file.canonicalPath
        file.isFile && (path.startsWith(filesDir.canonicalPath + File.separator) || path.startsWith(cacheDir.canonicalPath + File.separator))
    }.getOrDefault(false)

    private fun iconButton(parent: LinearLayout, title: Int, glyph: String, action: () -> Unit): MaterialButton {
        val button = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            contentDescription = getString(title); icon = EditorGlyph(glyph); iconSize = dp(22)
            iconPadding = 0; minWidth = 0; setPadding(dp(11), 0, dp(11), 0)
            setOnClickListener { if (loaded && !busy) action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(dp(48), dp(48)))
        return button
    }
    private fun actionButton(parent: LinearLayout, title: Int, filled: Boolean = false, action: () -> Unit) {
        parent.addView(MaterialButton(this, null, if (filled) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(title); minWidth = 0; setOnClickListener { if (loaded && !busy) action() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), dp(2), dp(4), 0) })
    }

    private fun begin(tool: StickerCanvasView.Tool, title: Int, instruction: Int): Boolean {
        if (!loaded || busy) return false
        if (canvas.inTransaction) { Toast.makeText(this, R.string.editor_finish_tool, Toast.LENGTH_SHORT).show(); return false }
        controls.removeAllViews(); panelTitle.setText(title); canvas.begin(tool)
        hint.setText(instruction); controlsScroll.scrollTo(0, 0)
        refresh(); return true
    }

    /** Keep only the newest slider request; no bitmap work runs in the input callback. */
    private fun requestPreview(operation: (Bitmap) -> Bitmap) {
        pendingPreview = operation
        previewRevision++
        previewRunning = true
        refresh()
        if (previewJob?.isActive == true) return
        previewJob = lifecycleScope.launch {
            try {
                while (pendingPreview != null) {
                    val next = pendingPreview ?: break
                    pendingPreview = null
                    val revision = previewRevision
                    val session = previewSession
                    val source = canvas.baselineCopy()
                    var result: Bitmap? = null
                    try {
                        withContext(worker) { result = next(source) }
                        coroutineContext.ensureActive()
                        if (session == previewSession && revision == previewRevision && canvas.inTransaction) {
                            canvas.preview(requireNotNull(result)); result = null
                        }
                    } finally {
                        source.recycle(); result?.recycle()
                    }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                pendingPreview = null
                showError(getString(R.string.editor_load_failed))
            } finally {
                previewRunning = false
                if (!isDestroyed) refresh()
            }
        }
    }

    private fun closeTool() {
        pendingPreview = null; previewRevision++; previewSession++
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(controls.windowToken, 0)
        controls.clearFocus(); controls.removeAllViews()
        panelTitle.setText(R.string.editor_tools); hint.setText(R.string.editor_gesture_intro); refresh()
    }
    private fun refresh() {
        if (!::applyRow.isInitialized) return
        undoButton.isEnabled = loaded && !busy && canvas.canUndo
        redoButton.isEnabled = loaded && !busy && canvas.canRedo
        saveButton.isEnabled = loaded && !busy && !canvas.inTransaction
        canvas.isEnabled = loaded && !busy
        progress.visibility = if (busy || !loaded || previewRunning) View.VISIBLE else View.GONE
        if (applyRow.childCount > 1) applyRow.getChildAt(1).isEnabled = loaded && !busy && !previewRunning
        val editing = canvas.inTransaction && !canvas.isDirectManipulation
        toolsScroll.visibility = if (editing) View.GONE else View.VISIBLE
        applyRow.visibility = if (editing) View.VISIBLE else View.GONE
        controlsScroll.visibility = if (editing) View.VISIBLE else View.GONE
        updateWorkspace()
    }

    private fun updateWorkspace() {
        if (!::applyRow.isInitialized || workspace.height <= 0) return
        val wide = workspace.width > workspace.height * 1.35f && workspace.width >= dp(560)
        val orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (workspace.orientation != orientation) workspace.orientation = orientation
        fun size(view: View, width: Int, height: Int, weight: Float = 0f) {
            val lp = view.layoutParams as LinearLayout.LayoutParams
            if (lp.width != width || lp.height != height || lp.weight != weight) {
                view.layoutParams = LinearLayout.LayoutParams(width, height, weight)
            }
        }
        if (wide) {
            size(canvasColumn, 0, -1, 1f)
            size(panel, dp(310), -1)
            size(toolsScroll, -1, 0, 1f)
            size(controlsScroll, -1, 0, 1f)
        } else {
            size(canvasColumn, -1, 0, 1f)
            size(panel, -1, -2)
            val available = workspace.height
            val panelSpace = (available * if (keyboardVisible) .55f else .54f).toInt()
            val title = if (keyboardVisible) 0 else dp(30)
            size(toolsScroll, -1, minOf(dp(264), (panelSpace - title - dp(18)).coerceAtLeast(dp(66))))
            size(controlsScroll, -1, minOf(dp(192), (panelSpace - title - dp(72)).coerceAtLeast(dp(72))))
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        workspace.post { updateWorkspace() }
    }
    private fun label(text: Int) {
        controls.addView(TextView(this).apply {
            setText(text); textSize = 12f; setTextColor(tone(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(6), 0, dp(6))
        })
    }
    private fun slider(title: Int, from: Float, to: Float, initial: Float, suffix: String = "", update: (Float) -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0) }
        row.addView(TextView(this).apply { setText(title); textSize = 14f }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(this).apply { text = "${initial.toInt()}$suffix"; textSize = 14f; setTextColor(tone(com.google.android.material.R.attr.colorPrimary)) }
        row.addView(value); controls.addView(row)
        controls.addView(Slider(this).apply {
            valueFrom = from; valueTo = to; stepSize = 1f; this.value = initial
            contentDescription = getString(title)
            setLabelFormatter { "${it.toInt()}$suffix" }
            addOnChangeListener { _, amount, fromUser ->
                value.text = "${amount.toInt()}$suffix"
                if (fromUser) update(amount)
            }
        }, LinearLayout.LayoutParams(-1, dp(42)))
    }
    private fun colorPicker(initial: Int = color, update: (Int) -> Unit) {
        val names = intArrayOf(R.string.editor_white, R.string.editor_black, R.string.editor_red, R.string.editor_yellow, R.string.editor_green, R.string.editor_blue, R.string.editor_purple, R.string.editor_pink)
        val palette = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val rows = List(2) { LinearLayout(this).apply { gravity = Gravity.CENTER } }
        rows.forEach { palette.addView(it) }
        val swatches = mutableListOf<MaterialButton>()
        fun select(c: Int) { swatches.forEachIndexed { index, button ->
            button.strokeWidth = dp(if (colors[index] == c) 3 else 1)
            button.strokeColor = ColorStateList.valueOf(if (colors[index] == c) tone(com.google.android.material.R.attr.colorPrimary) else tone(com.google.android.material.R.attr.colorOutline))
            button.icon = if (colors[index] == c) EditorGlyph("check") else null
            button.iconTint = ColorStateList.valueOf(if (index == 1 || index == 2 || index >= 4) Color.WHITE else Color.BLACK)
        } }
        colors.forEachIndexed { i, c ->
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                contentDescription = getString(names[i]); minWidth = 0; minHeight = 0
                cornerRadius = dp(18); insetTop = dp(3); insetBottom = dp(3)
                backgroundTintList = ColorStateList.valueOf(c); iconPadding = 0; iconSize = dp(18)
                setPadding(dp(6), 0, dp(6), 0)
                setOnClickListener { color = c; update(c); select(c) }
            }
            swatches += button
            rows[i / 4].addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) })
        }
        select(initial); controls.addView(palette)
    }
    private fun crop(tool: StickerCanvasView.Tool, title: Int) {
        if (!begin(tool, title, R.string.editor_crop_gesture_hint)) return
        if (tool == StickerCanvasView.Tool.ROUNDED) {
            slider(R.string.editor_corner_radius, 0f, 256f, canvas.cropRadius) { canvas.cropRadius = it }
            label(R.string.editor_rounded_note)
        } else label(R.string.editor_crop_note)
        resetViewButton()
    }
    private fun resetViewButton() {
        val row = LinearLayout(this)
        actionButton(row, R.string.editor_reset_view) { canvas.resetViewport() }; controls.addView(row)
    }
    private fun brush(tool: StickerCanvasView.Tool, title: Int) {
        if (!begin(tool, title, R.string.editor_brush_gesture_hint)) return
        canvas.brushSize = 26f; canvas.brushColor = color
        slider(R.string.editor_brush_size, 1f, 80f, 26f) { canvas.brushSize = it }
        if (tool == StickerCanvasView.Tool.DRAW) colorPicker { canvas.brushColor = it }
        if (tool == StickerCanvasView.Tool.RESTORE) label(R.string.editor_restore_hint)
        resetViewButton()
    }
    private fun remove() {
        if (!begin(StickerCanvasView.Tool.REMOVE, R.string.editor_remove, R.string.editor_remove_gesture_hint)) return
        canvas.tolerance = 40
        slider(R.string.editor_tolerance, 0f, 160f, 40f) { canvas.tolerance = it.toInt() }
        label(R.string.editor_remove_note); resetViewButton()
    }
    private fun text(focus: Boolean = true) {
        if (!begin(StickerCanvasView.Tool.TEXT, R.string.editor_text, R.string.editor_text_gesture_hint)) return
        val layout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { setHint(R.string.editor_text_input) }
        val input = TextInputEditText(layout.context).apply {
            minLines = 2; maxLines = 3; textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(400))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { canvas.textValue = s.toString() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        layout.addView(input); controls.addView(layout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        canvas.textSizePx = 64f; canvas.textColor = color
        slider(R.string.editor_font_size, 12f, 160f, 64f) { canvas.textSizePx = it }
        colorPicker { canvas.textColor = it }
        if (focus) input.post {
            input.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }
    private fun outline() {
        if (!begin(StickerCanvasView.Tool.NONE, R.string.editor_outline, R.string.editor_preview_hint)) return
        var width = 6f; var selectedColor = Color.WHITE
        fun preview() {
            val requestedWidth = width; val requestedColor = selectedColor
            requestPreview { StickerImageTools.outline(it, requestedWidth, requestedColor) }
        }
        slider(R.string.editor_outline_width, 0f, 24f, 6f) { width = it; preview() }
        colorPicker(Color.WHITE) { selectedColor = it; preview() }; label(R.string.editor_outline_note); preview()
    }
    private fun transform() {
        if (!begin(StickerCanvasView.Tool.TRANSFORM, R.string.editor_position, R.string.editor_transform_gesture_hint)) return
        val row = LinearLayout(this)
        actionButton(row, R.string.editor_rotate) { canvas.rotateTransform(90f) }
        actionButton(row, R.string.editor_mirror_x) { canvas.mirrorTransform(true) }
        controls.addView(row)
        val second = LinearLayout(this)
        actionButton(second, R.string.editor_mirror_y) { canvas.mirrorTransform(false) }
        actionButton(second, R.string.editor_reset_position) { canvas.resetTransform() }
        controls.addView(second)
        label(R.string.editor_transform_note)
    }
    private fun adjust() {
        if (!begin(StickerCanvasView.Tool.NONE, R.string.editor_adjust, R.string.editor_preview_hint)) return
        var brightness = 0f; var saturation = 1f
        fun preview() {
            val requestedBrightness = brightness; val requestedSaturation = saturation
            requestPreview { StickerImageTools.adjust(it, requestedBrightness, requestedSaturation) }
        }
        slider(R.string.editor_brightness, -100f, 100f, 0f) { brightness = it * 1.5f; preview() }
        slider(R.string.editor_saturation, 0f, 200f, 100f, "%") { saturation = it / 100f; preview() }
    }

    private fun persistDraft() {
        if (!loaded || finished || !dirty || !::draft.isInitialized) return
        val image = canvas.baselineCopy()
        draftScope.launch { try { runCatching { StickerImageTools.writePng(image, draft) } } finally { image.recycle() } }
    }
    override fun onPause() { persistDraft(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { persistDraft(); super.onSaveInstanceState(outState) }
    private fun save() {
        if (busy || !loaded || canvas.inTransaction) return
        busy = true; refresh()
        val image = canvas.snapshot()
        val output = File(filesDir, "editor_outputs/${UUID.randomUUID()}.png")
        lifecycleScope.launch {
            val result = withContext(worker) {
                try { runCatching { StickerImageTools.writePng(image, output); output.absolutePath } }
                finally { image.recycle() }
            }
            busy = false
            result.onSuccess {
                finished = true; draftScope.launch { draft.delete() }
                setResult(RESULT_OK, Intent().putExtra("output_path", it)); finish()
            }.onFailure { showError(getString(R.string.editor_save_failed)); refresh() }
        }
    }
    private fun leave() {
        if (busy || !loaded) return
        if (canvas.inTransaction) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.editor_cancel_tool_question)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.editor_cancel) { _, _ -> canvas.cancel(); closeTool() }.show()
            return
        }
        if (!dirty) { finished = true; finish(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.editor_unsaved).setMessage(R.string.editor_unsaved_question)
            .setPositiveButton(R.string.editor_save) { _, _ -> save() }
            .setNeutralButton(R.string.editor_keep_draft) { _, _ -> persistDraft(); finish() }
            .setNegativeButton(R.string.editor_discard) { _, _ ->
                finished = true; lifecycleScope.launch { withContext(worker) { draft.delete() }; finish() }
            }.show()
    }
    private fun showError(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onDestroy() { if (::canvas.isInitialized) canvas.release(); super.onDestroy() }
}

/** Small, resolution-independent tool symbols; their tint follows the current Material color scheme. */
internal class EditorGlyph(private val name: String) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var tintColors: ColorStateList? = null
    override fun setTint(tintColor: Int) = setTintList(ColorStateList.valueOf(tintColor))
    override fun setTintList(tint: ColorStateList?) {
        tintColors = tint
        onStateChange(state)
        invalidateSelf()
    }
    override fun isStateful() = tintColors?.isStateful == true
    override fun onStateChange(state: IntArray): Boolean {
        val colors = tintColors
        val nextColor = colors?.getColorForState(state, colors.defaultColor) ?: Color.BLACK
        if (paint.color == nextColor) return false
        paint.color = nextColor
        invalidateSelf()
        return true
    }
    override fun draw(canvas: Canvas) {
        val save = canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        val path = Path()
        fun lines(vararg p: Float) { path.moveTo(p[0], p[1]); for (i in 2 until p.size step 2) path.lineTo(p[i], p[i + 1]) }
        when (name) {
            "move" -> { lines(12f, 3f, 12f, 21f); lines(3f, 12f, 21f, 12f); lines(9f, 6f, 12f, 3f, 15f, 6f); lines(9f, 18f, 12f, 21f, 15f, 18f); lines(6f, 9f, 3f, 12f, 6f, 15f); lines(18f, 9f, 21f, 12f, 18f, 15f) }
            "crop" -> { lines(7f, 3f, 7f, 17f, 21f, 17f); lines(3f, 7f, 17f, 7f, 17f, 21f) }
            "rounded" -> path.addRoundRect(4f, 4f, 20f, 20f, 5f, 5f, Path.Direction.CW)
            "circle" -> path.addCircle(12f, 12f, 8f, Path.Direction.CW)
            "text" -> { lines(4f, 5f, 20f, 5f); lines(12f, 5f, 12f, 20f); lines(8f, 20f, 16f, 20f) }
            "draw" -> { lines(4f, 20f, 5f, 15f, 16f, 4f, 20f, 8f, 9f, 19f, 4f, 20f); lines(14f, 6f, 18f, 10f) }
            "erase" -> { lines(3f, 14f, 13f, 4f, 21f, 12f, 12f, 21f, 9f, 21f, 3f, 15f); lines(8f, 9f, 16f, 17f); lines(12f, 21f, 21f, 21f) }
            "remove" -> { lines(4f, 20f, 17f, 7f, 20f, 10f, 7f, 23f); lines(4f, 4f, 4f, 8f); lines(2f, 6f, 6f, 6f); lines(13f, 2f, 13f, 6f); lines(11f, 4f, 15f, 4f); lines(21f, 17f, 21f, 21f); lines(19f, 19f, 23f, 19f) }
            "free" -> { path.moveTo(5f, 5f); path.cubicTo(18f, 0f, 23f, 8f, 18f, 14f); path.cubicTo(14f, 21f, 1f, 21f, 4f, 13f); path.cubicTo(5f, 9f, 11f, 10f, 10f, 14f); lines(5f, 17f, 3f, 22f) }
            "adjust" -> { lines(5f, 3f, 5f, 21f); lines(12f, 3f, 12f, 21f); lines(19f, 3f, 19f, 21f); lines(2f, 8f, 8f, 8f); lines(9f, 16f, 15f, 16f); lines(16f, 9f, 22f, 9f) }
            "outline" -> { path.addRoundRect(3f, 3f, 21f, 21f, 6f, 6f, Path.Direction.CW); path.addRoundRect(8f, 8f, 16f, 16f, 2f, 2f, Path.Direction.CW) }
            "restore" -> { path.addArc(4f, 4f, 20f, 20f, -70f, 285f); lines(3f, 4f, 3f, 10f, 9f, 10f); lines(12f, 8f, 12f, 16f); lines(8f, 12f, 16f, 12f) }
            "undo", "redo" -> { if (name == "redo") { canvas.translate(24f, 0f); canvas.scale(-1f, 1f) }; lines(9f, 5f, 4f, 10f, 9f, 15f); path.moveTo(4f, 10f); path.cubicTo(16f, 4f, 23f, 13f, 19f, 20f) }
            "check" -> lines(5f, 12f, 10f, 17f, 20f, 6f)
        }
        canvas.drawPath(path, paint); canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 24
    override fun getIntrinsicHeight() = 24
}
