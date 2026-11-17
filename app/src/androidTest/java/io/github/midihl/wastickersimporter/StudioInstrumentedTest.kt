package io.github.midihl.wastickersimporter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.midihl.wastickersimporter.convert.AnimatedWebpEncoder
import io.github.midihl.wastickersimporter.model.PackManifest
import io.github.midihl.wastickersimporter.model.StickerNames
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StudioInstrumentedTest {
    @Test
    fun saveSingleTransparentPngAsStudioDraft() = withFixture { fixture ->
        val image = fixture.transparentPng("single.png")
        val manifest = fixture.save(listOf(image))

        assertEquals(fixture.packId, manifest.identifier)
        assertEquals("studio", manifest.sourceType)
        assertEquals(1, manifest.stickerCount)
        assertEquals("1", manifest.imageDataVersion)
        assertTrue(manifest.avoidCache)
        assertFalse(manifest.animatedStickerPack)
        val persisted = PackRepository.get(fixture.context, manifest.identifier)
        assertNotNull(persisted)
        assertEquals(manifest.stickers, persisted!!.stickers)
        assertStaticAssets(fixture, manifest)
        withBitmap(File(fixture.packDirectory, manifest.stickers.single().imageFileName)) {
            assertEquals(0, Color.alpha(it.getPixel(0, 0)))
            assertTrue(Color.alpha(it.getPixel(256, 256)) > 0)
        }
    }

    @Test
    fun saveThreeStickersProducesCompliantWebpAndTray() = withFixture { fixture ->
        val images = listOf(Color.RED, Color.GREEN, Color.BLUE).mapIndexed { index, color ->
            fixture.transparentPng("image_$index.png", color)
        }
        val manifest = fixture.save(images, trayIndex = 2)

        assertEquals(3, manifest.stickerCount)
        assertStaticAssets(fixture, manifest)
        withBitmap(File(fixture.packDirectory, manifest.trayImageFile)) {
            assertTrue(Color.blue(it.getPixel(48, 48)) > 200)
            assertTrue(Color.red(it.getPixel(48, 48)) < 40)
        }
    }

    @Test
    fun editingPreservesIdentifierIncrementsVersionAndKeepsOldAssetsReadable() = withFixture { fixture ->
        val first = fixture.save(listOf(fixture.transparentPng("first.png", Color.RED)))
        val oldSticker = File(fixture.packDirectory, first.stickers.single().imageFileName)
        val oldTray = File(fixture.packDirectory, first.trayImageFile)
        val oldStickerBytes = oldSticker.readBytes()
        val oldTrayBytes = oldTray.readBytes()
        val edited = fixture.save(listOf(fixture.transparentPng("edited.png", Color.BLUE)), name = "修改后")

        assertEquals(first.identifier, edited.identifier)
        assertEquals(first.imageDataVersion.toInt() + 1, edited.imageDataVersion.toInt())
        assertNotEquals(first.stickers.single().imageFileName, edited.stickers.single().imageFileName)
        assertNotEquals(first.trayImageFile, edited.trayImageFile)
        assertEquals("修改后", PackRepository.get(fixture.context, first.identifier)!!.name)
        assertArrayEquals(oldStickerBytes, oldSticker.readBytes())
        assertArrayEquals(oldTrayBytes, oldTray.readBytes())
        withBitmap(oldSticker) { assertEquals(512, it.width) }
        withBitmap(oldTray) { assertEquals(96, it.width) }
        assertStaticAssets(fixture, edited)
    }

    @Test
    fun metadataIsTrimmedBoundedAndPersisted() = withFixture { fixture ->
        val image = fixture.transparentPng("metadata.png")
        val emojis = (0..3).map { String(Character.toChars(0x1F600 + it)) }
        val manifest = PackRepository.saveStudio(
            fixture.context, fixture.packId, "  ${"名".repeat(160)}  ",
            "  ${"作者".repeat(80)}  ", listOf(image to emojis)
        )

        assertEquals(128, manifest.name.length)
        assertEquals(128, manifest.publisher.length)
        assertEquals(emojis.take(3), manifest.stickers.single().emojis)
        val persisted = PackRepository.get(fixture.context, fixture.packId)!!
        assertEquals(manifest.name, persisted.name)
        assertEquals(manifest.publisher, persisted.publisher)
        assertEquals(manifest.stickers.single().emojis, persisted.stickers.single().emojis)
    }

    @Test
    fun blankMetadataAndEmojiListHaveNonEmptyDefaults() = withFixture { fixture ->
        val manifest = fixture.save(listOf(fixture.transparentPng("defaults.png")), name = "  ", publisher = "\n")

        assertTrue(manifest.name.isNotBlank())
        assertTrue(manifest.publisher.isNotBlank())
        assertTrue(manifest.stickers.single().emojis.isNotEmpty())
    }

    @Test
    fun animatedTwoFrameWebpIsSavedWithoutLosingAnimation() = withFixture { fixture ->
        val animated = fixture.animatedWebp()
        val sourceBytes = animated.readBytes()
        assertTrue(StickerNames.isAnimatedWebp(sourceBytes))
        assertEquals(2, countRiffChunks(sourceBytes, "ANMF"))
        assertTrue(sourceBytes.size <= AnimatedWebpEncoder.MAX_BYTES)

        val manifest = fixture.save(listOf(animated))

        assertTrue(manifest.animatedStickerPack)
        assertEquals(1, manifest.stickerCount)
        val stored = File(fixture.packDirectory, manifest.stickers.single().imageFileName)
        assertArrayEquals(sourceBytes, stored.readBytes())
        assertEquals(stored.length(), manifest.stickers.single().sizeBytes)
        assertDimensions(stored, 512, 512)
        assertDimensions(File(fixture.packDirectory, manifest.trayImageFile), 96, 96)
    }

    @Test
    fun mixingStaticAndAnimatedStickersIsRejectedInBothOrders() = withFixture { fixture ->
        val static = fixture.transparentPng("static.png")
        val animated = fixture.animatedWebp()

        listOf(listOf(static, animated), listOf(animated, static)).forEach { images ->
            expectFailure<IllegalArgumentException> { fixture.save(images) }
            assertFalse(fixture.packDirectory.exists())
        }
    }

    @Test
    fun moreThanThirtyStickersIsRejectedBeforeWritingPack() = withFixture { fixture ->
        val image = fixture.transparentPng("repeated.png")

        expectFailure<IllegalArgumentException> { fixture.save(List(31) { image }) }

        assertFalse(fixture.packDirectory.exists())
    }

    @Test
    fun emptyStudioPackIsRejectedBeforeWritingPack() = withFixture { fixture ->
        expectFailure<IllegalArgumentException> { fixture.save(emptyList()) }

        assertFalse(fixture.packDirectory.exists())
    }

    @Test
    fun corruptStudioImageIsRejectedBeforeWritingPack() = withFixture { fixture ->
        val broken = File(fixture.directory, "broken.png").apply { writeText("无法解码的图片") }

        expectFailure<PackRepository.ImportException> { fixture.save(listOf(broken)) }

        assertFalse(fixture.packDirectory.exists())
    }

    @Test
    fun largeJpegFileUriIsSampledAndLetterboxedTo512() = withFixture { fixture ->
        val source = fixture.largeJpeg("large.jpg")
        assertDimensions(source, 4096, 2048)
        val imported = runBlocking {
            StudioMediaImporter.import(fixture.context, Uri.fromFile(source), fixture.outputDirectory)
        }

        assertFalse(imported.animated)
        val output = File(imported.path)
        assertEquals(fixture.outputDirectory.canonicalFile, output.parentFile!!.canonicalFile)
        assertDimensions(output, 512, 512)
        withBitmap(output) {
            assertEquals(0, Color.alpha(it.getPixel(256, 32)))
            assertRed(it.getPixel(128, 256))
            assertBlue(it.getPixel(384, 256))
        }
    }

    @Test
    fun largeJpegExifRotationIsAppliedBeforeLetterboxing() = withFixture { fixture ->
        val source = fixture.largeJpeg("rotated.jpg")
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val imported = runBlocking {
            StudioMediaImporter.import(fixture.context, Uri.fromFile(source), fixture.outputDirectory)
        }

        assertFalse(imported.animated)
        withBitmap(File(imported.path)) {
            assertEquals(512, it.width)
            assertEquals(512, it.height)
            assertEquals(0, Color.alpha(it.getPixel(32, 256)))
            assertRed(it.getPixel(256, 128))
            assertBlue(it.getPixel(256, 384))
        }
    }

    @Test
    fun corruptFileUriThrowsAndLeavesNoImportedOutput() = withFixture { fixture ->
        val source = File(fixture.directory, "corrupt.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        expectFailure<IllegalArgumentException> {
            runBlocking {
                StudioMediaImporter.import(fixture.context, Uri.fromFile(source), fixture.outputDirectory)
            }
        }

        assertTrue(fixture.outputDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(source.isFile)
    }

    private class Fixture(val context: Context) {
        private val token = UUID.randomUUID().toString()
        val packId = "studio-test-$token"
        val directory = File(context.cacheDir, "studio-device-test-$token")
        val outputDirectory = File(directory, "imported")
        val packDirectory: File get() = PackRepository.packDir(context, packId)

        fun save(
            images: List<File>,
            name: String = "设备测试贴纸",
            publisher: String = "设备测试作者",
            trayIndex: Int = 0
        ): PackManifest = PackRepository.saveStudio(
            context, packId, name, publisher, images.map { it to emptyList<String>() }, trayIndex
        )

        fun transparentPng(name: String, color: Int = Color.RED): File {
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).drawRect(128f, 128f, 384f, 384f, Paint().apply { this.color = color })
                return File(directory, name).also { file ->
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
            } finally {
                bitmap.recycle()
            }
        }

        fun animatedWebp(): File {
            val first = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val second = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            try {
                Canvas(first).drawRect(128f, 128f, 384f, 384f, Paint().apply { color = Color.RED })
                Canvas(second).drawRect(96f, 128f, 352f, 384f, Paint().apply { color = Color.BLUE })
                val bytes = AnimatedWebpEncoder.encode(
                    listOf(AnimatedWebpEncoder.Frame(first, 120), AnimatedWebpEncoder.Frame(second, 120)), 80
                )
                return File(directory, "animated.webp").apply { writeBytes(bytes) }
            } finally {
                first.recycle()
                second.recycle()
            }
        }

        fun largeJpeg(name: String): File {
            val bitmap = Bitmap.createBitmap(4096, 2048, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply {
                    drawColor(Color.RED)
                    drawRect(2048f, 0f, 4096f, 2048f, Paint().apply { color = Color.BLUE })
                }
                return File(directory, name).also { file ->
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture(InstrumentationRegistry.getInstrumentation().targetContext)
        var ownsPackId = false
        var ownsDirectory = false
        try {
            assertFalse(fixture.packDirectory.exists())
            ownsPackId = true
            assertTrue(fixture.directory.mkdirs())
            ownsDirectory = true
            assertTrue(fixture.outputDirectory.mkdirs())
            block(fixture)
        } finally {
            // 只清理本次 UUID 对应的测试包和缓存，保留设备已有内容。
            if (ownsPackId) PackRepository.delete(fixture.context, fixture.packId)
            if (ownsDirectory) fixture.directory.deleteRecursively()
        }
    }

    private fun assertStaticAssets(fixture: Fixture, manifest: PackManifest) {
        manifest.stickers.forEach { sticker ->
            val file = File(fixture.packDirectory, sticker.imageFileName)
            assertTrue(file.isFile)
            assertTrue(file.length() in 1L..(100L * 1024L))
            assertEquals(file.length(), sticker.sizeBytes)
            val header = file.inputStream().use { input -> ByteArray(12).also { assertEquals(12, input.read(it)) } }
            assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
            assertEquals("WEBP", String(header, 8, 4, Charsets.US_ASCII))
            assertFalse(StickerNames.isAnimatedWebp(file.readBytes()))
            assertDimensions(file, 512, 512)
        }
        val tray = File(fixture.packDirectory, manifest.trayImageFile)
        assertTrue(tray.length() in 1L..(50L * 1024L))
        assertDimensions(tray, 96, 96)
    }

    private fun assertDimensions(file: File, width: Int, height: Int) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        assertEquals(width, options.outWidth)
        assertEquals(height, options.outHeight)
    }

    private fun withBitmap(file: File, block: (Bitmap) -> Unit) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        assertNotNull("图片应可解码：${file.name}", bitmap)
        try {
            block(bitmap!!)
        } finally {
            bitmap?.recycle()
        }
    }

    private fun assertRed(color: Int) {
        assertTrue(Color.red(color) > 200)
        assertTrue(Color.blue(color) < 40)
    }

    private fun assertBlue(color: Int) {
        assertTrue(Color.blue(color) > 200)
        assertTrue(Color.red(color) < 40)
    }

    private fun countRiffChunks(bytes: ByteArray, name: String): Int {
        var offset = 12
        var count = 0
        while (offset + 8 <= bytes.size) {
            val chunkName = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = (0..3).fold(0L) { value, index ->
                value or ((bytes[offset + 4 + index].toLong() and 0xffL) shl (index * 8))
            }
            if (chunkName == name) count++
            val next = offset.toLong() + 8L + size + (size and 1L)
            assertTrue("WebP 分块长度应有效", next <= bytes.size.toLong())
            offset = next.toInt()
        }
        assertEquals(bytes.size, offset)
        return count
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            throw AssertionError("预期 ${T::class.java.simpleName}，实际为 ${error.javaClass.simpleName}", error)
        }
        throw AssertionError("预期抛出 ${T::class.java.simpleName}")
    }
}
