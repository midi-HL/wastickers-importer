package io.github.midihl.wastickersimporter

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
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
    private lateinit var applyRow: LinearLayout
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
    private var color = Color.WHITE
    private val colors = intArrayOf(Color.WHITE, Color.BLACK, 0xffef5350.toInt(), 0xffffca28.toInt(), 0xff66bb6a.toInt(), 0xff42a5f5.toInt(), 0xffab47bc.toInt(), 0xffff80ab.toInt())
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = intent.getStringExtra("input_path")?.let { File(it) }
        if (input == null || !isPrivate(input)) { finish(); return }
        val key = MessageDigest.getInstance("SHA-256").digest(input.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }
        draft = File(filesDir,"editor_drafts/$key.png")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.editor_title); setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { leave() }
        }
        root.addView(toolbar)
        progress = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate=true }
        root.addView(progress,LinearLayout.LayoutParams(-1,dp(3)))
        canvas = StickerCanvasView(this)
        root.addView(canvas,LinearLayout.LayoutParams(-1,0,1f))
        hint = TextView(this).apply { setText(R.string.editor_intro); setPadding(dp(16),dp(4),dp(16),dp(4)); textSize=13f }
        root.addView(hint)
        val history = LinearLayout(this)
        undoButton = button(history,R.string.editor_undo) { canvas.undo(); dirty=true; refresh() }
        redoButton = button(history,R.string.editor_redo) { canvas.redo(); dirty=true; refresh() }
        saveButton = button(history,R.string.editor_save) { save() }
        root.addView(history)
        val toolScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false }
        val tools = LinearLayout(this)
        button(tools,R.string.editor_crop_rect) { crop(StickerCanvasView.Tool.RECT,R.string.editor_crop_rect) }
        button(tools,R.string.editor_crop_circle) { crop(StickerCanvasView.Tool.CIRCLE,R.string.editor_crop_circle) }
        button(tools,R.string.editor_crop_free) { crop(StickerCanvasView.Tool.FREE,R.string.editor_crop_free) }
        button(tools,R.string.editor_erase) { brush(StickerCanvasView.Tool.ERASE,R.string.editor_erase) }
        button(tools,R.string.editor_restore) { brush(StickerCanvasView.Tool.RESTORE,R.string.editor_restore) }
        button(tools,R.string.editor_remove) { remove() }
        button(tools,R.string.editor_text) { text() }
        button(tools,R.string.editor_draw) { brush(StickerCanvasView.Tool.DRAW,R.string.editor_draw) }
        button(tools,R.string.editor_outline) { outline() }
        button(tools,R.string.editor_transform) { transform() }
        button(tools,R.string.editor_adjust) { adjust() }
        toolScroll.addView(tools); root.addView(toolScroll)
        val panelScroll = ScrollView(this)
        controls = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),0,dp(12),0) }
        panelScroll.addView(controls)
        root.addView(panelScroll,LinearLayout.LayoutParams(-1,dp(156)))
        applyRow = LinearLayout(this)
        button(applyRow,R.string.editor_cancel) { canvas.cancel(); closeTool() }
        button(applyRow,R.string.editor_apply) {
            if (canvas.apply()) { dirty=true; closeTool(); persistDraft() }
            else Toast.makeText(this,R.string.editor_select_first,Toast.LENGTH_SHORT).show()
        }
        root.addView(applyRow)
        canvas.onHistoryChanged = { refresh() }
        onBackPressedDispatcher.addCallback(this,object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        refresh()
        lifecycleScope.launch {
            val result = withContext(worker) {
                runCatching {
                    val original=StickerImageTools.load(input)
                    val restored=if (draft.isFile) runCatching { StickerImageTools.load(draft) }.getOrNull() else null
                    Triple(original,restored,restored != null)
                }
            }
            result.onSuccess { (original,restored,wasRestored) ->
                canvas.setImage(restored ?: original,original)
                original.recycle(); restored?.recycle()
                loaded=true; dirty=wasRestored
                if (wasRestored) hint.setText(R.string.editor_draft_restored)
            }.onFailure { showError(getString(R.string.editor_load_failed)); finish() }
            refresh()
        }
    }

    private fun isPrivate(file: File): Boolean = runCatching {
        val path=file.canonicalPath
        file.isFile && (path.startsWith(filesDir.canonicalPath+File.separator) || path.startsWith(cacheDir.canonicalPath+File.separator))
    }.getOrDefault(false)

    private fun button(parent: LinearLayout, title: Int, action: () -> Unit): MaterialButton {
        val button=MaterialButton(this).apply { setText(title); minHeight=dp(48); setOnClickListener { if (loaded && !busy) action() } }
        parent.addView(button,LinearLayout.LayoutParams(-2,-2).apply { setMargins(dp(3),0,dp(3),0) })
        return button
    }

    private fun begin(tool: StickerCanvasView.Tool, title: Int, instruction: Int): Boolean {
        if (canvas.inTransaction) { Toast.makeText(this,R.string.editor_finish_tool,Toast.LENGTH_SHORT).show(); return false }
        controls.removeAllViews(); canvas.begin(tool)
        hint.text=getString(title)+" · "+getString(instruction)
        refresh(); return true
    }

    private fun closeTool() {
        controls.removeAllViews(); hint.setText(R.string.editor_intro); refresh()
    }

    private fun refresh() {
        if (!::applyRow.isInitialized) return
        undoButton.isEnabled=loaded && !busy && canvas.canUndo
        redoButton.isEnabled=loaded && !busy && canvas.canRedo
        saveButton.isEnabled=loaded && !busy && !canvas.inTransaction
        canvas.isEnabled=loaded && !busy
        progress.visibility=if (busy || !loaded) View.VISIBLE else View.GONE
        applyRow.visibility=if (canvas.inTransaction) View.VISIBLE else View.GONE
    }

    private fun label(text: Int) { controls.addView(TextView(this).apply { setText(text); textSize=13f }) }
    private fun slider(title: Int, max: Int, initial: Int, offset: Int = 0, update: (Int) -> Unit) {
        val value=TextView(this).apply { text=getString(title)+": "+(initial+offset) }
        controls.addView(value)
        controls.addView(SeekBar(this).apply {
            this.max=max; progress=initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, amount: Int, fromUser: Boolean) {
                    value.text=getString(title)+": "+(amount+offset)
                    if (fromUser) update(amount)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
    }

    private fun colorPicker(update: (Int) -> Unit) {
        val scroll=HorizontalScrollView(this)
        val row=LinearLayout(this)
        val names=intArrayOf(R.string.editor_white,R.string.editor_black,R.string.editor_red,R.string.editor_yellow,R.string.editor_green,R.string.editor_blue,R.string.editor_purple,R.string.editor_pink)
        colors.forEachIndexed { i,c ->
            button(row,names[i]) { color=c; update(c) }.apply {
                setTextColor(if (i==1) Color.WHITE else Color.BLACK)
                backgroundTintList=android.content.res.ColorStateList.valueOf(c)
            }
        }
        scroll.addView(row); controls.addView(scroll)
    }

    private fun crop(tool: StickerCanvasView.Tool,title: Int) {
        if (begin(tool,title,R.string.editor_crop_hint)) label(R.string.editor_crop_note)
    }
    private fun brush(tool: StickerCanvasView.Tool,title: Int) {
        if (!begin(tool,title,R.string.editor_brush_hint)) return
        canvas.brushSize=26f; canvas.brushColor=color
        slider(R.string.editor_brush_size,79,25,1) { canvas.brushSize=(it+1).toFloat() }
        if (tool==StickerCanvasView.Tool.DRAW) colorPicker { canvas.brushColor=it }
        if (tool==StickerCanvasView.Tool.RESTORE) label(R.string.editor_restore_hint)
    }
    private fun remove() {
        if (!begin(StickerCanvasView.Tool.REMOVE,R.string.editor_remove,R.string.editor_remove_hint)) return
        canvas.tolerance=40
        slider(R.string.editor_tolerance,160,40) { canvas.tolerance=it }
    }
    private fun text() {
        if (!begin(StickerCanvasView.Tool.TEXT,R.string.editor_text,R.string.editor_text_hint)) return
        val input=TextInputEditText(this).apply {
            hint=getString(R.string.editor_text_input); minLines=2; maxLines=4
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters=arrayOf(android.text.InputFilter.LengthFilter(400))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { canvas.textValue=s.toString() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        controls.addView(input)
        canvas.textSizePx=48f; canvas.textColor=color
        slider(R.string.editor_font_size,108,36,12) { canvas.textSizePx=(it+12).toFloat() }
        colorPicker { canvas.textColor=it }
    }
    private fun outline() {
        if (!begin(StickerCanvasView.Tool.NONE,R.string.editor_outline,R.string.editor_preview_hint)) return
        var width=6f; var selectedColor=Color.WHITE
        fun preview() { val source=canvas.baselineCopy(); val result=StickerImageTools.outline(source,width,selectedColor); source.recycle(); canvas.preview(result) }
        slider(R.string.editor_outline_width,24,6) { width=it.toFloat(); preview() }
        colorPicker { selectedColor=it; preview() }
        label(R.string.editor_outline_note); preview()
    }
    private fun transform() {
        if (!begin(StickerCanvasView.Tool.NONE,R.string.editor_transform,R.string.editor_preview_hint)) return
        var rotation=0f; var mirrorX=false; var mirrorY=false; var scale=1f
        fun preview() { val source=canvas.baselineCopy(); val result=StickerImageTools.transform(source,rotation,mirrorX,mirrorY,scale); source.recycle(); canvas.preview(result) }
        val row=LinearLayout(this)
        button(row,R.string.editor_rotate) { rotation+=90; preview() }
        button(row,R.string.editor_mirror_x) { mirrorX=!mirrorX; preview() }
        button(row,R.string.editor_mirror_y) { mirrorY=!mirrorY; preview() }
        controls.addView(HorizontalScrollView(this).apply { addView(row) })
        slider(R.string.editor_scale,150,50,50) { scale=(it+50)/100f; preview() }
    }
    private fun adjust() {
        if (!begin(StickerCanvasView.Tool.NONE,R.string.editor_adjust,R.string.editor_preview_hint)) return
        var brightness=0f; var saturation=1f
        fun preview() { val source=canvas.baselineCopy(); val result=StickerImageTools.adjust(source,brightness,saturation); source.recycle(); canvas.preview(result) }
        slider(R.string.editor_brightness,200,100) { brightness=(it-100)*1.5f; preview() }
        slider(R.string.editor_saturation,200,100) { saturation=it/100f; preview() }
    }

    private fun persistDraft() {
        if (!loaded || finished || busy || (!dirty && !canvas.inTransaction) || !::draft.isInitialized) return
        val image=canvas.snapshot()
        // 独立生命周期和单线程保证重建期间完成写入，再读取最新草稿。
        draftScope.launch {
            try { runCatching { StickerImageTools.writePng(image,draft) } } finally { image.recycle() }
        }
    }
    override fun onPause() { persistDraft(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { persistDraft(); super.onSaveInstanceState(outState) }

    private fun save() {
        if (busy || !loaded || canvas.inTransaction) return
        busy=true; refresh()
        val image=canvas.snapshot()
        val output=File(filesDir,"editor_outputs/${UUID.randomUUID()}.png")
        lifecycleScope.launch {
            val result=withContext(worker) {
                try { runCatching { StickerImageTools.writePng(image,output); draft.delete(); output.absolutePath } }
                finally { image.recycle() }
            }
            busy=false
            result.onSuccess {
                finished=true; setResult(RESULT_OK,Intent().putExtra("output_path",it)); finish()
            }.onFailure { showError(getString(R.string.editor_save_failed)); refresh() }
        }
    }
    private fun leave() {
        if (busy || !loaded) return
        if (canvas.inTransaction) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.editor_cancel_tool_question)
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.editor_cancel) { _,_ -> canvas.cancel(); closeTool() }.show()
            return
        }
        if (!dirty) { finished=true; finish(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.editor_unsaved).setMessage(R.string.editor_unsaved_question)
            .setPositiveButton(R.string.editor_save) { _,_ -> save() }
            .setNeutralButton(R.string.editor_keep_draft) { _,_ -> persistDraft(); finish() }
            .setNegativeButton(R.string.editor_discard) { _,_ ->
                finished=true
                lifecycleScope.launch { withContext(worker) { draft.delete() }; finish() }
            }.show()
    }
    private fun showError(message: String) {
        Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    }
    override fun onDestroy() { if (::canvas.isInitialized) canvas.release(); super.onDestroy() }
}
