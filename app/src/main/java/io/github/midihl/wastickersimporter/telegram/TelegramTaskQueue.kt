package io.github.midihl.wastickersimporter.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.coroutineContext

enum class ImportStage { FETCH, DOWNLOAD, CONVERT, SAVE }
data class ImportProgress(val done: Int = 0, val total: Int = 0, val stage: ImportStage = ImportStage.FETCH)
enum class ImportTaskState { QUEUED, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }
data class ImportTask<T>(
    val id: String, val shortName: String,
    val state: ImportTaskState = ImportTaskState.QUEUED,
    val progress: ImportProgress = ImportProgress(),
    val result: T? = null, val error: Throwable? = null
) {
    val active: Boolean get() = state in setOf(ImportTaskState.QUEUED, ImportTaskState.RUNNING, ImportTaskState.CANCELLING)
}

/** Process-lifetime queue, independent of Activity recreation. No credentials in public state. */
class TelegramTaskQueue<T>(private val scope: CoroutineScope) {
    private val mutableTasks = MutableStateFlow<List<ImportTask<T>>>(emptyList())
    val tasks = mutableTasks.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    val hasActiveTasks: Boolean get() = tasks.value.any { it.active }

    @Synchronized
    fun add(shortName: String, retryId: String? = null,
            operation: suspend ((ImportProgress) -> Unit) -> T): Boolean {
        if (tasks.value.any { it.active && it.shortName.equals(shortName, ignoreCase = true) }) return false
        val previous = retryId?.let { id -> tasks.value.firstOrNull { it.id == id && !it.active } }
        val task = ImportTask<T>(previous?.id ?: UUID.randomUUID().toString(), shortName)
        mutableTasks.update { current ->
            if (previous == null) current + task else current.map { if (it.id == task.id) task else it }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                change(task.id) { it.copy(state = ImportTaskState.RUNNING) }
                val result = operation { progress ->
                    change(task.id) { if (it.state == ImportTaskState.RUNNING) it.copy(progress = progress) else it }
                }
                coroutineContext.ensureActive()
                change(task.id) { it.copy(state = ImportTaskState.SUCCEEDED, result = result) }
            } catch (_: CancellationException) {
                change(task.id) { it.copy(state = ImportTaskState.CANCELLED) }
            } catch (error: Exception) {
                change(task.id) { it.copy(state = ImportTaskState.FAILED, error = error) }
            } finally {
                synchronized(this@TelegramTaskQueue) { jobs.remove(task.id) }
            }
        }
        jobs[task.id] = job
        job.start()
        return true
    }

    @Synchronized
    fun cancel(id: String) {
        if (tasks.value.any { it.id == id && it.state == ImportTaskState.RUNNING && it.progress.stage == ImportStage.SAVE }) return
        val job = jobs[id] ?: return
        change(id) { if (it.active) it.copy(state = ImportTaskState.CANCELLING) else it }
        job.cancel()
        // A lazy job cancelled before dispatch may never enter its body.
        job.invokeOnCompletion { change(id) { if (it.state == ImportTaskState.CANCELLING) it.copy(state = ImportTaskState.CANCELLED) else it } }
    }

    @Synchronized
    fun clearFinished() { mutableTasks.update { tasks -> tasks.filter { it.active } } }

    private fun change(id: String, transform: (ImportTask<T>) -> ImportTask<T>) {
        mutableTasks.update { tasks -> tasks.map { if (it.id == id) transform(it) else it } }
    }
}
