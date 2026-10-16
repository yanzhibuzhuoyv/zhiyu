package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.voicemessages.*
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.VoiceTuningSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class VoiceMessageStoreNativeTest {
    private class Fixture(val maxBytes: Long = 512L * 1024 * 1024) : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val directory = "voice_test_$suffix"
        val database = "voice_test_$suffix.sqlite3"
        val store = VoiceMessageStore(context, directory, database, maxBytes)
        fun reopen() = VoiceMessageStore(context, directory, database, maxBytes)
        override fun close() {
            val root = File(context.filesDir, directory).canonicalFile
            require(root.parentFile == context.filesDir.canonicalFile)
            root.deleteRecursively()
            context.deleteDatabase(database)
        }
    }
    private fun sha(text: String) = LocalMemoryCodec.sha(LocalMemoryCodec.bytes(text))

    @Test fun aRecordedDraftExposesTheTranscriptHashBeforeItsFirstSend() {
        Fixture().use { test ->
            val text = "这条录音准备发送"
            val draft = test.store.recordDraft(FloatArray(1600), 16000, text)
            assertEquals("The send gate must be able to verify an unbound recording", sha(text), draft.sourceSha)
            assertEquals(sha(text), test.reopen().find(draft.id)?.sourceSha)
            assertNull(draft.owner)
        }
    }

    @Test fun recordedDraftSurvivesStoreRecreationAndReturnsVerifiedMonoPcm() {
        Fixture().use { test ->
            val input = FloatArray(1600) { if (it % 2 == 0) .25f else -.5f }
            val draft = test.store.recordDraft(input, 16000, "我发来一条合成语音")
            assertTrue(draft.id.matches(Regex("[a-f0-9]{32}")))
            assertEquals(VoiceClipState.READY, draft.state)
            assertEquals(100L, draft.durationMillis)
            assertNull(draft.owner)
            assertEquals(draft, test.reopen().find(draft.id))
            val audio = test.reopen().read(draft.id)
            assertEquals(16000, audio.sampleRate)
            assertEquals(input.size, audio.samples.size)
            input.indices.forEach { assertEquals(input[it], audio.samples[it], 1f / 32767f) }
        }
    }

    @Test fun onlyMatchingOriginalCanBindAndBoundAudioCannotBeDiscarded() {
        Fixture().use { test ->
            val draft = test.store.recordDraft(FloatArray(160), 16000, "合成原话")
            assertThrows(IllegalArgumentException::class.java) {
                test.store.linkDraft(draft.id, "owner", "chat", "message", sha("另一句"))
            }
            val linked = test.store.linkDraft(draft.id, "owner", "chat", "message", sha("合成原话"))
            assertEquals("owner", linked.owner)
            assertEquals(linked, test.store.linkDraft(draft.id, "owner", "chat", "message", sha("合成原话")))
            assertThrows(IllegalArgumentException::class.java) {
                test.store.linkDraft(draft.id, "other", "chat", "message", sha("合成原话"))
            }
            test.store.discardDraft(draft.id)
            assertEquals(linked, test.store.find(draft.id))
            assertEquals(160, test.store.read(draft.id).samples.size)
        }
    }

    @Test fun corruptionAndOutOfScopePathsFailBeforeReturningAudio() {
        Fixture().use { test ->
            val draft = test.store.recordDraft(FloatArray(1600), 16000, "校验")
            val file = File(File(test.context.filesDir, test.directory), "${draft.id}.wav")
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = 1
            file.writeBytes(bytes)
            assertThrows(IllegalArgumentException::class.java) { test.store.read(draft.id) }
            assertThrows(IllegalArgumentException::class.java) { test.store.read("../escape") }
            assertThrows(IllegalArgumentException::class.java) {
                test.store.recordDraft(floatArrayOf(Float.NaN), 16000, "校验")
            }
            assertThrows(IllegalArgumentException::class.java) {
                test.store.recordDraft(FloatArray(16000 * 60 + 1), 16000, "太长")
            }
        }
    }

    @Test fun boundedCapacityDoesNotDeletePreviouslySentAudio() {
        Fixture(96L * 1024).use { test ->
            val draft = test.store.recordDraft(FloatArray(1600), 16000, "保留")
            test.store.linkDraft(draft.id, "owner", "chat", "message", sha("保留"))
            assertThrows(IllegalStateException::class.java) {
                test.store.recordDraft(FloatArray(48000), 16000, "容量已满")
            }
            assertEquals(1600, test.store.read(draft.id).samples.size)
        }
    }

    @Test fun replyPublicationRechecksSourceAndKeepsTheChosenPitchAndVolume() {
        Fixture().use { test ->
            val id = "b".repeat(32)
            val tuning = VoiceTuningSettings(speed = .9f, pitchSemitones = 2f, volume = .4f)
            val pending = test.store.prepareReply(id, "owner", "chat", "reply", sha("嗯"), tuning)
            assertEquals(VoiceClipState.PENDING, pending.state)
            assertTrue(test.store.claim(id))
            assertFalse(test.store.claim(id))
            val audio = LocalVoiceAudio(FloatArray(1600) { .1f }, 16000)
            assertThrows(IllegalStateException::class.java) { test.store.completeReply(id, audio) { false } }
            assertEquals(VoiceClipState.PENDING, test.store.find(id)!!.state)
            val ready = test.store.completeReply(id, audio) { true }
            assertEquals(VoiceClipState.READY, ready.state)
            assertEquals(2f, ready.pitchSemitones)
            assertEquals(.4f, ready.volume)
            assertEquals(1600, test.reopen().read(id).samples.size)
        }
    }
}
