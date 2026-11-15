package io.github.midihl.wastickersimporter.telegram

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** T2/T3: HttpURLConnection only; every URL is api.telegram.org and nothing else. */
class TelegramBotClient(private val token: String) {
    private val base = "https://api.telegram.org/bot$token"

    fun getMe(): TelegramBotInfo {
        val json = requestJson("$base/getMe")
        return TelegramBotInfo(json.getJSONObject("result").optString("username", ""))
    }

    fun getStickerSet(shortName: String): TelegramStickerSet {
        val encoded = URLEncoder.encode(shortName, Charsets.UTF_8.name())
        val response = requestJson("$base/getStickerSet?name=$encoded")
        return mapStickerSet(response.getJSONObject("result"), shortName)
    }

    companion object {
        /** Exposed for JVM unit tests with recorded Bot API JSON; no token or network required. */
        @JvmStatic
        fun mapStickerSetForTest(json: String): TelegramStickerSet =
            mapStickerSet(JSONObject(json).getJSONObject("result"), "myadestes_1_nacho")

        @JvmStatic
        fun mapStickerSet(result: JSONObject, shortName: String): TelegramStickerSet {
            val stickerArray = result.getJSONArray("stickers")
            val stickers = (0 until stickerArray.length()).map { i ->
                val obj = stickerArray.getJSONObject(i)
                TelegramSticker(
                    fileId = obj.getString("file_id"),
                    emoji = obj.optString("emoji", "😀").ifBlank { "😀" },
                    isAnimated = obj.optBoolean("is_animated", false),
                    isVideo = obj.optBoolean("is_video", false),
                    thumbnailFileId = obj.optJSONObject("thumbnail")?.optString("file_id").orEmpty()
                )
            }
            return TelegramStickerSet(
                name = result.optString("name", shortName),
                title = result.optString("title", shortName),
                stickerType = result.optString("sticker_type", "regular"),
                stickers = stickers,
                thumbnailFileId = result.optJSONObject("thumbnail")?.optString("file_id").orEmpty()
            )
        }
    }

    fun getFilePath(fileId: String): String {
        val encoded = URLEncoder.encode(fileId, Charsets.UTF_8.name())
        val result = requestJson("$base/getFile?file_id=$encoded").getJSONObject("result")
        return result.getString("file_path")
    }

    fun downloadFile(fileId: String, destination: File): File {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val path = getFilePath(fileId)
                val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
                val url = URL("https://api.telegram.org/file/bot$token/$encodedPath")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) throw TelegramApiException(code, "HTTP $code")
                connection.inputStream.use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                }
                connection.disconnect()
                return destination
            } catch (e: Exception) {
                lastError = e
                destination.delete()
                if (attempt == 1) throw e
            }
        }
        throw lastError ?: IllegalStateException("download failed")
    }

    private fun requestJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.requestMethod = "GET"
        connection.connect()
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        val json = runCatching { JSONObject(body) }.getOrElse { throw TelegramApiException(code, "Invalid Bot API response") }
        if (!json.optBoolean("ok", false)) {
            val description = json.optString("description", "Bot API error")
            throw TelegramApiException(code, description)
        }
        return json
    }
}
