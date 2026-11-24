package io.github.midihl.wastickersimporter.telegram

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Only leaf operations run here; task owners never wait for children on these threads. */
class ImportWorkerPool(threads: Int = DEFAULT_THREADS) : Closeable {
    companion object {
        const val DEFAULT_THREADS = 2
        const val MIN_THREADS = 1
        const val MAX_THREADS = 6
    }

    private val sequence = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        threads.coerceIn(MIN_THREADS, MAX_THREADS), threads.coerceIn(MIN_THREADS, MAX_THREADS),
        30, TimeUnit.SECONDS, LinkedBlockingQueue(),
        { runnable -> Thread(runnable, "telegram-worker-${sequence.incrementAndGet()}").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }
    val dispatcher = executor.asCoroutineDispatcher()
    // Each animated encoder may hold 90 full-size bitmaps plus its pixel buffer.
    // Wait for this memory budget outside the worker pool so downloads can continue.
    val animatedSlots = Semaphore((Runtime.getRuntime().maxMemory() / (256L * 1024 * 1024))
        .toInt().coerceIn(1, MAX_THREADS))
    val threadCount: Int get() = executor.maximumPoolSize

    @Synchronized
    fun resize(threads: Int) {
        val count = threads.coerceIn(MIN_THREADS, MAX_THREADS)
        if (count > executor.maximumPoolSize) {
            executor.maximumPoolSize = count
            executor.corePoolSize = count
        } else {
            executor.corePoolSize = count
            executor.maximumPoolSize = count
        }
    }

    override fun close() { dispatcher.close() }
}
