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
import io.github.midihl.wastickersimporter.UiMotion.childrenSequence
import io.github.midihl.wastickersimporter.model.PackManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity() {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private lateinit var progress: CircularProgressIndicator
    private lateinit var adapter: PackAdapter

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            importUri(uri, null)
        }
    }

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
        adapter = PackAdapter(
            onAdd = { pack -> launchAdd(pack.identifier, pack.name) },
            onDelete = { pack -> confirmDelete(pack) },
            onOpen = { pack -> openPreview(pack.identifier) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val fab = findViewById<ExtendedFloatingActionButton>(R.id.open_file_fab)
                if (dy > 8) fab.shrink() else if (dy < -8) fab.extend()
            }
        })
        findViewById<ExtendedFloatingActionButton>(R.id.open_file_fab).setOnClickListener {
            openFile.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.empty_open_button).setOnClickListener {
            openFile.launch(arrayOf("*/*"))
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
                    val message = getString(R.string.import_saved, imported.manifests.size) +
                        if (warnings.isBlank()) "" else "\n$warnings"
                    showResult(message)
                    if (imported.manifests.size == 1) openPreview(imported.manifests.first().identifier)
                },
                onFailure = { error -> showError(error.message ?: getString(R.string.error_unknown)) }
            )
        }
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
            targets.size == 1 -> {
                val target = WhatsAppImporter.intent(identifier, name).setPackage(targets.first())
                addPack.launch(target)
            }
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

    private fun showResult(message: String) =
        UiMotion.snackbar(findViewById(R.id.main_root), message).show()

    private fun showError(message: String) =
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()

    private inner class PackAdapter(
        private val onAdd: (PackManifest) -> Unit,
        private val onDelete: (PackManifest) -> Unit,
        private val onOpen: (PackManifest) -> Unit
    ) : RecyclerView.Adapter<PackAdapter.ViewHolder>() {
        private val items = ArrayList<PackManifest>()

        fun submit(list: List<PackManifest>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pack, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val pack = items[position]
            holder.name.text = pack.name
            holder.author.text = pack.publisher
            holder.count.text = getString(R.string.pack_sticker_count, pack.stickerCount)
            ImageLoaders.loadSticker(holder.icon, this@MainActivity, pack, pack.trayImageFile)
            holder.card.setOnClickListener { onOpen(pack) }
            holder.add.setOnClickListener { onAdd(pack) }
            holder.delete.setOnClickListener { onDelete(pack) }
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
            val add: MaterialButton = view.findViewById(R.id.pack_add)
            val delete: MaterialButton = view.findViewById(R.id.pack_delete)
        }
    }
}
