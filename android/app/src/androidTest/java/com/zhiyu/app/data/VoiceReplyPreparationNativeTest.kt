package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.voicemessages.*
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.VoiceTuningSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class VoiceReplyPreparationNativeTest {
    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val dir = "voice_prepare_$suffix"
        val database = "voice_prepare_$suffix.sqlite3"
        val store = VoiceMessageStore(context, dir, database)
        val tuning = VoiceTuningSettings(speed = 1.05f, pitchSemitones = -1f, volume = .6f)
        var original = LocalMemoryOriginal("owner", "chat", "message", "assistant", "2026-10-16T04:00:00Z",
            LocalMemoryCodec.sha(LocalMemoryCodec.bytes("嗯。")), 0, "嗯。", origin = VoiceMessageOrigins.reply(suffix))
        init { store.prepareReply(suffix, "owner", "chat", "message", original.sha256, tuning) }
        override fun close() {
            val root = File(context.filesDir, dir).canonicalFile
            require(root.parentFile == context.filesDir.canonicalFile)
            root.deleteRecursively(); context.deleteDatabase(database)
        }
    }
    @Test fun bundledLocalTtsPreparesAnActualWavAndFreezesItsPlaybackTuning() = runBlocking {
        Fixture().use { test ->
            LocalVoiceEngine(test.context).use { engine ->
                val preparation = VoiceReplyPreparation(test.store, { test.original }, { 10 to test.tuning },
                    { text, voice, tuning, active -> engine.synthesize(text, voice, tuning, active) })
                preparation.run(test.suffix, "owner", "chat", "message", test.original.sha256)
            }
            val ready = test.store.find(test.suffix)!!
            assertEquals(VoiceClipState.READY, ready.state)
            assertEquals(-1f, ready.pitchSemitones)
            assertEquals(.6f, ready.volume)
            val audio = test.store.read(test.suffix)
            assertTrue(audio.samples.any { it != 0f })
            assertTrue(ready.durationMillis > 0)
        }
    }
    @Test fun sourceChangesDuringSynthesisCannotPublishAnAudioReply() = runBlocking {
        Fixture().use { test ->
            val sha = test.original.sha256
            val preparation = VoiceReplyPreparation(test.store, { test.original }, { 10 to test.tuning },
                { _, _, _, _ ->
                    test.original = test.original.copy(revision = 1)
                    LocalVoiceAudio(FloatArray(1600) { .1f }, 16000)
                })
            var failed = false
            try { preparation.run(test.suffix, "owner", "chat", "message", sha) }
            catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
            assertEquals(VoiceClipState.PENDING, test.store.find(test.suffix)!!.state)
            assertFalse(File(File(test.context.filesDir, test.dir), "${test.suffix}.wav").exists())
        }
    }
    @Test fun replayingAnAlreadyPreparedReplyDoesNotRunSynthesisAgain() = runBlocking {
        Fixture().use { test ->
            var calls = 0
            val preparation = VoiceReplyPreparation(test.store, { test.original }, { 10 to test.tuning },
                { _, _, _, _ -> calls++; LocalVoiceAudio(FloatArray(1600) { .1f }, 16000) })
            repeat(2) { preparation.run(test.suffix, "owner", "chat", "message", test.original.sha256) }
            assertEquals(1, calls)
            assertEquals(VoiceClipState.READY, test.store.find(test.suffix)!!.state)
        }
    }

    @Test fun anInterruptedClaimBecomesTerminalWithoutSynthesizingASecondTime() = runBlocking {
        Fixture().use { test ->
            assertTrue(test.store.claim(test.suffix, test.tuning))
            assertEquals(VoiceClipState.PENDING, test.store.find(test.suffix)!!.state)
            var calls = 0
            val preparation = VoiceReplyPreparation(test.store, { test.original }, { 10 to test.tuning },
                { _, _, _, _ -> calls++; LocalVoiceAudio(FloatArray(1600) { .1f }, 16000) })
            repeat(2) { preparation.run(test.suffix, "owner", "chat", "message", test.original.sha256) }
            val terminal = test.store.find(test.suffix)!!
            assertEquals(VoiceClipState.FAILED, terminal.state)
            assertEquals("interrupted", terminal.errorCode)
            assertEquals("Recovery must not repeat an already claimed synthesis", 0, calls)
            assertFalse(File(File(test.context.filesDir, test.dir), "${test.suffix}.wav").exists())
        }
    }
}
