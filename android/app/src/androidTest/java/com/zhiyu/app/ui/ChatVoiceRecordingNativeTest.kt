package com.zhiyu.app.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.NativeV4Context
import com.zhiyu.app.data.voicemessages.VoiceClipState
import com.zhiyu.app.data.voicemessages.VoiceMessageStore
import com.zhiyu.app.domain.LocalMemoryCodec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** Real private SQLite/WAV and Main dispatcher; synthetic capture, no microphone/provider access. */
class ChatVoiceRecordingNativeTest {
    private class Recorder(private val delayedPreparation: Boolean = false,
        private val holdRecording: Boolean = false, private val failCapture: Boolean = false) : ChatVoiceRecorder {
        val entered = CompletableDeferred<Unit>()
        val prepared = CompletableDeferred<Unit>()
        val recorded = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val opened = AtomicInteger()
        val stopped = AtomicInteger()
        val text = "这条语音有真实保存的录音。"
        override suspend fun record(started: () -> Unit, partial: (String) -> Unit,
            samples: (FloatArray) -> Unit): String {
            entered.complete(Unit)
            if (delayedPreparation) prepared.await()
            currentCoroutineContext().ensureActive()
            opened.incrementAndGet(); started()
            val input = FloatArray(1600) { if (it % 2 == 0) .25f else -.5f }
            samples(input)
            // The recorder may reuse its capture buffer. The draft must own a PCM snapshot.
            input.fill(0f)
            partial("这条语音")
            recorded.complete(Unit)
            if (holdRecording) finish.await()
            if (failCapture) throw IllegalStateException("Synthetic capture failed after returning PCM")
            return text
        }
        override fun stop() { stopped.incrementAndGet(); finish.complete(Unit) }
    }

    private class Fixture(val recorder: Recorder = Recorder(), dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = NativeV4Context(base)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = VoiceMessageStore(context)
        val controller = ChatVoiceController(context, scope, recorder, store)
        fun waves() = File(context.filesDir, "voice_messages").listFiles()?.filter { it.extension == "wav" }.orEmpty()
        suspend fun close() {
            withContext(Dispatchers.Main) { controller.cancel() }
            // The production cleanup worker is process-scoped; let its queued SQLite discard finish
            // before deleting the isolated root, otherwise it can recreate a database file mid-delete.
            UnsentVoiceCleanup.awaitIdle()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            context.close()
            val root = context.filesDir.canonicalFile
            check(root.parentFile == base.filesDir.canonicalFile && root.name.startsWith("v4_"))
            check(root.deleteRecursively())
        }
    }

    private suspend fun awaitReady(test: Fixture) = withTimeout(5_000) {
        while (withContext(Dispatchers.Main) { test.controller.phase != ChatVoicePhase.READY }) delay(10)
    }
    private suspend fun awaitCaptureEnded(test: Fixture) = withTimeout(5_000) {
        while (test.scope.coroutineContext[Job]!!.children.any { !it.isCompleted }) delay(10)
    }

    @Test fun actualPcmDraftSurvivesQueueRejectionAndTransfersOnlyAfterAcceptance(): Unit = runBlocking {
        val test = Fixture()
        try {
            withContext(Dispatchers.Main) { test.controller.start() }
            awaitReady(test)
            val draft = withContext(Dispatchers.Main) { test.controller.draft!! }
            assertEquals(VoiceClipState.READY, draft.state)
            assertEquals(100L, draft.durationMillis)
            assertEquals(LocalMemoryCodec.sha(LocalMemoryCodec.bytes(test.recorder.text)), draft.sourceSha)
            // Read the exact PCM a preview would consume, without claiming physical playback.
            val audio = withContext(Dispatchers.IO) { test.store.read(draft.id) }
            assertEquals(16000, audio.sampleRate)
            assertEquals(1600, audio.samples.size)
            assertEquals(.25f, audio.samples[0], 1f / 32767f)
            assertEquals(-.5f, audio.samples[1], 1f / 32767f)
            val submissions = mutableListOf<Pair<String, String>>()
            withContext(Dispatchers.Main) {
                test.controller.send { text, clip -> submissions.add(text to clip); false }
                assertEquals(draft, test.controller.draft)
                assertEquals(ChatVoicePhase.READY, test.controller.phase)
                test.controller.send { text, clip -> submissions.add(text to clip); true }
                assertNull(test.controller.draft)
                assertEquals(ChatVoicePhase.IDLE, test.controller.phase)
                test.controller.cancel()
            }
            assertEquals(List(2) { test.recorder.text to draft.id }, submissions)
            assertEquals(draft, VoiceMessageStore(test.context).find(draft.id))
            assertEquals(1600, test.store.read(draft.id).samples.size)
        } finally { test.close() }
    }

    @Test fun aSuspendedDurableHandoffKeepsTheRecordingUntilAcceptance(): Unit = runBlocking {
        val test = Fixture()
        val accepted = CompletableDeferred<Boolean>()
        try {
            withContext(Dispatchers.Main) { test.controller.start() }
            awaitReady(test)
            val clip = withContext(Dispatchers.Main) { test.controller.draft!! }
            withContext(Dispatchers.Main) {
                test.controller.send { _, _ -> accepted.await() }
                assertEquals(ChatVoicePhase.SENDING, test.controller.phase)
                test.controller.cancel()
                assertEquals(clip, test.controller.draft)
            }
            assertNotNull(test.store.find(clip.id))
            accepted.complete(false)
            withTimeout(5_000) {
                while (withContext(Dispatchers.Main) { test.controller.phase != ChatVoicePhase.READY }) delay(10)
            }
            withContext(Dispatchers.Main) {
                assertEquals(clip, test.controller.draft)
                test.controller.send { _, _ -> true }
                assertNull(test.controller.draft)
                assertEquals(ChatVoicePhase.IDLE, test.controller.phase)
            }
            assertNotNull(test.store.find(clip.id))
        } finally { accepted.complete(false); test.close() }
    }

