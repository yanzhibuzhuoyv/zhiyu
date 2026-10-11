package com.zhiyu.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

class LocalMemoryRecognitionPaused : CancellationException("后台记忆识别已暂停")

/** Single small batch; cloud waits hold neither the chat Mutex nor a SQLite write transaction. */
class LocalMemoryRecognitionEngine(
    private val store: LocalMemoryRecognitionStore, private val recognizer: LocalMemoryRecognizer,
    private val foreground: StateFlow<Boolean>, private val authorized: () -> Boolean,
) {
    suspend fun run(identity: LocalMemoryRecognitionIdentity, batch: Int = 4): Int {
        require(batch in 1..8)
        if (foreground.value || !withContext(Dispatchers.IO) { authorized() }) return 0
        withContext(Dispatchers.IO) { store.recover(identity); store.enqueue(identity) }
        return coroutineScope {
            val ownerScope = this
            val visibility = launch { foreground.first { it }; ownerScope.cancel(LocalMemoryRecognitionPaused()) }
            val credentials = launch {
                while (isActive) {
                    delay(250)
                    if (!withContext(Dispatchers.IO) { authorized() }) ownerScope.cancel(LocalMemoryRecognitionPaused())
                }
            }
            try { batch(identity, batch) }
            finally { visibility.cancel(); credentials.cancel() }
        }
    }

    private suspend fun batch(identity: LocalMemoryRecognitionIdentity, size: Int): Int {
        var completed = 0
        repeat(size) {
            currentCoroutineContext().ensureActive()
            if (foreground.value || !withContext(Dispatchers.IO) { authorized() }) throw LocalMemoryRecognitionPaused()
            if (!process(identity)) return completed
            completed++
        }
        return completed
    }

    private suspend fun process(identity: LocalMemoryRecognitionIdentity): Boolean {
        var claimed: LocalMemoryRecognitionJob? = null
        try {
            withContext(NonCancellable + Dispatchers.IO) { claimed = store.claim(identity) }
            val job = claimed ?: return false
            currentCoroutineContext().ensureActive()
            val source = withContext(Dispatchers.IO) { store.original(job) } ?: run {
                withContext(NonCancellable + Dispatchers.IO) { store.release(job, false) }; claimed = null; return false
            }
            if (source.text.isBlank()) {
                currentCoroutineContext().ensureActive()
                val activeJob = currentCoroutineContext()[Job]!!
                val skipped = withContext(NonCancellable + Dispatchers.IO) {
                    store.skipNoText(job, source) { activeJob.isActive && !foreground.value && authorized() }
                }
                if (!skipped) withContext(NonCancellable + Dispatchers.IO) { store.release(job, true) }
                claimed = null
                return skipped
            }
            val window = LocalMemoryRecognitionRules.window(source, job.offset)
            val catalogue = withContext(Dispatchers.IO) { store.catalogue(job) }
            val annotations = withTimeout(60_000) { recognizer.recognize(window, catalogue) }
            currentCoroutineContext().ensureActive()
            val activeJob = currentCoroutineContext()[Job]!!
            val committed = withContext(NonCancellable + Dispatchers.IO) {
                store.finish(job, window, annotations) { activeJob.isActive && !foreground.value && authorized() }
            }
            if (!committed) withContext(NonCancellable + Dispatchers.IO) { store.release(job, true) }
            claimed = null
            return committed
        } catch (_: TimeoutCancellationException) {
            claimed?.let { withContext(NonCancellable + Dispatchers.IO) { store.release(it, false) } }
            return false
        } catch (cancelled: CancellationException) {
            claimed?.let { withContext(NonCancellable + Dispatchers.IO) { store.release(it, true) } }
            throw cancelled
        } catch (_: Exception) {
            claimed?.let { withContext(NonCancellable + Dispatchers.IO) { store.release(it, false) } }
            return false
        }
    }
}
