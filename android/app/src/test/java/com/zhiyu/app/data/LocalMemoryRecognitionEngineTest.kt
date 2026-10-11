package com.zhiyu.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic sources and fake models; never calls Android storage or paid APIs. */
class LocalMemoryRecognitionEngineTest {
    private val identity = LocalMemoryRecognitionIdentity("owner", "chat", "deepseek", "bound-model")

    @Test fun noKeyAndVisibleAppDoNotQueueOrCallCloud() = runBlocking {
        val store = FakeStore()
        val calls = AtomicInteger()
        val recognizer = LocalMemoryRecognizer { _, _ -> calls.incrementAndGet(); emptyList() }
        assertEquals(0, LocalMemoryRecognitionEngine(store, recognizer, MutableStateFlow(false)) { false }.run(identity))
        assertEquals(0, LocalMemoryRecognitionEngine(store, recognizer, MutableStateFlow(true)) { true }.run(identity))
        assertEquals(0, store.queued.get()); assertEquals(0, calls.get())
    }
    @Test fun oneBatchIsBoundedAndCloudHasNoStoreMutationHeld() = runBlocking {
        val store = FakeStore(10)
        val calls = AtomicInteger()
        val recognizer = LocalMemoryRecognizer { _, _ ->
            assertFalse(store.mutating.get()); calls.incrementAndGet(); emptyList()
        }
        assertEquals(4, LocalMemoryRecognitionEngine(store, recognizer, MutableStateFlow(false)) { true }.run(identity))
        assertEquals(4, calls.get()); assertEquals(4, store.committed.get()); assertTrue(store.pending(identity))
    }
    @Test fun blankSourcesTerminateWithoutCloudAndStillConsumeTheBatchBudget() = runBlocking {
        val store = FakeStore(10, " \n\t\u3000 "); val calls = AtomicInteger()
        val recognizer = LocalMemoryRecognizer { _, _ -> calls.incrementAndGet(); emptyList() }
        assertEquals(4, LocalMemoryRecognitionEngine(store, recognizer, MutableStateFlow(false)) { true }.run(identity))
        assertEquals(4, store.skipped.get()); assertEquals(0, store.committed.get())
        assertEquals(0, calls.get()); assertEquals(0, store.catalogued.get()); assertTrue(store.pending(identity))
    }
    @Test fun rejectedBlankSkipReleasesTheLeaseWithoutClaimingCompletion() = runBlocking {
        val store = FakeStore(text = "").apply { rejectCommit = true }; val calls = AtomicInteger()
        val recognizer = LocalMemoryRecognizer { _, _ -> calls.incrementAndGet(); emptyList() }
        assertEquals(0, LocalMemoryRecognitionEngine(store, recognizer, MutableStateFlow(false)) { true }.run(identity))
        assertEquals(listOf(true), store.releases); assertEquals(0, store.skipped.get()); assertEquals(0, calls.get())
    }
    @Test fun foregroundArrivalCancelsNetworkAndReleasesLeaseWithoutBurningAttempt() = runBlocking {
        val store = FakeStore(); val foreground = MutableStateFlow(false); val entered = CompletableDeferred<Unit>()
        val recognizer = LocalMemoryRecognizer { _, _ -> entered.complete(Unit); awaitCancellation() }
        val task = async { LocalMemoryRecognitionEngine(store, recognizer, foreground) { true }.run(identity) }
        withTimeout(3000) { entered.await(); foreground.value = true; task.join() }
        assertTrue(task.isCancelled); assertEquals(listOf(true), store.releases); assertEquals(0, store.committed.get())
    }
    @Test fun changedCredentialCancelsEvenWhileModelIsWaiting() = runBlocking {
        val store = FakeStore(); val allowed = AtomicBoolean(true); val entered = CompletableDeferred<Unit>()
        val recognizer = LocalMemoryRecognizer { _, _ -> entered.complete(Unit); awaitCancellation() }
        val task = async { LocalMemoryRecognitionEngine(store, recognizer, MutableStateFlow(false), allowed::get).run(identity) }
        withTimeout(3000) { entered.await(); allowed.set(false); task.join() }
        assertTrue(task.isCancelled); assertEquals(listOf(true), store.releases); assertEquals(0, store.committed.get())
    }
    @Test fun lateNonCancellableResultAfterRevocationCannotCommit() = runBlocking {
        val store = FakeStore(); val allowed = AtomicBoolean(true)
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val recognizer = LocalMemoryRecognizer { _, _ ->
            withContext(NonCancellable) { entered.complete(Unit); resume.await(); emptyList() }
        }
        val task = async { LocalMemoryRecognitionEngine(store, recognizer, MutableStateFlow(false), allowed::get).run(identity) }
        withTimeout(3000) { entered.await(); allowed.set(false); delay(400); resume.complete(Unit); task.join() }
        assertTrue(task.isCancelled); assertEquals(0, store.committed.get()); assertEquals(listOf(true), store.releases)
    }
    @Test fun invalidModelResponseReleasesSourceForBoundedRetry() = runBlocking {
        val store = FakeStore()
        val recognizer = LocalMemoryRecognizer { _, _ -> throw IllegalArgumentException("synthetic invalid schema") }
        assertEquals(0, LocalMemoryRecognitionEngine(store, recognizer, MutableStateFlow(false)) { true }.run(identity))
        assertEquals(listOf(false), store.releases); assertEquals(0, store.committed.get())
    }
    @Test fun staleOriginalNeverReachesModelAndCasRejectionReleasesLease() = runBlocking {
        val missing = FakeStore().apply { unavailable = true }
        val calls = AtomicInteger(); val recognizer = LocalMemoryRecognizer { _, _ -> calls.incrementAndGet(); emptyList() }
        assertEquals(0, LocalMemoryRecognitionEngine(missing, recognizer, MutableStateFlow(false)) { true }.run(identity))
        assertEquals(0, calls.get()); assertEquals(listOf(false), missing.releases)
        val changed = FakeStore().apply { rejectCommit = true }
        assertEquals(0, LocalMemoryRecognitionEngine(changed, recognizer, MutableStateFlow(false)) { true }.run(identity))
        assertEquals(listOf(true), changed.releases); assertEquals(0, changed.committed.get())
    }
    @Test fun restartContinuesEveryLongWindowThroughSurrogateAndFinalTail() = runBlocking {
        val text = "甲".repeat(15999) + "🐈" + "乙".repeat(17000) + "最后生日原话"
        val store = FakeStore(text = text); val observed = mutableListOf<String>()
        val recognizer = LocalMemoryRecognizer { window, _ -> observed.add(window.text); emptyList() }
        val engine = LocalMemoryRecognitionEngine(store, recognizer, MutableStateFlow(false)) { true }
        while (store.pending(identity)) engine.run(identity, 1)
        assertEquals(text, observed.joinToString("")); assertEquals(3, observed.size)
        assertEquals(3, store.committed.get()); assertFalse(store.pending(identity))
    }

