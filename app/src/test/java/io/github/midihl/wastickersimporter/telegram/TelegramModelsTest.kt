package io.github.midihl.wastickersimporter.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramModelsTest {
    @Test
    fun parsesAllAcceptedLinkForms() {
        val expected = "myadestes_1_nacho"
        listOf(
            "https://t.me/addstickers/myadestes_1_nacho",
            "http://t.me/addstickers/myadestes_1_nacho?start=1",
            "telegram.me/addstickers/myadestes_1_nacho/extra",
            "https://telegram.me/addstickers/myadestes_1_nacho",
            "myadestes_1_nacho",
            "  myadestes_1_nacho  "
        ).forEach { assertEquals(expected, TelegramLinkParser.parse(it)) }
    }

    @Test
    fun rejectsInvalidLinks() {
        listOf(
            "", "https://example.com/addstickers/foo", "https://t.me/addemoji/foo",
            "https://t.me/addstickers/bad-name", "https://t.me/addstickers/"
        ).forEach {
            assertTrue(it, runCatching { TelegramLinkParser.parse(it) }.exceptionOrNull() is TelegramLinkParser.ParseError)
        }
    }

    @Test
    fun mapsRecordedStickerSetJson() {
        val stickers = listOf(
            TelegramRecordedSticker("A", "😀", isAnimated = false, isVideo = false),
            TelegramRecordedSticker("B", "😄", isAnimated = true, isVideo = false)
        )
        val result = TelegramMetadataMapper.map("myadestes_1_nacho", "Pack", "regular", stickers)
        assertEquals("Pack", result.title)
        assertEquals(2, result.stickers.size)
        assertEquals("😀", result.stickers[0].emoji)
        assertTrue(result.stickers[1].isAnimated)
        assertFalse(result.stickers[0].isVideo)
    }
}
