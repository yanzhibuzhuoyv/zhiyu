package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Real private metadata, immutable PNG bytes and persisted trajectories; no models or API calls. */
class LocalDailyShareMediaNativeTest {
    @Test fun remoteStockRequiresReviewAndTheExactChatMarker() {
        DailyShareNativeFixture().use { f ->
            val tags=f.tags - "qwen_generated" + listOf("remote_generated","remote_chat_${f.chat.take(24)}")
            val picture=f.image(labels=tags)
            assertEquals(picture,f.selector.prepare(f.chat,f.event,emptySet()))
            f.rewriteMetadata(picture){it.put("tags",JSONArray(tags - "composite_reviewed"))}
            assertNull(f.selector.prepare(f.chat,f.event,emptySet()))
            f.rewriteMetadata(picture){it.put("tags",JSONArray(tags.filterNot {t->t.startsWith("remote_chat_")}+"remote_chat_wrong") )}
            assertNull(f.selector.prepare(f.chat,f.event,emptySet()))
        }
    }
    @Test fun selectsExactReviewedSlotWithoutRenderingOrAcceptingEmoji() {
        DailyShareNativeFixture().use { f ->
            f.image(kind = "sticker", color = 0xff328421.toInt())
            val picture = f.image()
            val before = f.assets.list("image").size
            assertEquals(picture, f.selector.prepare(f.chat, f.event, emptySet()))
            assertEquals(before, f.assets.list("image").size)
            assertArrayEquals(f.png(), f.assets.download(picture.id, picture.sha256))
            assertEquals(LocalDailyRules.revision(f.scene), LocalDailyRules.revision(f.selector.scene(f.chat)))
        }
    }

    @Test fun absentPicturesAndPreviouslyUsedActualShaNeverProduceASubstitute() {
        DailyShareNativeFixture().use { f ->
            assertNull(f.selector.prepare(f.chat, f.event, emptySet()))
            assertTrue(f.assets.list("image").isEmpty())
            val image = f.image()
            assertNull(f.selector.prepare(f.chat, f.event, setOf(image.sha256)))
        }
    }

    @Test fun rejectsEveryMissingOrConflictingStockReviewAndRevisionMarker() {
        val changes: List<(DailyShareNativeFixture) -> List<String>> = listOf(
            { it.tags - "daily_stock" }, { it.tags - "decoded_final" },
            { it.tags - "composite_reviewed" }, { it.tags + "local_placeholder" },
            { it.tags.filterNot { tag -> tag.startsWith("trajectory_") } + "trajectory_${"0".repeat(24)}" },
            { it.tags.filterNot { tag -> tag.startsWith("slot_") } + "slot_2359" },
            { it.tags + "slot_2359" }, { it.tags + "trajectory_${"0".repeat(24)}" })
        changes.forEach { change -> DailyShareNativeFixture().use { f ->
            f.image(labels = change(f))
            assertNull(f.selector.prepare(f.chat, f.event, emptySet()))
        } }
    }

    @Test fun rejectsWrongDayVersionPurposeAndUnreviewedQwenSources() {
        DailyShareNativeFixture().use { f ->
            f.image(day = "2026-10-11", color = 0xff338721.toInt())
            f.image(day = null, color = 0xff338722.toInt())
            f.image(version = 1, color = 0xff338723.toInt())
            f.image(purpose = "scene_background", color = 0xff338724.toInt())
            f.image(labels = f.tags - "qwen_generated" - "composite_reviewed", color = 0xff338725.toInt())
            assertNull(f.selector.prepare(f.chat, f.event, emptySet()))
        }
    }

    @Test fun wrongChatOrChangedSlotActivityCannotBorrowAnotherCharactersReadyPhoto() {
        DailyShareNativeFixture().use { f ->
            f.image()
            assertNull(f.selector.prepare(LocalMediaStore.identifier(), f.event, emptySet()))
            val altered = f.event.copy(slot = f.slot.copy(activity = "与原计划不同的事件"))
            assertNull(f.selector.prepare(f.chat, altered, emptySet()))
            assertNull(f.selector.prepare(f.chat, f.event.copy(sceneId = "0".repeat(32)), emptySet()))
            assertNull(f.selector.prepare(f.chat, f.event.copy(revision = "0".repeat(64)), emptySet()))
        }
    }

    @Test fun beforeDueAndAfterExpiryOrMidnightCannotShareAsIfTheSlotOccurred() {
        DailyShareNativeFixture().use { f ->
            f.image()
            for (time in listOf(f.due.minusSeconds(1), f.event.expires, Instant.parse("2026-10-12T16:00:00Z"))) {
                f.now = time
                assertNull(f.selector.prepare(f.chat, f.event, emptySet()))
            }
        }
    }

    @Test fun actualCorruptPixelsAreRejectedBeforeCaptionGeneration() {
        DailyShareNativeFixture().use { f ->
            val picture = f.image(); f.corruptBlob(picture)
            assertNull(f.selector.prepare(f.chat, f.event, emptySet()))
        }
    }

    @Test fun finalCommitRechecksTheExactPictureMetadataAndBytesBeforeCallingSave() {
        DailyShareNativeFixture().use { f ->
            val picture = f.image()
            assertEquals(picture, f.selector.prepare(f.chat, f.event, emptySet()))
            var calls = 0
            assertEquals(picture.id, f.selector.validateAndCommit(f.chat, f.event, picture) { calls++; it.id })
            f.rewriteMetadata(picture) { it.put("tags", JSONArray(f.tags + "local_placeholder")) }
            assertThrows(IllegalArgumentException::class.java) {
                f.selector.validateAndCommit(f.chat, f.event, picture) { calls++ }
            }
            assertEquals(1, calls)
        }
    }

    @Test fun finalCommitRejectsAnOCChangeAndExpiryWithoutInvokingSave() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val picture = f.image()
            f.visual.update(f.chat, "import", "测试外观", "female", "long")
            var calls = 0
            assertThrows(IllegalArgumentException::class.java) {
                f.selector.validateAndCommit(f.chat, f.event, picture) { calls++ }
            }
            assertEquals(0, calls)
        }
        DailyShareNativeFixture().use { f ->
            val picture = f.image(); f.now = f.event.expires
            var calls = 0
            assertThrows(IllegalArgumentException::class.java) {
                f.selector.validateAndCommit(f.chat, f.event, picture) { calls++ }
            }
            assertEquals(0, calls)
        }
    }

    @Test fun finalCommitRejectsCopiedAttachmentLabelsAndCorruption() {
        DailyShareNativeFixture().use { f ->
            val picture = f.image()
            assertThrows(IllegalArgumentException::class.java) {
                f.selector.validateAndCommit(f.chat, f.event, picture.copy(visualVersion = 1)) { fail("must not save") }
            }
            f.corruptBlob(picture)
            assertThrows(IllegalArgumentException::class.java) {
                f.selector.validateAndCommit(f.chat, f.event, picture) { fail("must not save") }
            }
        }
    }

    @Test fun finalCommitUsesChangedPersistedSceneRatherThanRecreatingTheOldSeededPlan() {
        DailyShareNativeFixture().use { f ->
            val picture = f.image()
            assertEquals(picture, f.selector.prepare(f.chat, f.event, emptySet()))
            f.changePersistedActivity()
            var calls = 0
            assertThrows(IllegalArgumentException::class.java) {
                f.selector.validateAndCommit(f.chat, f.event, picture) { calls++ }
            }
            assertEquals(0, calls)
            assertNull(f.selector.prepare(f.chat, f.event, emptySet()))
        }
    }
}