    private class FakeStore(count: Int = 1, text: String = "今天想吃苹果") : LocalMemoryRecognitionStore {
        val queued = AtomicInteger(); val committed = AtomicInteger(); val mutating = AtomicBoolean()
        val skipped = AtomicInteger(); val catalogued = AtomicInteger()
        val releases = mutableListOf<Boolean>(); var unavailable = false; var rejectCommit = false
        private val sources = List(count) {
            LocalMemoryOriginal("owner", "chat", "message-$it", "user", "2026-10-01T00:00:00Z", LocalMemoryRecognitionRules.sha(text), 0, text)
        }
        private var index = 0; private var offset = 0; private var current: LocalMemoryRecognitionJob? = null
        override fun enqueue(identity: LocalMemoryRecognitionIdentity, limit: Int) { queued.incrementAndGet() }
        override fun recover(identity: LocalMemoryRecognitionIdentity) = Unit
        override fun claim(identity: LocalMemoryRecognitionIdentity): LocalMemoryRecognitionJob? {
            if (index >= sources.size || current != null) return null
            val source = sources[index]
            return LocalMemoryRecognitionJob("job-$index", identity.owner, identity.conversationId, source.messageId,
                identity.providerId, identity.model, source.sha256, source.revision, offset, "lease", 1, source.role, source.createdAt).also { current = it }
        }
        override fun original(job: LocalMemoryRecognitionJob) = if (unavailable) null else sources[index]
        override fun catalogue(job: LocalMemoryRecognitionJob):List<LocalMemoryTopic> {
            catalogued.incrementAndGet(); return emptyList()
        }
        override fun skipNoText(job: LocalMemoryRecognitionJob, source: LocalMemoryOriginal, authorized: () -> Boolean):Boolean {
            if (!authorized() || rejectCommit || source != sources[index] || source.text.isNotBlank() || current != job) return false
            skipped.incrementAndGet(); index++; offset = 0; current = null
            return true
        }
        override fun finish(job: LocalMemoryRecognitionJob, window: LocalMemoryWindow, annotations: List<LocalMemoryAnnotation>, authorized: () -> Boolean): Boolean {
            mutating.set(true)
            try {
                if (!authorized() || rejectCommit) return false
                offset = window.end; committed.incrementAndGet(); current = null
                if (offset == sources[index].text.length) { offset = 0; index++ }
                return true
            } finally { mutating.set(false) }
        }
        override fun release(job: LocalMemoryRecognitionJob, retry: Boolean) { releases.add(retry); current = null }
        override fun pending(identity: LocalMemoryRecognitionIdentity) = index < sources.size
    }
}
