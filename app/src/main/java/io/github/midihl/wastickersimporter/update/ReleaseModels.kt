package io.github.midihl.wastickersimporter.update

import java.net.URI

/** A downloadable file attached to a GitHub Release. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

/** The small, user-visible part of a stable GitHub Release. */
data class AppRelease(
    val tag: String,
    val versionName: String,
    val pageUrl: String,
    val notes: String,
    val assets: List<ReleaseAsset>
)

object ReleaseVersions {
    private val versionPattern = Regex("^v?(0|[1-9]\\d*)(?:\\.(0|[1-9]\\d*)){0,2}$", RegexOption.IGNORE_CASE)

    /**
     * Compares numeric release versions only. Prereleases, build identifiers and
     * malformed tags are deliberately ignored so an update check cannot select a
     * release whose meaning is ambiguous to the user.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val next = parse(candidate) ?: return false
        val installed = parse(current) ?: return false
        for (index in 0 until maxOf(next.size, installed.size)) {
            val a = next.getOrElse(index) { 0 }
            val b = installed.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    internal fun parse(value: String): List<Int>? {
        val normalized = value.trim()
        if (!versionPattern.matches(normalized)) return null
        val pieces = normalized.removePrefix("v").removePrefix("V").split('.')
        return pieces.map { it.toIntOrNull() ?: return null }
    }
}

object ReleaseAssets {
    private val blockedName = Regex("(^|[-_.])(debug|androidtest|unsigned|test)([-_.]|$)", RegexOption.IGNORE_CASE)

    /**
     * Selects the release APK matching the device ABI order, then universal APK.
     * Debug, test and unsigned artifacts are excluded even if attached to a release.
     */
    fun preferred(release: AppRelease, supportedAbis: List<String>): ReleaseAsset? {
        val candidates = release.assets.filter { isInstallableReleaseApk(it.name) }
            .sortedBy { if (it.name.endsWith("-release.apk", ignoreCase = true)) 0 else 1 }
        for (abi in supportedAbis.map { it.trim() }.filter { it.isNotEmpty() }) {
            candidates.firstOrNull { asset -> hasAbi(asset.name, abi) }?.let { return it }
        }
        return candidates.firstOrNull { isUniversal(it.name) }
    }

    private fun isInstallableReleaseApk(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".apk") && !blockedName.containsMatchIn(lower)
    }

    private fun hasAbi(name: String, abi: String): Boolean {
        if (abi !in setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) return false
        val stem = name.removeSuffix(".apk").removeSuffix(".APK")
        // An underscore belongs to x86_64, so only the complete known ABI is matched.
        val otherAbis = setOf("armeabi-v7a", "arm64-v8a", "x86_64", "x86")
        val declared = otherAbis.filter { known ->
            Regex("(^|[-_.])" + Regex.escape(known) + "($|[-_.])", RegexOption.IGNORE_CASE).containsMatchIn(stem)
        }.toMutableSet().apply { if ("x86_64" in this) remove("x86") }
        return abi in declared
    }

    private fun isUniversal(name: String): Boolean =
        Regex("(^|[-_.])universal($|[-_.])", RegexOption.IGNORE_CASE)
            .containsMatchIn(name.substringBeforeLast('.'))
}

/** A Release response may link only to this repository's HTTPS pages and APK attachments. */
internal object ReleaseUrls {
    private const val REPOSITORY = "midi-HL/wastickers-importer"

    fun isReleasePage(value: String, tag: String): Boolean {
        val uri = trustedUri(value) ?: return false
        return uri.rawPath == "/$REPOSITORY/releases/tag/$tag"
    }

    fun isReleaseDownload(value: String, tag: String): Boolean {
        val uri = trustedUri(value) ?: return false
        val prefix = "/$REPOSITORY/releases/download/$tag/"
        if (!uri.rawPath.startsWith(prefix)) return false
        val name = uri.path.substringAfter("/releases/download/$tag/", "")
        return name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name
    }

    private fun trustedUri(value: String): URI? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return uri.takeIf {
            it.scheme == "https" && it.host.equals("github.com", true) &&
                it.userInfo == null && it.port == -1 && it.query == null && it.fragment == null
        }
    }
}
