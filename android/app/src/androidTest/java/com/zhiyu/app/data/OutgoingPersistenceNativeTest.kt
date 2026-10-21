package com.zhiyu.app.data

import com.zhiyu.app.data.voicemessages.VoiceMessageStore
import com.zhiyu.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class OutgoingPersistenceNativeTest {
    private val owner = "synthetic-outbox-owner"

    @Test fun pendingWholeOriginalSurvivesArchiveCloseAndReopen() {
        DailyShareNativeFixture().use { f ->
            val text = "保留每一句原话🍎。".repeat(1200)
            val draft = LocalChatDatabase(f.context).use { archive ->
                val chat = archive.ensureConversation(owner, "synthetic-model", CompanionProfile())
                draft(chat.id, text).also { LocalOutgoingStore(archive, owner).save(it) }
            }
            LocalChatDatabase(f.context).use { reopened ->
                val restored = LocalOutgoingStore(reopened, owner).restore().single()
                assertEquals(draft.content, restored.content)
                assertEquals(draft.id, restored.id)
                assertEquals(draft.textBatchId, restored.textBatchId)
                assertEquals(OutgoingPhase.FAILED, restored.phase) // User retry, never an automatic paid request.
            }
        }
    }

    @Test fun committedUnknownNeverBecomesRetryableAndCompletedBatchDisappears() {
        DailyShareNativeFixture().use { f -> LocalChatDatabase(f.context).use { archive ->
            val chat = archive.ensureConversation(owner, "synthetic-model", CompanionProfile())
            val store = LocalOutgoingStore(archive, owner)
            val first = draft(chat.id, "第一句")
            val second = draft(chat.id, "第二句").copy(textBatchId = first.textBatchId)
            val third = draft(chat.id, "尚未发出的第三句")
            listOf(first, second, third).forEach(store::save)
            archive.appendQueuedTextBatch(owner, chat.id, listOf(first, second).map { QueuedTextInput(it.id, it.content) }) {}
            assertEquals(listOf(OutgoingPhase.SUBMITTED_NO_REPLY, OutgoingPhase.SUBMITTED_NO_REPLY, OutgoingPhase.FAILED), store.restore().map { it.phase })
            archive.append(owner, chat.id, "assistant", "一起回答前两句", requestId = "outgoing-reply-${first.id}")
            assertEquals(listOf(third.id), store.restore().map { it.id })
            assertEquals(3, archive.history(owner, chat.id).messages.size)
        } }
    }

    @Test fun queuedPhotoAndVoiceRemainPinnedUntilExplicitDiscard() {
        DailyShareNativeFixture().use { f -> LocalChatDatabase(f.context).use { archive ->
            val chat = archive.ensureConversation(owner, "synthetic-model", CompanionProfile())
            val store = LocalOutgoingStore(archive, owner)
            val image = f.image(purpose = "chat", day = null)
            val photo = draft(chat.id, "").copy(image = ChatImageDraft(image), textBatchId = null)
            store.save(photo)
            assertTrue(image.id in archive.pinnedMediaIds())
            val voices = VoiceMessageStore(f.context)
            val clip = voices.recordDraft(FloatArray(1600) { 0.05f }, 16000, "语音原话")
            val voice = draft(chat.id, "语音原话").copy(voiceDraftId = clip.id, textBatchId = null)
            store.save(voice)
            voices.discardDraft(clip.id)
            assertNotNull(voices.find(clip.id))
            assertEquals(image, store.restore().first().image!!.attachment)
            store.remove(voice.id); voices.discardDraft(clip.id)
            assertNull(voices.find(clip.id))
            store.remove(photo.id)
            assertFalse(image.id in archive.pinnedMediaIds())
        } }
    }

    @Test fun capacityAndRequestIdentityNeverOverwriteExistingOriginals() {
        DailyShareNativeFixture().use { f -> LocalChatDatabase(f.context).use { archive ->
            val chat = archive.ensureConversation(owner, "synthetic-model", CompanionProfile())
            val store = LocalOutgoingStore(archive, owner)
            val first = draft(chat.id, "不可被覆盖")
            store.save(first); store.save(first)
            assertThrows(IllegalStateException::class.java) { store.save(first.copy(content = "另一句")) }
            repeat(31) { store.save(draft(chat.id, "排队消息$it")) }
            assertThrows(IllegalStateException::class.java) { store.save(draft(chat.id, "超过容量")) }
            assertEquals(32, store.restore().size)
            assertEquals(first.content, store.restore().first().content)
            assertTrue(LocalOutgoingStore(archive, "different-owner").restore().isEmpty())
        } }
    }

    @Test fun schemaThreeUpgradeKeepsOriginalsAndCreatesTheNewStores() {
        DailyShareNativeFixture().use { f ->
            val original = LocalChatDatabase(f.context).use { archive ->
                val chat = archive.ensureConversation(owner, "synthetic-model", CompanionProfile())
                val row = archive.append(owner, chat.id, "user", "升级前的完整原文🍎")
                archive.writableDatabase.execSQL("DROP TABLE outgoing_drafts")
                archive.writableDatabase.execSQL("DROP TABLE photo_deliveries")
                archive.writableDatabase.execSQL("DROP TABLE photo_delivery_migrations")
                archive.writableDatabase.version = 3
                chat.id to row
            }
            LocalChatDatabase(f.context).use { upgraded ->
                assertEquals(4, upgraded.readableDatabase.version)
                assertEquals(original.second, upgraded.history(owner, original.first).messages.single())
                val pending = draft(original.first, "升级后仍能保存")
                LocalOutgoingStore(upgraded, owner).save(pending)
                assertEquals(pending.id, LocalOutgoingStore(upgraded, owner).restore().single().id)
                assertTrue(LocalPhotoDeliveryStore(f.context, upgraded).delivered(original.first, setOf("a".repeat(64))).isEmpty())
            }
        }
    }

    private fun draft(chat: String, content: String): OutgoingDraft {
        val id = LocalMediaStore.identifier()
        return OutgoingDraft(id, chat, 0, LocalChatRepository.LOCAL_URL, "synthetic", "synthetic-model", content, textBatchId = id)
    }
}
