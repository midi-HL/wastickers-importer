package io.github.midihl.wastickersimporter

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.midihl.wastickersimporter.model.PackManifest
import io.github.midihl.wastickersimporter.model.StickerEntry
import java.io.File
import java.io.FileNotFoundException

/**
 * Ported from the current WhatsApp/stickers Android sample (commit 06144a1f, BSD licensed).
 * The only substantive difference: packs and files are read from `files/packs/<identifier>/`
 * instead of assets, so only packs materialized by this app are exposed.
 */
class StickerContentProvider : ContentProvider() {

    companion object {
        const val STICKER_PACK_IDENTIFIER_IN_QUERY = "sticker_pack_identifier"
        const val STICKER_PACK_NAME_IN_QUERY = "sticker_pack_name"
        const val STICKER_PACK_PUBLISHER_IN_QUERY = "sticker_pack_publisher"
        const val STICKER_PACK_ICON_IN_QUERY = "sticker_pack_icon"
        const val ANDROID_APP_DOWNLOAD_LINK_IN_QUERY = "android_play_store_link"
        const val IOS_APP_DOWNLOAD_LINK_IN_QUERY = "ios_app_download_link"
        const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        const val IMAGE_DATA_VERSION = "image_data_version"
        const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        const val ANIMATED_STICKER_PACK = "animated_sticker_pack"
        const val STICKER_FILE_NAME_IN_QUERY = "sticker_file_name"
        const val STICKER_FILE_EMOJI_IN_QUERY = "sticker_emoji"
        const val STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY = "sticker_accessibility_text"

        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"
        private const val METADATA_CODE = 1
        private const val METADATA_CODE_FOR_SINGLE_PACK = 2
        private const val STICKERS_CODE = 3
        private const val STICKERS_ASSET_CODE = 4
        private const val STICKER_PACK_TRAY_ICON_CODE = 5

        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH)

        val AUTHORITY_URI: Uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .appendPath(METADATA)
            .build()

        fun stickerListUri(identifier: String): Uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .appendPath(STICKERS).appendPath(identifier).build()

        fun stickerAssetUri(identifier: String, fileName: String): Uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .appendPath(STICKERS_ASSET).appendPath(identifier).appendPath(fileName).build()
    }

    override fun onCreate(): Boolean {
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        val context = requireNotNull(context)
        if (!authority.startsWith(context.packageName)) {
            error("ContentProvider authority must start with ${context.packageName}")
        }
        synchronized(MATCHER) {
            MATCHER.addURI(authority, METADATA, METADATA_CODE)
            MATCHER.addURI(authority, "$METADATA/*", METADATA_CODE_FOR_SINGLE_PACK)
            MATCHER.addURI(authority, "$STICKERS/*", STICKERS_CODE)
            MATCHER.addURI(authority, "$STICKERS_ASSET/*/*", STICKERS_ASSET_CODE)
            // The official sample registers each tray icon path separately. We keep
            // that distinction by resolving the requested file name at open time.
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor = when (MATCHER.match(uri)) {
        METADATA_CODE -> packInfoCursor(uri, loadPacks())
        METADATA_CODE_FOR_SINGLE_PACK -> {
            val id = uri.lastPathSegment
            packInfoCursor(uri, loadPacks().filter { it.identifier == id })
        }
        STICKERS_CODE -> stickerCursor(uri, uri.lastPathSegment)
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val code = MATCHER.match(uri)
        if (code != STICKERS_ASSET_CODE && code != STICKER_PACK_TRAY_ICON_CODE) return null
        val segments = uri.pathSegments
        if (segments.size != 3) throw IllegalArgumentException("path segments should be 3, uri is: $uri")
        val identifier = segments[1]
        val fileName = segments[2]
        val pack = loadPacks().firstOrNull { it.identifier == identifier } ?: return null
        val allowed = fileName == pack.trayImageFile || pack.stickers.any { it.imageFileName == fileName }
        if (!allowed || fileName.contains("..") || fileName.contains('/')) return null
        val file = File(packDirectory(identifier), fileName)
        if (!file.isFile) return null
        return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
    }

    override fun getType(uri: Uri): String = when {
        MATCHER.match(uri) == METADATA_CODE ->
            "vnd.android.cursor.dir/vnd.${BuildConfig.CONTENT_PROVIDER_AUTHORITY}.$METADATA"
        MATCHER.match(uri) == METADATA_CODE_FOR_SINGLE_PACK ->
            "vnd.android.cursor.item/vnd.${BuildConfig.CONTENT_PROVIDER_AUTHORITY}.$METADATA"
        MATCHER.match(uri) == STICKERS_CODE ->
            "vnd.android.cursor.dir/vnd.${BuildConfig.CONTENT_PROVIDER_AUTHORITY}.$STICKERS"
        MATCHER.match(uri) == STICKERS_ASSET_CODE && isTrayIconUri(uri) -> "image/png"
        MATCHER.match(uri) == STICKERS_ASSET_CODE -> "image/webp"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    private fun isTrayIconUri(uri: Uri): Boolean {
        val segments = uri.pathSegments
        if (segments.size != 3) return false
        val identifier = segments[1]
        val fileName = segments[2]
        return loadPacks().firstOrNull { it.identifier == identifier }?.trayImageFile == fileName
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("Not supported")

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Not supported")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("Not supported")

    private fun packInfoCursor(uri: Uri, packs: List<PackManifest>): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                STICKER_PACK_IDENTIFIER_IN_QUERY, STICKER_PACK_NAME_IN_QUERY,
                STICKER_PACK_PUBLISHER_IN_QUERY, STICKER_PACK_ICON_IN_QUERY,
                ANDROID_APP_DOWNLOAD_LINK_IN_QUERY, IOS_APP_DOWNLOAD_LINK_IN_QUERY,
                PUBLISHER_EMAIL, PUBLISHER_WEBSITE, PRIVACY_POLICY_WEBSITE,
                LICENSE_AGREEMENT_WEBSITE, IMAGE_DATA_VERSION, AVOID_CACHE,
                ANIMATED_STICKER_PACK
            )
        )
        for (pack in packs) {
            cursor.addRow(
                arrayOf(
                    pack.identifier, pack.name, pack.publisher, pack.trayImageFile,
                    pack.androidPlayStoreLink, pack.iosAppStoreLink, pack.publisherEmail,
                    pack.publisherWebsite, pack.privacyPolicyWebsite, pack.licenseAgreementWebsite,
                    pack.imageDataVersion, if (pack.avoidCache) 1 else 0,
                    if (pack.animatedStickerPack) 1 else 0
                )
            )
        }
        context?.contentResolver?.notifyChange(uri, null)
        return cursor
    }

    private fun stickerCursor(uri: Uri, identifier: String?): Cursor {
        val cursor = MatrixCursor(
            arrayOf(STICKER_FILE_NAME_IN_QUERY, STICKER_FILE_EMOJI_IN_QUERY, STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY)
        )
        val pack = loadPacks().firstOrNull { it.identifier == identifier }
        pack?.stickers?.forEach { sticker: StickerEntry ->
            cursor.addRow(arrayOf(sticker.imageFileName, sticker.emojis.joinToString(","), sticker.accessibilityText))
        }
        context?.contentResolver?.notifyChange(uri, null)
        return cursor
    }

    private fun loadPacks(): List<PackManifest> {
        val appContext = context ?: return emptyList()
        return PackRepository.manifests(appContext)
    }

    private fun packDirectory(identifier: String): File =
        File(File(context!!.filesDir, "packs"), identifier)

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        openAssetFile(uri, mode)?.parcelFileDescriptor
}
