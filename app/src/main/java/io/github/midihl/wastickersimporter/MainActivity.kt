package io.github.midihl.wastickersimporter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import io.github.midihl.wastickersimporter.UiMotion.childrenSequence
import io.github.midihl.wastickersimporter.model.PackManifest
import io.github.midihl.wastickersimporter.telegram.TelegramApiException
import io.github.midihl.wastickersimporter.telegram.TelegramImportCoordinator
import io.github.midihl.wastickersimporter.telegram.TelegramLinkParser
import io.github.midihl.wastickersimporter.telegram.TelegramTokenStore
import io.github.midihl.wastickersimporter.telegram.WastickersExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : BaseActivity() {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private lateinit var progress: CircularProgressIndicator
    private lateinit var tgProgress: LinearProgressIndicator
    private lateinit var tgProgressText: TextView
    private lateinit var tgCancel: MaterialButton
    private lateinit var tgFetch: MaterialButton
    private lateinit var tgInput: TextInputEditText
    private lateinit var adapter: PackAdapter

    @Volatile private var cancelled = false
    private var telegramJob: Job? = null

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            importUri(uri, null)
        }
    }

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val manifest = pendingExport ?: return@registerForActivityResult
        pendingExport = null
        if (uri != null) {
            runCatching { WastickersExporter.exportToUri(this, manifest, uri) }
                .onSuccess { showResult(getString(R.string.export_done)) }
                .onFailure { showError(it.message ?: getString(R.string.error_unknown)) }
        }
    }
    private var pendingExport: PackManifest? = null

    private val addPack = registerForActivityResult(AddPackContract()) { (code, data) ->
        showResult(WhatsAppImporter.describeResult(this, code, data))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.top_app_bar))
        findViewById<MaterialToolbar>(R.id.top_app_bar).setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            } else false
        }
        recycler = findViewById(R.id.pack_recycler)
        emptyState = findViewById(R.id.empty_state)
        progress = findViewById(R.id.progress)
        tgProgress = findViewById(R.id.telegram_progress)
        tgProgressText = findViewById(R.id.telegram_progress_text)
        tgCancel = findViewById(R.id.telegram_cancel_button)
        tgFetch = findViewById(R.id.telegram_fetch_button)
        tgInput = findViewById(R.id.telegram_link_input)
        adapter = PackAdapter(
            onAdd = { pack -> launchAdd(pack.identifier, pack.name) },
            onDelete = { pack -> confirmDelete(pack) },
            onOpen = { pack -> openPreview(pack.identifier) },
            onExport = { pack -> startExport(pack) },
            onRefetch = { pack -> startTelegramFetch(pack.telegramShortName) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        findViewById<ExtendedFloatingActionButton>(R.id.open_file_fab).setOnClickListener {
            openFile.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.empty_open_button).setOnClickListener {
            openFile.launch(arrayOf("*/*"))
        }
        tgFetch.setOnClickListener { startTelegramFetch(tgInput.text?.toString().orEmpty()) }
        tgCancel.setOnClickListener {
            cancelled = true
            telegramJob?.cancel()
            setTelegramBusy(false)
            File(cacheDir, "telegram").deleteRecursively()
            showResult(getString(R.string.telegram_cancelled))
        }
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming?.action == Intent.ACTION_VIEW && TelegramLinkParser.isTelegramStickerLink(incoming.data)) {
            startTelegramFetch(incoming.data.toString())
            return
        }
        val uri = when (incoming?.action) {
            Intent.ACTION_VIEW -> incoming.data
            Intent.ACTION_SEND -> incoming.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) importUri(uri, incoming?.getStringExtra(Intent.EXTRA_TITLE))
    }

    private fun displayName(uri: Uri): String {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: uri.lastPathSegment.orEmpty()
            }
        }
        return uri.lastPathSegment ?: "stickers.wastickers"
    }

    private fun importUri(uri: Uri, title: String?) {
        progress.visibility = View.VISIBLE
        val suggested = title ?: displayName(uri)
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { PackRepository.importFromUri(applicationContext, uri, suggested) }
            }
            progress.visibility = View.GONE
            result.fold(
                onSuccess = { imported ->
                    reload()
                    val warnings = imported.warnings.joinToString("\n")
                    showResult(getString(R.string.import_saved, imported.manifests.size) + if (warnings.isBlank()) "" else "\n$warnings")
                    if (imported.manifests.size == 1) openPreview(imported.manifests.first().identifier)
                },
                onFailure = { error -> showError(error.message ?: getString(R.string.error_unknown)) }
            )
        }
    }

    private fun startTelegramFetch(rawLink: String) {
        val shortName = try {
            TelegramLinkParser.parse(rawLink)
        } catch (e: TelegramLinkParser.ParseError) {
            showError(getString(R.string.telegram_invalid_link)); return
        }
        val token = TelegramTokenStore.token(this)
        if (token.isBlank()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.telegram_token_missing_title)
                .setMessage(R.string.telegram_token_missing_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }.show()
            return
        }
        tgInput.setText(shortName)
        cancelled = false
        setTelegramBusy(true)
        val context = applicationContext
        telegramJob = CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    TelegramImportCoordinator(context, token) { cancelled }.fetchAndImport(shortName) { p ->
                        launch(Dispatchers.Main) {
                            val percent = if (p.total == 0) 0 else p.done * 100 / p.total
                            tgProgress.setProgressCompat(percent, true)
                            tgProgressText.text = getString(R.string.telegram_progress, p.done + 1, p.total, p.stage)
                        }
                    }
                }
            }
            setTelegramBusy(false)
            result.fold(
                onSuccess = { imported ->
                    ImageLoaders.clear()
                    reload()
                    showResult(getString(R.string.telegram_import_done, imported.manifests.size) +
                        if (imported.warnings.isEmpty()) "" else "\n" + imported.warnings.joinToString("\n"))
                },
                onFailure = { error -> showError(telegramError(error)) }
            )
        }
    }

    private fun telegramError(error: Throwable): String = when (error) {
        is TelegramImportCoordinator.CancelledException -> getString(R.string.telegram_cancelled)
        is TelegramApiException -> when {
            error.code == 401 -> getString(R.string.telegram_token_invalid)
            error.description.contains("STICKERSET_INVALID", true) || error.code == 404 ->
                getString(R.string.telegram_pack_not_found)
            else -> getString(R.string.telegram_network_error, error.description)
        }
        else -> error.message ?: getString(R.string.error_unknown)
    }

    private fun setTelegramBusy(busy: Boolean) {
        tgFetch.isEnabled = !busy
        tgCancel.visibility = if (busy) View.VISIBLE else View.GONE
        tgProgress.visibility = if (busy) View.VISIBLE else View.GONE
        tgProgressText.visibility = if (busy) View.VISIBLE else View.GONE
        if (!busy) tgProgress.setProgressCompat(0, false)
    }

    private fun startExport(pack: PackManifest) {
        pendingExport = pack
        exportFile.launch("${pack.name.take(60).replace(Regex("[^A-Za-z0-9._-]"), "_")}.wastickers")
    }

    private fun reload() {
        val packs = PackRepository.manifests(this)
        adapter.submit(packs)
        emptyState.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (packs.isEmpty()) View.GONE else View.VISIBLE
        recycler.post { UiMotion.staggerIn(recycler.childrenSequence(), this) }
    }

    private fun openPreview(identifier: String) {
        startActivity(Intent(this, PreviewActivity::class.java).putExtra(PreviewActivity.EXTRA_IDENTIFIER, identifier))
        if (UiMotion.animationsEnabled(this)) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.m3_fade_slide_in, R.anim.m3_fade_hold)
        }
    }

    private fun launchAdd(identifier: String, name: String) {
        val targets = WhatsAppImporter.installedTargets(this)
        when {
            targets.isEmpty() -> showError(getString(R.string.error_no_whatsapp))
            targets.size == 1 -> addPack.launch(WhatsAppImporter.intent(identifier, name).setPackage(targets.first()))
            else -> {
                val labels = arrayOf(getString(R.string.target_whatsapp), getString(R.string.target_whatsapp_business))
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.choose_target_title)
                    .setItems(labels) { _, which ->
                        addPack.launch(WhatsAppImporter.intent(identifier, name).setPackage(targets[which]))
                    }.show()
            }
        }
    }

    private fun confirmDelete(pack: PackManifest) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(R.string.delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                PackRepository.delete(this, pack.identifier)
                ImageLoaders.clear()
                reload()
                showResult(getString(R.string.delete_done))
            }.show()
    }

    private fun showResult(message: String) = UiMotion.snackbar(findViewById(R.id.main_root), message).show()

    private fun showError(message: String) = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.error_title).setMessage(message)
        .setPositiveButton(android.R.string.ok, null).show()

    private inner class PackAdapter(
        private val onAdd: (PackManifest) -> Unit,
        private val onDelete: (PackManifest) -> Unit,
        private val onOpen: (PackManifest) -> Unit,
        private val onExport: (PackManifest) -> Unit,
        private val onRefetch: (PackManifest) -> Unit
    ) : RecyclerView.Adapter<PackAdapter.ViewHolder>() {
        private val items = ArrayList<PackManifest>()

        fun submit(list: List<PackManifest>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_pack, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val pack = items[position]
            holder.name.text = pack.name
            holder.author.text = pack.publisher
            holder.count.text = getString(R.string.pack_sticker_count, pack.stickerCount)
            holder.sourceBadge.text = if (pack.sourceType == "telegram") getString(R.string.source_telegram) else getString(R.string.source_file)
            ImageLoaders.loadSticker(holder.icon, this@MainActivity, pack, pack.trayImageFile)
            holder.card.setOnClickListener { onOpen(pack) }
            holder.add.setOnClickListener { onAdd(pack) }
            holder.delete.setOnClickListener { onDelete(pack) }
            holder.export.setOnClickListener { onExport(pack) }
            holder.refetch.visibility = if (pack.sourceType == "telegram") View.VISIBLE else View.GONE
            holder.refetch.setOnClickListener { onRefetch(pack) }
            holder.add.contentDescription = getString(R.string.cd_add_pack, pack.name)
            holder.delete.contentDescription = getString(R.string.cd_delete_pack, pack.name)
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.pack_card)
            val icon: ImageView = view.findViewById(R.id.pack_icon)
            val name: TextView = view.findViewById(R.id.pack_name)
            val author: TextView = view.findViewById(R.id.pack_author)
            val count: TextView = view.findViewById(R.id.pack_count)
            val sourceBadge: TextView = view.findViewById(R.id.pack_source_badge)
            val add: MaterialButton = view.findViewById(R.id.pack_add)
            val export: MaterialButton = view.findViewById(R.id.pack_export)
            val refetch: MaterialButton = view.findViewById(R.id.pack_refetch)
            val delete: MaterialButton = view.findViewById(R.id.pack_delete)
        }
    }
}
