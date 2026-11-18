package io.github.midihl.wastickersimporter

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.midihl.wastickersimporter.UiMotion.childrenSequence
import io.github.midihl.wastickersimporter.model.PackManifest
import io.github.midihl.wastickersimporter.model.StickerEntry
import io.github.midihl.wastickersimporter.telegram.WastickersExporter

class PreviewActivity : BaseActivity() {
    companion object { const val EXTRA_IDENTIFIER = "identifier" }

    private lateinit var pack: PackManifest
    private lateinit var nameInput: TextInputEditText
    private lateinit var authorInput: TextInputEditText
    private lateinit var addButton: MaterialButton
    private var pendingExport: PackManifest? = null
    private val exportFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val manifest = pendingExport ?: return@registerForActivityResult
        pendingExport = null
        if (uri != null) {
            runCatching { WastickersExporter.exportToUri(this, manifest, uri) }
                .onSuccess { UiMotion.snackbar(findViewById(R.id.preview_root), getString(R.string.export_done)).show() }
                .onFailure { showError(it.message ?: getString(R.string.error_unknown)) }
        }
    }
    private var addResultLauncher = registerForActivityResult(AddPackContract()) { (code, data) ->
        val message = WhatsAppImporter.describeResult(this, code, data)
        UiMotion.snackbar(findViewById(R.id.preview_root), message).show()
        if (code == android.app.Activity.RESULT_OK) {
            addButton.text = getString(R.string.added_state)
            addButton.setIconResource(R.drawable.ic_check)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        val toolbar = findViewById<MaterialToolbar>(R.id.preview_toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        val identifier = intent.getStringExtra(EXTRA_IDENTIFIER)
        pack = identifier?.let { PackRepository.get(this, it) } ?: run {
            finish(); return
        }
        nameInput = findViewById(R.id.pack_name_input)
        authorInput = findViewById(R.id.pack_author_input)
        addButton = findViewById(R.id.add_button)
        nameInput.setText(pack.name)
        authorInput.setText(pack.publisher)
        renderMetadata()
        findViewById<MaterialButton>(R.id.pack_edit_details).setOnClickListener { editMetadata() }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.preview_root)) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        findViewById<TextView>(R.id.pack_count).text = getString(R.string.pack_sticker_count, pack.stickerCount)
        val tray = findViewById<ImageView>(R.id.tray_image)
        ImageLoaders.loadSticker(tray, this, pack, pack.trayImageFile)
        val grid = findViewById<RecyclerView>(R.id.sticker_grid)
        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = StickerAdapter(pack)
        grid.itemAnimator = null
        findViewById<View>(R.id.preview_workspace).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateWorkspace() }
        addButton.setOnClickListener { saveMetadataAndAdd() }
        findViewById<MaterialButton>(R.id.preview_export_button).setOnClickListener {
            pendingExport = pack
            exportFile.launch("${pack.name.take(60).replace(Regex("[^A-Za-z0-9._-]"), "_")}.wastickers")
        }
        findViewById<MaterialButton>(R.id.preview_studio_button).setOnClickListener {
            startActivity(Intent(this, StudioActivity::class.java)
                .putExtra("identifier", pack.identifier)
                .putExtra("draft_name", nameInput.text?.toString().orEmpty())
                .putExtra("draft_author", authorInput.text?.toString().orEmpty()))
            finish()
        }
        findViewById<MaterialButton>(R.id.preview_telegram_button).setOnClickListener {
            saveMetadata(); TelegramExporter.launch(this, pack)
        }
        val refetch = findViewById<MaterialButton>(R.id.preview_refetch_button)
        if (pack.sourceType == "telegram") {
            refetch.visibility = View.VISIBLE
            refetch.setOnClickListener {
                saveMetadata()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_TELEGRAM_SHOR_NAME, pack.telegramShortName)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
    }

    private fun updateWorkspace() {
        val workspace = findViewById<android.widget.LinearLayout>(R.id.preview_workspace)
        if (workspace.height <= 0) return
        val summary = findViewById<android.widget.ScrollView>(R.id.preview_summary_scroll)
        val content = findViewById<View>(R.id.preview_content)
        val heading = findViewById<View>(R.id.sticker_grid_heading)
        val dp = resources.displayMetrics.density
        val wide = workspace.width >= 560 * dp && workspace.width > workspace.height * 1.35f
        val compact = !wide && workspace.height < 360 * dp
        workspace.orientation = if (wide) android.widget.LinearLayout.HORIZONTAL else android.widget.LinearLayout.VERTICAL
        heading.visibility = if (compact) View.GONE else View.VISIBLE
        fun size(view: View, width: Int, height: Int, weight: Float = 0f) {
            val lp = view.layoutParams as android.widget.LinearLayout.LayoutParams
            if (lp.width != width || lp.height != height || lp.weight != weight) {
                view.layoutParams = android.widget.LinearLayout.LayoutParams(width, height, weight)
            }
        }
        if (wide) {
            size(summary, (workspace.width * .44f).toInt().coerceAtMost((340 * dp).toInt()), -1)
            size(content, 0, -1, 1f)
        } else {
            val desired = summary.getChildAt(0).measuredHeight + (16 * dp).toInt()
            val summaryHeight = if (compact) minOf(desired, (workspace.height - 48 * dp).toInt().coerceAtLeast(0))
                else minOf(desired, (workspace.height * .5f).toInt())
            size(summary, -1, summaryHeight)
            size(content, -1, 0, 1f)
        }
    }

    private fun renderMetadata() {
        findViewById<TextView>(R.id.pack_display_title).text = pack.name
        findViewById<TextView>(R.id.pack_display_author).text = pack.publisher
    }
    private fun editMetadata() {
        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt(); setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: Int, value: String): TextInputEditText {
            val box = com.google.android.material.textfield.TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { setHint(hint) }
            val input = TextInputEditText(box.context).apply { setText(value); setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(128)) }
            box.addView(input); body.addView(box); return input
        }
        val title = field(R.string.pack_name_hint, pack.name)
        val author = field(R.string.pack_author_hint, pack.publisher)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.pack_details_edit).setView(body)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            if (title.text.isNullOrBlank()) title.error = getString(R.string.pack_name_hint)
            else {
                nameInput.setText(title.text); authorInput.setText(author.text); saveMetadata(); renderMetadata(); dialog.dismiss()
            }
        } }
        dialog.show()
    }

    private fun saveMetadata() {
        val updated = PackRepository.updateMetadata(
            this,
            pack.identifier,
            nameInput.text?.toString().orEmpty(),
            authorInput.text?.toString().orEmpty()
        ) ?: pack
        pack = updated
        nameInput.setText(pack.name)
        authorInput.setText(pack.publisher)
    }

    private fun saveMetadataAndAdd() {
        saveMetadata()
        if (pack.stickerCount < 3) { showError(getString(R.string.studio_whatsapp_min)); return }
        val targets = WhatsAppImporter.installedTargets(this)
        when {
            targets.isEmpty() -> showError(getString(R.string.error_no_whatsapp))
            targets.size == 1 -> addResultLauncher.launch(
                WhatsAppImporter.intent(pack.identifier, pack.name).setPackage(targets.first())
            )
            else -> {
                val labels = arrayOf(getString(R.string.target_whatsapp), getString(R.string.target_whatsapp_business))
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.choose_target_title)
                    .setItems(labels) { _, which ->
                        addResultLauncher.launch(
                            WhatsAppImporter.intent(pack.identifier, pack.name).setPackage(targets[which])
                        )
                    }.show()
            }
        }
    }

    private fun showError(message: String) = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.error_title).setMessage(message)
        .setPositiveButton(android.R.string.ok, null).show()

    private inner class StickerAdapter(private val manifest: PackManifest) :
        RecyclerView.Adapter<StickerAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_sticker, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val sticker: StickerEntry = manifest.stickers[position]
            ImageLoaders.loadSticker(holder.image, this@PreviewActivity, manifest, sticker.imageFileName)
            holder.image.contentDescription = getString(R.string.cd_sticker, position + 1, sticker.emojis.joinToString(""))
            holder.emoji.text = sticker.emojis.joinToString("")
        }

        override fun getItemCount(): Int = manifest.stickers.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.sticker_image)
            val emoji: TextView = view.findViewById(R.id.sticker_emoji)
        }
    }
}
