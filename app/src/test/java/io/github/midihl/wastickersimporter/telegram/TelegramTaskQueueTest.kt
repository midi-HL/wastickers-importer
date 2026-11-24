package io.github.midihl.wastickersimporter.telegram

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TelegramTaskQueueTest {
    private suspend fun await(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }

    @Test fun multipleTasksShareTwoWorkersAndNeverMultiplyTheirBudget() = runBlocking {
        ImportWorkerPool().use { pool ->
            val owner = SupervisorJob()
            val queue = TelegramTaskQueue<Int>(CoroutineScope(owner + Dispatchers.Default))
            val release = CountDownLatch(1)
            val entered = CountDownLatch(2)
            val running = AtomicInteger()
            val peak = AtomicInteger()
            try {
                repeat(3) { task -> assertTrue(queue.add("pack$task") {
                    coroutineScope { List(6) { async { withContext(pool.dispatcher) {
                        val count = running.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
                        entered.countDown()
                        try { assertTrue(release.await(5, TimeUnit.SECONDS)); 1 } finally { running.decrementAndGet() }
                    } } }.awaitAll().sum() }
                }) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(2, running.get())
                release.countDown()
                await { !queue.hasActiveTasks }
                assertEquals(3, queue.tasks.value.count { it.state == ImportTaskState.SUCCEEDED })
                assertTrue(queue.tasks.value.all { it.result == 6 })
                assertEquals(2, peak.get())
            } finally { release.countDown(); owner.cancelAndJoin() }
        }
    }

    @Test fun raisingAndLoweringConcurrencyDoesNotLoseQueuedWork() = runBlocking {
        ImportWorkerPool(2).use { pool ->
            val release = CountDownLatch(1)
            val entered = CountDownLatch(6)
            val running = AtomicInteger()
            val work = List(18) { async(Dispatchers.Default) { withContext(pool.dispatcher) {
                running.incrementAndGet(); entered.countDown()
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); 1 } finally { running.decrementAndGet() }
            } } }
            try {
                await { running.get() == 2 }
                pool.resize(6)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(6, running.get())
                pool.resize(1)
                assertEquals(1, pool.threadCount)
                release.countDown()
                assertEquals(18, work.awaitAll().sum())
                pool.resize(99); assertEquals(6, pool.threadCount)
                pool.resize(0); assertEquals(1, pool.threadCount)
            } finally { release.countDown(); work.forEach { it.cancel() } }
        }
    }

    @Test fun cancelOneTaskWaitsForItsWorkerAndDoesNotCancelAnother() = runBlocking {
        ImportWorkerPool(2).use { pool ->
            val owner = SupervisorJob()
            val queue = TelegramTaskQueue<Int>(CoroutineScope(owner + Dispatchers.Default))
            val blocked = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                queue.add("cancel_me") { withContext(pool.dispatcher) {
                    blocked.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); 1
                } }
                assertTrue(blocked.await(5, TimeUnit.SECONDS))
                val id = queue.tasks.value.first().id
                queue.cancel(id)
                assertEquals(ImportTaskState.CANCELLING, queue.tasks.value.first().state)
                queue.add("keep_me") { withContext(pool.dispatcher) { 2 } }
                await { queue.tasks.value.last().state == ImportTaskState.SUCCEEDED }
                assertEquals(ImportTaskState.CANCELLING, queue.tasks.value.first().state)
                release.countDown()
                await { queue.tasks.value.first().state == ImportTaskState.CANCELLED }
                assertEquals(2, queue.tasks.value.last().result)
            } finally { release.countDown(); owner.cancelAndJoin() }
        }
    }

    @Test fun failureIsIsolatedDuplicateIsRejectedAndRetryReusesTheRow() = runBlocking {
        val owner = SupervisorJob()
        val queue = TelegramTaskQueue<Int>(CoroutineScope(owner + Dispatchers.Default))
        val pending = CompletableDeferred<Int>()
        try {
            assertTrue(queue.add("Pack") { pending.await() })
            assertFalse(queue.add("pack") { 0 })
            val id = queue.tasks.value.first().id
            queue.add("other") { 7 }
            pending.completeExceptionally(java.io.IOException("fixture failure"))
            await { !queue.hasActiveTasks }
            assertEquals(ImportTaskState.FAILED, queue.tasks.value.first().state)
            assertEquals(7, queue.tasks.value.last().result)
            assertTrue(queue.add("Pack", id) { 9 })
            await { !queue.hasActiveTasks }
            assertEquals(2, queue.tasks.value.size)
            assertEquals(id, queue.tasks.value.first().id)
            assertEquals(9, queue.tasks.value.first().result)
            queue.clearFinished()
            assertTrue(queue.tasks.value.isEmpty())
        } finally { owner.cancelAndJoin() }
    }

    @Test fun saveCommitCannotBeCancelledHalfwayThrough() = runBlocking {
        val owner = SupervisorJob()
        val queue = TelegramTaskQueue<Int>(CoroutineScope(owner + Dispatchers.Default))
        val finish = CompletableDeferred<Int>()
        try {
            queue.add("saving") { progress -> progress(ImportProgress(3, 3, ImportStage.SAVE)); finish.await() }
            await { queue.tasks.value.first().progress.stage == ImportStage.SAVE }
            queue.cancel(queue.tasks.value.first().id)
            assertEquals(ImportTaskState.RUNNING, queue.tasks.value.first().state)
            finish.complete(3)
            await { !queue.hasActiveTasks }
            assertEquals(ImportTaskState.SUCCEEDED, queue.tasks.value.first().state)
        } finally { owner.cancelAndJoin() }
    }
}
