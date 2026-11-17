package io.github.midihl.wastickersimporter

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.midihl.wastickersimporter.model.StickerNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A private, autosaved workspace. Library files are never mutated before Save. */
class StudioActivity : BaseActivity() {
    companion object {
        private val openDrafts = mutableSetOf<String>()
    }
    private val draftPreferences by lazy { getSharedPreferences("studio_sessions", MODE_PRIVATE) }
    private val activeDraftPreference get() = "active_${existing ?: "new"}"
    private data class Item(var path: String, var emojis: List<String>, val animated: Boolean)
    private val items = mutableListOf<Item>()
    private lateinit var root: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var name: TextInputEditText
    private lateinit var author: TextInputEditText
    private lateinit var count: TextView
    private lateinit var progress: ProgressBar
    private lateinit var work: File
    private var existing: String? = null
    private var targetId = "studio-${UUID.randomUUID()}"
    private var tray = 0
    private var editIndex = -1
    private var busy = false
    private var closed = false
    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importUris(uris)
    }
    private val edit = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = result.data?.getStringExtra("output_path")
        if (result.resultCode == RESULT_OK && path != null && editIndex in items.indices) {
            items[editIndex].path = path; persist(); refresh()
        }
        editIndex = -1
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        existing = state?.getString("existing") ?: intent.getStringExtra("identifier")
        existing?.let { targetId = it }
        val supplied = if (state == null) intent.getParcelableArrayListExtra<Uri>("media_uris") else null
        val legacyKey = (existing?.let { "edit_$it" } ?: "new").replace(Regex("[^A-Za-z0-9_-]"), "_")
        val resumableKey = if (supplied.isNullOrEmpty()) {
            draftPreferences.getString(activeDraftPreference, null)
                ?: legacyKey.takeIf { File(filesDir, "studio_drafts/$it/draft.json").isFile }
        } else null
        val key = state?.getString("work") ?: intent.getStringExtra("draft_key") ?: resumableKey
        startWorkspace(key)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.studio_title); setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { leave() }
        }
        root.addView(toolbar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE; isIndeterminate = true }
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(TextView(this).apply { setText(R.string.studio_intro); textSize = 14f; setPadding(0,0,0,dp(16)) })
        fun field(hint: Int): TextInputEditText {
            val layout = TextInputLayout(this).apply { setHint(hint) }
            val input = TextInputEditText(this).apply { maxLines = 1; filters = arrayOf(android.text.InputFilter.LengthFilter(128)) }
            layout.addView(input); body.addView(layout); return input
        }
        name = field(R.string.pack_name_hint); author = field(R.string.pack_author_hint)
        val tools = LinearLayout(this)
        button(tools, R.string.studio_add_media) { if (!busy) pick.launch(arrayOf("image/*", "video/*")) }
        button(tools, R.string.studio_text_sticker) { createBlank() }
        body.addView(tools)
        count = TextView(this).apply { setPadding(0,dp(12),0,dp(12)) }; body.addView(count)
        grid = GridLayout(this).apply { columnCount = 3 }; body.addView(grid)
        button(body, R.string.studio_save) { save() }
        button(body, R.string.studio_discard) {
            if (!busy) MaterialAlertDialogBuilder(this).setMessage(R.string.studio_discard_question)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.delete_confirm) { _,_ ->
                    closeWorkspace(); finish()
                }.show()
        }
        val restored = runCatching { restore() }
        if (restored.isFailure) {
            openDrafts.remove(work.name)
            startWorkspace(null)
            root.post { error(getString(R.string.studio_draft_recovery_failed)) }
        }
        if (restored.getOrDefault(false).not()) loadLibrary()
        refresh()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = leave() })
        if (!supplied.isNullOrEmpty()) importUris(supplied)
    }
    private fun startWorkspace(requestedKey: String?) {
        val key = requestedKey?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) && it !in openDrafts }
            ?: UUID.randomUUID().toString()
        work = File(filesDir, "studio_drafts/$key").apply { mkdirs() }
        openDrafts += key
        draftPreferences.edit().putString(activeDraftPreference, key).apply()
    }
    private fun closeWorkspace() {
        closed = true
        if (draftPreferences.getString(activeDraftPreference, null) == work.name) {
            draftPreferences.edit().remove(activeDraftPreference).apply()
        }
        openDrafts.remove(work.name)
        work.deleteRecursively()
    }
    override fun onDestroy() {
        if (::work.isInitialized) openDrafts.remove(work.name)
        super.onDestroy()
    }
    private fun button(parent: LinearLayout, text: Int, action: () -> Unit) {
        parent.addView(MaterialButton(this).apply {
            setText(text); minHeight = dp(48); setOnClickListener { action() }
        }, LinearLayout.LayoutParams(if (parent.orientation == LinearLayout.HORIZONTAL) 0 else -1, -2,
            if (parent.orientation == LinearLayout.HORIZONTAL) 1f else 0f).apply { setMargins(dp(2),dp(4),dp(2),dp(4)) })
    }
    private fun loadLibrary() {
        val pack = existing?.let { PackRepository.get(this,it) }
        name.setText(intent.getStringExtra("draft_name") ?: pack?.name ?: getString(R.string.studio_default_name))
        author.setText(intent.getStringExtra("draft_author") ?: pack?.publisher ?: "")
        pack?.stickers?.forEach { s ->
            items += Item(File(PackRepository.packDir(this,pack.identifier),s.imageFileName).absolutePath,s.emojis,pack.animatedStickerPack)
        }
        persist()
    }
    private fun restore(): Boolean {
        val file = File(work,"draft.json")
        if (!file.exists() && !File(work,"draft.json.bak").exists()) return false
        val json = JSONObject(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        val restoredName = json.getString("name")
        val restoredAuthor = json.getString("author")
        val restoredTarget = json.optString("target_id", targetId)
        require(restoredTarget == existing || restoredTarget.matches(Regex("studio-[A-Za-z0-9-]{1,80}")))
        val array = json.getJSONArray("items")
        require(array.length() <= 30)
        val restoredItems = (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            val path = entry.getString("path")
            require(File(path).isFile)
            val emojis = entry.getJSONArray("emojis")
            Item(path, (0 until emojis.length()).map { emojis.getString(it) }, entry.getBoolean("animated"))
        }
        require(restoredItems.all { it.animated } || restoredItems.none { it.animated })
        val restoredTray = json.optInt("tray", 0)
        val restoredEditing = json.optInt("editing", -1)
        require(restoredTray == 0 || restoredTray in restoredItems.indices)
        require(restoredEditing == -1 || restoredEditing in restoredItems.indices)
        name.setText(restoredName)
        author.setText(restoredAuthor)
        targetId = restoredTarget
        tray = restoredTray
        editIndex = restoredEditing
        items.clear()
        items.addAll(restoredItems)
        return true
    }
    private fun persist() {
        if (closed || !::work.isInitialized || !::name.isInitialized) return
        work.mkdirs()
        val json = JSONObject().put("target_id",targetId).put("name",name.text.toString()).put("author",author.text.toString()).put("tray",tray).put("editing",editIndex)
        val array = JSONArray(); items.forEach { array.put(JSONObject().put("path",it.path).put("emojis",JSONArray(it.emojis)).put("animated",it.animated)) }
        json.put("items",array)
        val file = android.util.AtomicFile(File(work,"draft.json"))
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            draftPreferences.edit().putString(activeDraftPreference, work.name).apply()
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }
    override fun onPause() { persist(); super.onPause() }
    override fun onSaveInstanceState(out: Bundle) {
        persist(); out.putString("work",work.name); out.putString("existing",existing); super.onSaveInstanceState(out)
    }
    private fun refresh() {
        grid.removeAllViews(); count.text = getString(R.string.studio_count, items.size)
        val cellWidth = ((resources.displayMetrics.widthPixels - dp(44)) / 3).coerceAtLeast(dp(64))
        items.forEachIndexed { index, item ->
            val card = MaterialCardView(this).apply { radius=dp(16).toFloat(); contentDescription=getString(R.string.cd_sticker,index+1,item.emojis.joinToString("")); isClickable=true; isFocusable=true }
            val content = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(6),dp(6),dp(6),dp(6)) }
            val image = ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
            val options=BitmapFactory.Options().apply { inSampleSize=2 }
            image.setImageBitmap(BitmapFactory.decodeFile(item.path,options))
            content.addView(image,LinearLayout.LayoutParams(-1,dp(86)))
            content.addView(TextView(this).apply { text="${index+1}  ${item.emojis.joinToString("")} ${if(index==tray) "★" else ""} ${if(item.animated) "▶" else ""}"; gravity=android.view.Gravity.CENTER; textSize=13f })
            card.addView(content); card.setOnClickListener { if(!busy) actions(index) }
            grid.addView(card,GridLayout.LayoutParams(GridLayout.spec(index/3),GridLayout.spec(index%3)).apply { width=cellWidth; height=dp(130); setMargins(dp(3),dp(3),dp(3),dp(3)) })
        }
    }
    private fun actions(index: Int) {
        val actions=intArrayOf(R.string.studio_edit,R.string.studio_emojis,R.string.studio_duplicate,R.string.studio_move_left,R.string.studio_move_right,R.string.studio_cover,R.string.delete_confirm)
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.studio_item_title,index+1)).setItems(actions.map { getString(it) }.toTypedArray()) { _, which ->
            when(which) {
                0 -> if(items[index].animated) error(getString(R.string.studio_animation_edit)) else openEditor(index)
                1 -> { val input=TextInputEditText(this).apply { setText(items[index].emojis.joinToString("")); hint=getString(R.string.studio_emojis) }
                    MaterialAlertDialogBuilder(this).setTitle(R.string.studio_emojis).setView(input).setPositiveButton(android.R.string.ok) { _,_ -> items[index].emojis=StickerNames.emojisFromFileName(input.text.toString()); persist(); refresh() }.setNegativeButton(android.R.string.cancel,null).show() }
                2 -> if(items.size<30) { items.add(index+1,items[index].copy()); if(tray>index) tray++ } else error(getString(R.string.studio_limit))
                3 -> if(index>0) move(index,index-1)
                4 -> if(index<items.lastIndex) move(index,index+1)
                5 -> tray=index
                6 -> { items.removeAt(index); if(tray>index) tray--; if(tray>=items.size) tray=0 }
            }; persist(); refresh()
        }.show()
    }
    private fun move(from:Int,to:Int) {
        java.util.Collections.swap(items,from,to)
        tray=when(tray) { from -> to; to -> from; else -> tray }
    }
    private fun openEditor(index:Int) {
        editIndex=index; persist()
        edit.launch(Intent(this,StickerEditorActivity::class.java).putExtra("input_path",items[index].path))
    }
    private fun createBlank() {
        if(busy) return
        if(items.size>=30) { error(getString(R.string.studio_limit)); return }
        if(items.any { it.animated }) { error(getString(R.string.studio_mixed)); return }
        val bitmap=android.graphics.Bitmap.createBitmap(512,512,android.graphics.Bitmap.Config.ARGB_8888)
        val file=File(work,"${UUID.randomUUID()}.png"); file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        items+=Item(file.absolutePath,listOf("😀"),false); persist(); refresh(); openEditor(items.lastIndex)
    }
    private fun importUris(uris:List<Uri>) {
        if(busy) return
        if(items.size+uris.size>30) { error(getString(R.string.studio_limit)); return }
        val hasVideo=uris.any { val type=contentResolver.getType(it).orEmpty(); type.startsWith("video/") || type=="image/gif" }
        if(hasVideo) {
            val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),0,dp(24),0) }
            val start=TextInputEditText(this).apply { hint=getString(R.string.studio_video_start); inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setText("0") }
            val duration=TextInputEditText(this).apply { hint=getString(R.string.studio_video_duration); inputType=start.inputType; setText("3") }
            body.addView(TextView(this).apply { setText(R.string.studio_video_note) }); body.addView(start); body.addView(duration)
            val dialog=MaterialAlertDialogBuilder(this).setTitle(R.string.studio_add_media).setView(body).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null).create()
            dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
                val s=start.text.toString().toFloatOrNull(); val d=duration.text.toString().toFloatOrNull()
                if(s==null || !s.isFinite() || s<0 || d==null || !d.isFinite() || d<=0 || d>3) { duration.error=getString(R.string.studio_video_range) }
                else { dialog.dismiss(); performImport(uris,s,d) }
            } }; dialog.show()
        } else performImport(uris,0f,3f)
    }
    private fun performImport(uris:List<Uri>,start:Float,duration:Float) {
        busy=true; progress.visibility=View.VISIBLE
        lifecycleScope.launch {
            val failures=mutableListOf<String>()
            for(uri in uris) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val result=withContext(Dispatchers.IO) { runCatching { StudioMediaImporter.import(this@StudioActivity,uri,work,start,duration) } }
                result.onSuccess { imported ->
                    if(items.isNotEmpty() && items.first().animated!=imported.animated) failures+=getString(R.string.studio_mixed)
                    else { items+=Item(imported.path,listOf("😀"),imported.animated); persist(); refresh() }
                }.onFailure { if(it is CancellationException) throw it; failures+=it.message ?: getString(R.string.studio_bad_image) }
            }
            busy=false; progress.visibility=View.GONE
            if(failures.isNotEmpty()) error(failures.distinct().joinToString("\n"))
        }
    }
    private fun save() {
        if(busy) return
        if(items.isEmpty()) { error(getString(R.string.studio_empty)); return }
        if(name.text.isNullOrBlank()) { name.error=getString(R.string.pack_name_hint); return }
        persist(); busy=true; progress.visibility=View.VISIBLE
        val snapshot=items.map { File(it.path) to it.emojis }; val title=name.text.toString(); val publisher=author.text.toString()
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) { runCatching { PackRepository.saveStudio(this@StudioActivity,targetId,title,publisher,snapshot,tray) } }
            busy=false; progress.visibility=View.GONE
            result.onSuccess { pack ->
                closeWorkspace(); ImageLoaders.clear()
                startActivity(Intent(this@StudioActivity,PreviewActivity::class.java).putExtra(PreviewActivity.EXTRA_IDENTIFIER,pack.identifier)); finish()
            }.onFailure { error(it.message ?: getString(R.string.error_unknown)) }
        }
    }
    private fun leave() {
        if(busy) { Toast.makeText(this,R.string.studio_busy,Toast.LENGTH_SHORT).show(); return }
        persist(); finish()
    }
    private fun error(message:String) { MaterialAlertDialogBuilder(this).setTitle(R.string.error_title).setMessage(message).setPositiveButton(android.R.string.ok,null).show() }
}