    @Test fun finishWhilePreparingNeverStartsALateRecorder(): Unit = runBlocking {
        val test = Fixture(Recorder(delayedPreparation = true))
        try {
            withContext(Dispatchers.Main) { test.controller.start() }
            withTimeout(5_000) { test.recorder.entered.await() }
            withContext(Dispatchers.Main) {
                assertEquals(ChatVoicePhase.PREPARING, test.controller.phase)
                test.controller.finish()
            }
            test.recorder.prepared.complete(Unit)
            awaitCaptureEnded(test)
            assertEquals(0, test.recorder.opened.get())
            withContext(Dispatchers.Main) {
                assertEquals(ChatVoicePhase.IDLE, test.controller.phase)
                assertNull(test.controller.draft)
            }
            assertTrue(test.waves().isEmpty())
        } finally { test.recorder.prepared.complete(Unit); test.close() }
    }

    @Test fun cancellationDuringRecordingCannotPublishLatePcmOrADraft(): Unit = runBlocking {
        val test = Fixture(Recorder(holdRecording = true))
        try {
            withContext(Dispatchers.Main) { test.controller.start() }
            withTimeout(5_000) { test.recorder.recorded.await() }
            withContext(Dispatchers.Main) { test.controller.cancel() }
            awaitCaptureEnded(test)
            assertEquals(1, test.recorder.opened.get())
            assertTrue(test.recorder.stopped.get() > 0)
            withContext(Dispatchers.Main) {
                assertEquals(ChatVoicePhase.IDLE, test.controller.phase)
                assertNull(test.controller.draft)
            }
            assertTrue(test.waves().isEmpty())
        } finally { test.close() }
    }

    @Test fun onStopPausePreservesAReadyRecordingForForegroundResume(): Unit = runBlocking {
        val test = Fixture()
        try {
            withContext(Dispatchers.Main) { test.controller.start() }
            awaitReady(test)
            val draft = withContext(Dispatchers.Main) { test.controller.draft!! }
            // ZhiYuApp's real ON_STOP observer calls this production pause entry point.
            withContext(Dispatchers.Main) {
                test.controller.pause()
                assertEquals(ChatVoicePhase.READY, test.controller.phase)
                assertEquals(draft, test.controller.draft)
                assertEquals(test.recorder.text, test.controller.transcript)
            }
            assertEquals(draft, test.store.find(draft.id))
            assertEquals(1600, test.store.read(draft.id).samples.size)
        } finally { test.close() }
    }

    @Test fun captureFailureCannotCreateAPlayableDraftFromAnUnfinishedTranscript(): Unit = runBlocking {
        val test = Fixture(Recorder(failCapture = true))
        try {
            withContext(Dispatchers.Main) { test.controller.start() }
            awaitCaptureEnded(test)
            withContext(Dispatchers.Main) {
                assertEquals(ChatVoicePhase.IDLE, test.controller.phase)
                assertNull(test.controller.draft)
                assertNotNull(test.controller.error)
            }
            assertTrue(test.waves().isEmpty())
        } finally { test.close() }
    }

    @Test fun disposingAfterTheScreenScopeEndsStillDiscardsAnUnsentReadyDraft(): Unit = runBlocking {
        val test = Fixture()
        try {
            withContext(Dispatchers.Main) { test.controller.start() }
            awaitReady(test)
            val draft = withContext(Dispatchers.Main) { test.controller.draft!! }
            test.scope.coroutineContext[Job]!!.cancelAndJoin()
            withContext(Dispatchers.Main) { test.controller.cancel() }
            withTimeoutOrNull(2_000) { while (test.store.find(draft.id) != null) delay(10) }
            assertNull("A cancelled screen scope cannot own the only local cleanup task", test.store.find(draft.id))
            assertTrue(test.waves().isEmpty())
        } finally { test.close() }
    }

    @Test fun cancellationAtTheCommittedWavReturnBoundaryDoesNotLeaveAnOrphan(): Unit = runBlocking {
        val committedReturnCancelled = AtomicBoolean(false)
        lateinit var test: Fixture
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Dispatchers.Main.dispatch(context, Runnable {
                    if (test.waves().isNotEmpty() && committedReturnCancelled.compareAndSet(false, true))
                        context[Job]?.cancel(CancellationException("synthetic cancellation after WAV commit before Main result"))
                    block.run()
                })
            }
        }
        test = Fixture(dispatcher = dispatcher)
        try {
            withContext(Dispatchers.Main) { test.controller.start() }
            awaitCaptureEnded(test)
            assertTrue("The fixture must cancel only after the WAV has actually been committed", committedReturnCancelled.get())
            assertTrue("Prompt cancellation must still clean the just-committed unowned WAV", test.waves().isEmpty())
        } finally { test.close() }
    }
}
