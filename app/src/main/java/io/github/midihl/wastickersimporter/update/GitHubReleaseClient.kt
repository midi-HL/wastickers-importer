package io.github.midihl.wastickersimporter.update

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Read-only, unauthenticated access to this project's stable GitHub Release. */
class GitHubReleaseClient {
    companion object {
        private const val API_URL = "https://api.github.com/repos/midi-HL/wastickers-importer/releases/latest"
        private const val MAX_RESPONSE_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    /** Null means there is no supported stable release; request errors remain visible to the caller. */
    fun latestStable(): AppRelease? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "Sticker-Studio-Android")
            }
            connection.connect()
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (status !in 200..299) throw IOException("GitHub HTTP $status")
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw IOException("GitHub response too large")
            val body = connection.inputStream.use { readLimited(it, MAX_RESPONSE_BYTES) }
            parseRelease(JSONObject(body.toString(Charsets.UTF_8)))
        } catch (error: JSONException) {
            throw IOException("Invalid GitHub release response", error)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject): AppRelease? {
        if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) return null
        val tag = json.optString("tag_name").trim()
        val version = ReleaseVersions.parse(tag) ?: return null
        val pageUrl = json.optString("html_url").trim()
        if (!ReleaseUrls.isReleasePage(pageUrl, tag)) return null
        val assets = mutableListOf<ReleaseAsset>()
        val array = json.optJSONArray("assets") ?: return null
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            val url = item.optString("browser_download_url").trim()
            if (name.isBlank() || !ReleaseUrls.isReleaseDownload(url, tag)) continue
            assets += ReleaseAsset(name, url, item.optLong("size", 0L).coerceAtLeast(0L))
        }
        return AppRelease(tag, version.joinToString("."), pageUrl, json.optString("body", ""), assets)
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("GitHub response too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
