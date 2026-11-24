package io.github.midihl.wastickersimporter.telegram

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import io.github.midihl.wastickersimporter.PackRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TelegramConcurrentImportTest {
    private class FixtureContext : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        val root = File(super.getCacheDir(), "concurrent-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }
    private fun image(index: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(index * 7 % 255, index * 13 % 255, index * 19 % 255))
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }
    private class Source(val count: Int, val bytes: List<ByteArray>, val barrier: CyclicBarrier? = null) : TelegramImportSource {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val completed = java.util.Collections.synchronizedList(mutableListOf<Int>())
        override fun getStickerSet(shortName: String): TelegramStickerSet {
            barrier?.await(10, TimeUnit.SECONDS)
            return TelegramStickerSet(shortName, "Fixture $shortName", "regular",
                List(count) { TelegramSticker(it.toString(), listOf("😀", "😄", "😎")[it % 3], false, false) })
        }
        override fun downloadFile(fileId: String, destination: File): File {
            val running = active.incrementAndGet(); peak.updateAndGet { maxOf(it, running) }
            try {
                val index = fileId.toInt()
                Thread.sleep(if (index % 2 == 0) 70 else 10)
                destination.writeBytes(bytes[index]); completed.add(index); return destination
            } finally { active.decrementAndGet() }
        }
    }

    @Test fun twoPacksFetchDownloadConvertSplitAndReimportWithoutOrderOrVersionDrift() = runBlocking {
        val context = FixtureContext()
        val bytes = List(33) { image(it + 1) }
        val barrier = CyclicBarrier(2)
        val sourceA = Source(33, bytes, barrier)
        val sourceB = Source(3, bytes, barrier)
        ImportWorkerPool(2).use { pool ->
            try {
                val a = async(Dispatchers.Default) { TelegramImportCoordinator(context, "", pool, client = sourceA).fetchAndImport("fixture_a") {} }
                val b = async(Dispatchers.Default) { TelegramImportCoordinator(context, "", pool, client = sourceB).fetchAndImport("fixture_b") {} }
                val resultA = withTimeout(30_000) { a.await() }
                val resultB = b.await()
                assertEquals(listOf(30, 3), resultA.manifests.map { it.stickerCount })
                assertEquals(3, resultB.manifests.single().stickerCount)
                assertTrue(sourceA.peak.get() <= 2)
                assertTrue(sourceB.peak.get() <= 2)
                assertEquals(3, PackRepository.manifests(context).size)
                val digest = MessageDigest.getInstance("SHA-256")
                bytes.forEach { digest.update(it) }
                assertEquals(digest.digest().joinToString("") { "%02x".format(it) }.take(12), resultA.sourceHash)
                resultA.manifests.flatMap { it.stickers }.forEachIndexed { index, sticker ->
                    assertEquals(listOf(listOf("😀", "😄", "😎")[index % 3]), sticker.emojis)
                }
                val old = resultA.manifests.first()
                PackRepository.updateMetadata(context, old.identifier, "My custom name", "My author")
                pool.resize(6)
                val again = TelegramImportCoordinator(context, "", pool, client = Source(33, bytes)).fetchAndImport("fixture_a") {}
                assertEquals(resultA.sourceHash, again.sourceHash)
                assertEquals(resultA.manifests.map { it.identifier }, again.manifests.map { it.identifier })
                assertEquals("My custom name", again.manifests.first().name)
                assertEquals("My author", again.manifests.first().publisher)
                assertEquals("2", again.manifests.first().imageDataVersion)
                assertEquals(3, PackRepository.manifests(context).size)
                assertTrue(File(context.cacheDir, "telegram").listFiles().orEmpty().isEmpty())
            } finally { context.root.deleteRecursively() }
        }
    }

    @Test fun cancellingDownloadsKeepsOtherTaskFilesAndSkipsPartialPackCommit() = runBlocking {
        val context = FixtureContext()
        val bytes = List(3) { image(it + 1) }
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val slow = object : TelegramImportSource {
            override fun getStickerSet(shortName: String) = TelegramStickerSet(shortName, shortName, "regular",
                List(3) { TelegramSticker(it.toString(), "😀", false, false) })
            override fun downloadFile(fileId: String, destination: File): File {
                entered.countDown(); assertTrue(finish.await(10, TimeUnit.SECONDS))
                destination.writeBytes(bytes[fileId.toInt()]); return destination
            }
        }
        ImportWorkerPool(2).use { pool ->
            try {
                val cancelled = launch(Dispatchers.Default) {
                    val owner = coroutineContext.job
                    TelegramImportCoordinator(context, "", pool, { !owner.isActive }, slow).fetchAndImport("cancelled_pack") {}
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                cancelled.cancel()
                val kept = async(Dispatchers.Default) { TelegramImportCoordinator(context, "", pool,
                    client = Source(3, bytes)).fetchAndImport("kept_pack") {} }
                finish.countDown()
                cancelled.join()
                val result = withTimeout(30_000) { kept.await() }
                assertEquals(1, result.manifests.size)
                assertEquals(listOf("kept_pack"), PackRepository.manifests(context).map { it.telegramShortName })
                assertTrue(File(context.cacheDir, "telegram").listFiles().orEmpty().isEmpty())
            } finally { finish.countDown(); context.root.deleteRecursively() }
        }
    }

    @Test fun tgsAndVideoTasksConvertToAnimatedWebpUsingTheSharedMemoryBudget() = runBlocking {
        val context = FixtureContext()
        val json = """{"v":"5.5.7","fr":30,"ip":0,"op":6,"w":512,"h":512,"nm":"fixture","ddd":0,"assets":[],"layers":[{"ddd":0,"ind":1,"ty":1,"nm":"solid","sr":1,"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":1,"k":[{"t":0,"s":[0,0,0],"e":[400,400,0],"o":{"x":0.33,"y":0},"i":{"x":0.67,"y":1}},{"t":6,"s":[400,400,0]}]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},"sw":80,"sh":80,"sc":"#00ff00","ip":0,"op":6,"st":0,"bm":0}]}"""
        val tgs = ByteArrayOutputStream().use { output ->
            java.util.zip.GZIPOutputStream(output).use { it.write(json.toByteArray()) }; output.toByteArray()
        }
        // The recorded short video exercises the same FFmpeg decoder path as Bot API video files.
        val video = InstrumentationRegistry.getInstrumentation().context.assets.open("studio_motion.mp4").use { it.readBytes() }
        val barrier = CyclicBarrier(2)
        fun source(animated: Boolean) = object : TelegramImportSource {
            override fun getStickerSet(shortName: String): TelegramStickerSet {
                barrier.await(10, TimeUnit.SECONDS)
                return TelegramStickerSet(shortName, shortName, "regular",
                    List(3) { TelegramSticker(it.toString(), "😀", animated, !animated) })
            }
            override fun downloadFile(fileId: String, destination: File): File {
                destination.writeBytes(if (animated) tgs else video); return destination
            }
        }
        ImportWorkerPool(6).use { pool ->
            try {
                val results = coroutineScope {
                    listOf(async(Dispatchers.Default) { TelegramImportCoordinator(context, "", pool,
                        client = source(true)).fetchAndImport("tgs_fixture") {} },
                        async(Dispatchers.Default) { TelegramImportCoordinator(context, "", pool,
                            client = source(false)).fetchAndImport("video_fixture") {} }).awaitAll()
                }
                assertEquals(2, results.size)
                for (result in results) {
                    assertTrue(result.warnings.joinToString(), result.warnings.isEmpty())
                    val manifest = result.manifests.single()
                    assertTrue(manifest.animatedStickerPack)
                    assertEquals(3, manifest.stickerCount)
                    for (sticker in manifest.stickers) {
                        val file = File(PackRepository.packDir(context, manifest.identifier), sticker.imageFileName)
                        assertTrue(file.length() <= 500 * 1024)
                        assertTrue(io.github.midihl.wastickersimporter.model.StickerNames.isAnimatedWebp(file.readBytes()))
                    }
                }
                assertTrue(File(context.cacheDir, "telegram").listFiles().orEmpty().isEmpty())
            } finally { context.root.deleteRecursively() }
        }
    }
}
