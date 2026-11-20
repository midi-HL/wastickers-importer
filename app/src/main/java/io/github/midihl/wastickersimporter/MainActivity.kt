package io.github.midihl.wastickersimporter

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.R as MaterialR
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
    companion object { const val EXTRA_TELEGRAM_SHOR_NAME = "telegram_short_name" }
    private val updates by lazy { io.github.midihl.wastickersimporter.update.UpdateController(this) }

    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private lateinit var createFab: ExtendedFloatingActionButton
    private lateinit var progress: CircularProgressIndicator
    private var archiveImportsInFlight = 0
    private var resultSnackbar: Snackbar? = null
    private var openErrorDialogs = 0
    private lateinit var tgProgress: LinearProgressIndicator
    private lateinit var tgProgressText: TextView
    private lateinit var tgCancel: MaterialButton
    private lateinit var tgFetch: MaterialButton
    private lateinit var tgInput: TextInputEditText
    private lateinit var adapter: PackAdapter

    @Volatile private var cancelled = false
    private var telegramJob: Job? = null
    private val importLock = Any()

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
        val createSticker = View.OnClickListener { startActivity(Intent(this, StudioActivity::class.java)) }
        createFab = findViewById(R.id.open_file_fab)
        createFab.setOnClickListener(createSticker)
        findViewById<MaterialButton>(R.id.empty_create_button).setOnClickListener(createSticker)
        findViewById<MaterialButton>(R.id.main_open_archive).setOnClickListener {
            openFile.launch(arrayOf("*/*"))
        }
        val nav = findViewById<BottomNavigationView>(R.id.main_navigation)
        findViewById<MaterialButton>(R.id.empty_open_button).setOnClickListener {
            nav.selectedItemId = R.id.nav_import
        }
        nav.menu.add(Menu.NONE, R.id.nav_library, 0, R.string.nav_library).setIcon(R.drawable.ic_empty_sticker)
        nav.menu.add(Menu.NONE, R.id.nav_import, 1, R.string.nav_import).setIcon(R.drawable.ic_open_file)
        nav.itemActiveIndicatorColor = ColorStateList.valueOf(MaterialColors.getColor(nav, MaterialR.attr.colorPrimaryContainer))
        val navStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val inactiveColor = MaterialColors.getColor(nav, MaterialR.attr.colorOnSurfaceVariant)
        nav.itemIconTintList = ColorStateList(navStates, intArrayOf(
            MaterialColors.getColor(nav, MaterialR.attr.colorOnPrimaryContainer), inactiveColor
        ))
        nav.itemTextColor = ColorStateList(navStates, intArrayOf(
            MaterialColors.getColor(nav, MaterialR.attr.colorPrimary), inactiveColor
        ))
        nav.setOnItemSelectedListener { selectPage(it.itemId); true }
        nav.selectedItemId = savedInstanceState?.getInt("page", R.id.nav_library) ?: R.id.nav_library
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        recycler.itemAnimator = null
        tgFetch.setOnClickListener {
            if (telegramJob?.isActive == true) return@setOnClickListener
            startTelegramFetch(tgInput.text?.toString().orEmpty())
        }
        tgCancel.setOnClickListener {
            cancelled = true
            telegramJob?.cancel()
            setTelegramBusy(false)
            File(cacheDir, "telegram").deleteRecursively()
            showResult(getString(R.string.telegram_cancelled))
        }
        if (savedInstanceState == null) handleIncomingIntent(intent)
    }

    private fun selectPage(id: Int) {
        findViewById<View>(R.id.library_page).visibility = if (id == R.id.nav_library) View.VISIBLE else View.GONE
        findViewById<View>(R.id.import_page).visibility = if (id == R.id.nav_import) View.VISIBLE else View.GONE
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.main_navigation).selectedItemId)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean =
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        } else {
            super.onOptionsItemSelected(item)
        }

    override fun onResume() {
        super.onResume()
        if (!refreshHomePalette()) {
            reload()
            recycler.post {
                if (archiveImportsInFlight == 0 && telegramJob?.isActive != true) updates.checkAutomatically()
            }
        }
    }

    private fun refreshHomePalette(): Boolean {
        // Keep the current instance until imports and their result feedback are finished.
        if (archiveImportsInFlight > 0 || telegramJob?.isActive == true || pendingExport != null ||
            resultSnackbar != null || openErrorDialogs > 0
        ) return false
        return refreshPaletteIfNeeded()
    }

    private fun refreshAfterFeedback() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refreshHomePalette()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        incoming?.getStringExtra(EXTRA_TELEGRAM_SHOR_NAME)?.takeIf { it.isNotBlank() }?.let {
            startTelegramFetch(it)
            incoming.removeExtra(EXTRA_TELEGRAM_SHOR_NAME)
            return
        }
        if (incoming?.action == Intent.ACTION_VIEW && TelegramLinkParser.isTelegramStickerLink(incoming.data)) {
            startTelegramFetch(incoming.data.toString())
            return
        }
        if (incoming?.type?.startsWith("image/") == true || incoming?.type?.startsWith("video/") == true) {
            val media = when (incoming.action) {
                Intent.ACTION_SEND_MULTIPLE -> incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                Intent.ACTION_SEND -> incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { arrayListOf(it) }
                Intent.ACTION_VIEW -> incoming.data?.let { arrayListOf(it) }
                else -> null
            }
            if (!media.isNullOrEmpty()) {
                startActivity(Intent(this, StudioActivity::class.java).putParcelableArrayListExtra("media_uris", media))
                incoming.action = null
                return
            }
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
        archiveImportsInFlight++
        CoroutineScope(Dispatchers.Main).launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching { PackRepository.importFromUri(applicationContext, uri, suggested) }
                }
            } finally {
                archiveImportsInFlight--
                progress.visibility = if (archiveImportsInFlight > 0) View.VISIBLE else View.GONE
            }
            result.fold(
                onSuccess = { imported ->
                    findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.main_navigation).selectedItemId = R.id.nav_library
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
        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.main_navigation).selectedItemId = R.id.nav_import
        tgInput.setText(shortName)
        cancelled = false
        setTelegramBusy(true)
        val context = applicationContext
        telegramJob = CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                synchronized(importLock) {
                    runCatching {
                        TelegramImportCoordinator(context, token) { cancelled }.fetchAndImport(shortName) { p ->
                            CoroutineScope(Dispatchers.Main).launch {
                                val percent = if (p.total == 0) 0 else p.done * 100 / p.total
                                tgProgress.setProgressCompat(percent, true)
                                tgProgressText.text = getString(R.string.telegram_progress, p.done + 1, p.total, p.stage)
                            }
                        }
                    }
                }
            }
            setTelegramBusy(false)
            result.fold(
                onSuccess = { imported ->
                    ImageLoaders.clear()
                    findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.main_navigation).selectedItemId = R.id.nav_library
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
        tgInput.isEnabled = !busy
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
        findViewById<TextView>(R.id.library_summary).text = getString(R.string.library_summary, packs.size, packs.sumOf { it.stickerCount })
        emptyState.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (packs.isEmpty()) View.GONE else View.VISIBLE
        createFab.visibility = if (packs.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openPreview(identifier: String) {
        startActivity(Intent(this, PreviewActivity::class.java).putExtra(PreviewActivity.EXTRA_IDENTIFIER, identifier))
        if (UiMotion.animationsEnabled(this)) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.m3_fade_slide_in, R.anim.m3_fade_hold)
        }
    }

    private fun launchAdd(identifier: String, name: String) {
        if ((PackRepository.get(this, identifier)?.stickerCount ?: 0) < 3) {
            showError(getString(R.string.studio_whatsapp_min)); return
        }
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

    private fun showResult(message: String) {
        val snackbar = UiMotion.snackbar(findViewById(R.id.main_root), message)
        resultSnackbar = snackbar
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (resultSnackbar === snackbar) {
                    resultSnackbar = null
                    refreshAfterFeedback()
                }
            }
        })
        snackbar.show()
    }

    private fun showError(message: String) {
        openErrorDialogs++
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnDismissListener {
            openErrorDialogs--
            refreshAfterFeedback()
        }
        dialog.show()
    }

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
            holder.sourceBadge.text = getString(when (pack.sourceType) {
                "telegram" -> R.string.source_telegram
                "studio" -> R.string.studio_source
                else -> R.string.source_file
            })
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
