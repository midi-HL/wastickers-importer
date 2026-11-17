package io.github.midihl.wastickersimporter

import io.github.midihl.wastickersimporter.model.StickerNames
import io.github.midihl.wastickersimporter.model.WastickersFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WastickersFormatTest {
    @Test fun truncatedWebpExtendedHeaderDoesNotCrash() {
        val bytes = ByteArray(20)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        "VP8X".toByteArray().copyInto(bytes, 12)
        org.junit.Assert.assertFalse(StickerNames.isAnimatedWebp(bytes))
    }

    private fun zip(entries: LinkedHashMap<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun pngHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(24 + 16)
        bytes[0] = 0x89.toByte(); bytes[1] = 'P'.code.toByte(); bytes[2] = 'N'.code.toByte(); bytes[3] = 'G'.code.toByte()
        "IHDR".forEachIndexed { i, c -> bytes[12 + i] = c.code.toByte() }
        listOf(width, height).forEachIndexed { i, value ->
            val offset = 16 + i * 4
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
        return bytes
    }

    @Test
    fun parsesMetadataOrderAndTray() {
        val entries = linkedMapOf(
            "title.txt" to "猫咪日常\n第二行无效".toByteArray(),
            "author.txt" to "midi-HL".toByteArray(),
            "tray.png" to pngHeader(96, 96),
            "01😀.webp" to "RIFF0000WEBPVP8L".toByteArray(),
            "02.webp" to "RIFF0000WEBPVP8L".toByteArray(),
            "03.webp" to "RIFF0000WEBPVP8L".toByteArray()
        )
        val parsed = WastickersFormat.parse(ByteArrayInputStream(zip(entries)), "fallback.wastickers")
        assertEquals("猫咪日常", parsed.name)
        assertEquals("midi-HL", parsed.publisher)
        assertNotNull(parsed.trayIconBytes)
        assertEquals(3, parsed.stickers.size)
        assertEquals("01😀.webp", parsed.stickers[0].originalName)
        assertEquals(listOf("😀"), StickerNames.emojisFromFileName(parsed.stickers[0].originalName))
        assertEquals(listOf("😀"), StickerNames.emojisFromFileName(parsed.stickers[1].originalName))
    }

    @Test
    fun missingMetadataUsesFallbacksAndPngStickers() {
        val entries = linkedMapOf(
            "a.png" to pngHeader(96, 96),
            "b.png" to pngHeader(512, 512),
            "c.png" to pngHeader(512, 512)
        )
        val parsed = WastickersFormat.parse(ByteArrayInputStream(zip(entries)), "my-pack.wastickers")
        assertEquals("my-pack", parsed.name)
        assertEquals("Unknown", parsed.publisher)
        assertEquals(2, parsed.stickers.size)
    }

    @Test
    fun brokenZipReportsParseException() {
        val result = runCatching {
            WastickersFormat.parse(ByteArrayInputStream("not a zip".toByteArray()), "x.wastickers")
        }
        assertTrue(result.exceptionOrNull() is WastickersFormat.ParseException)
    }

    @Test
    fun identifierIsStableAndSanitized() {
        val first = StickerNames.identifier("猫 咪/包", "deadbeef1234")
        val second = StickerNames.identifier("猫 咪/包", "deadbeef1234")
        assertEquals(first, second)
        assertTrue(first.matches(Regex("[A-Za-z0-9._-]+")))
        assertTrue(!first.startsWith("."))
        assertEquals("pack-abcdef", StickerNames.identifier("...", "abcdef"))
        assertEquals(3, StickerNames.emojisFromFileName("😀😄😂🎉.webp").size)
    }

    @Test
    fun animatedWebpFlagDetectsVp8xAnimationBit() {
        val bytes = ByteArray(21)
        "RIFF".forEachIndexed { i, c -> bytes[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> bytes[8 + i] = c.code.toByte() }
        "VP8X".forEachIndexed { i, c -> bytes[12 + i] = c.code.toByte() }
        bytes[20] = 0x02
        assertTrue(StickerNames.isAnimatedWebp(bytes))
    }
}
