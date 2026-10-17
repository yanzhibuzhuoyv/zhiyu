package com.zhiyu.app.data

import com.zhiyu.app.data.remotescene.*
import com.zhiyu.app.data.selfie.LocalPhotoBorrowStore
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Approved synthetic pixels and a real local source ledger; no key or network request is used. */
class SelfieBorrowNativeTest {
    private data class Future(val target: LocalScenePrefetchTarget, val image: MediaAttachment, val request: String)
    private fun future(f: DailyShareNativeFixture, profile: VisualProfile, reviewed: Boolean = true): Future {
        val scene = f.daily.horizon(f.chat, profile.visualVersion, f.now)[1]
        val target = LocalScenePrefetchTarget(scene, 1, scene.slots[1], true)
        val source = f.assets.details(profile.referenceId!!).attachment
        val identity = OcCreatorStore(f.context).read(f.chat)?.takeIf { it.identity.confirmed &&
            it.identity.reference?.id == profile.referenceId && it.identity.reference?.visualVersion == profile.visualVersion }
        val framing = com.zhiyu.app.data.ocexpansion.OcFramingStore(f.context).active(f.chat, profile)
        val original = framing?.let(OcFramingRules::reference) ?: identity?.identity?.photoSource
            ?: OcAssetRef(source.id, source.sha256, source.visualVersion)
        val binding = RemoteSceneBinding(f.chat, profile, identity?.packageSha, original,
            "synthetic-image-key", Image2Protocol.DEFAULT_MODEL,
            SavedConnection("local://phone", "deepseek", "synthetic-chat-key", "deepseek-v4-flash"),
            CompanionBinding("deepseek", "deepseek-v4-flash"), f.assets.download(original.id, original.sha256), framingPolicy = framing)
        val request = RemoteSceneRules.requestId(binding, target, 0)
        val bytes = f.png(0xff3c5279.toInt())
        LocalImageJobStore(f.context).use { ledger ->
            ledger.reserve(LocalGenerationJob(request, f.chat, "scene", profile.visualVersion, scene.dayId,
                RemoteSceneRules.metadataHash(request), "reserved"))
            ledger.finish(request, "running")
            RemoteSceneCandidateStore(f.context).write(RemoteSceneCandidate(request, f.chat, RemoteSceneRules.bindingSha(binding),
                ChatPhotoRules.sha(bytes), "image/png", "The same OC in a new portrait by the window.", 0, scene.dayId,
                target.slot.time, LocalDailyRules.revision(scene),
                if (reviewed) LifePhotoReviewStatus.APPROVED else LifePhotoReviewStatus.UNKNOWN,
                "synthetic pixel review", originalId = original.id, originalSha = original.sha256,
                visualVersion = profile.visualVersion, packageSha = identity?.packageSha), bytes)
            ledger.finish(request, "success")
        }
        val tags = target.slot.tags + listOf("daily_stock", "decoded_final", "remote_generated", "contains_person",
            "remote_chat_${f.chat.take(24)}", "slot_${target.slot.time.replace(":", "")}",
            "trajectory_${LocalDailyRules.revision(scene).take(24)}") + (framing?.let(OcFramingStockRules::tags) ?: emptyList()) +
            if (reviewed) listOf("composite_reviewed") else emptyList()
        val image = f.assets.ingest("image", bytes, "合成审核生活照", profile.visualVersion, scene.dayId, target.slot.activity, tags)
        return Future(target, image, request)
    }
    @Test fun approvedTomorrowPortraitCanBeBorrowedWithoutClaimingItsFutureEventAlreadyHappened() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val profile = f.visual.import(f.chat, f.png())
            val future = future(f, profile)
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList(), "给我发张自拍")
            assertNotNull("明日已审人像未进入提前借用", plan.borrowed)
            val reply = selector.commit(f.chat, plan, "明天的早餐我刚吃完，刚拍给你。") { it }
            assertEquals(future.image.id, reply.attachments.single().id)
            assertEquals(LocalDailyRules.dayId(f.now), reply.attachments.single().dayId)
            assertFalse(reply.content.contains("刚吃完")); assertFalse(reply.content.contains("刚拍"))
            assertEquals(future.target.scene.dayId, f.assets.details(future.image.id).attachment.dayId)
            assertTrue(LocalPhotoBorrowStore(f.context).borrowed(f.chat, future.image.sha256))
            val again = selector.prepare(f.chat, null, emptyList(), "给我发张自拍")
            assertTrue(again.candidates.none { it.sha256 == future.image.sha256 })
        }
    }
    @Test fun borrowedSourceIsExcludedFromItsFutureShareAndGetsAnotherStablePhotoMoment() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val profile = f.visual.import(f.chat, f.png())
            val future = future(f, profile)
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList(), "给我发张自拍")
            assertNotNull(plan.borrowed)
            selector.commit(f.chat, plan, "给你看看。") { it }
            val store = LocalPhotoBorrowStore(f.context)
            val replacement = store.adjusted(f.chat, future.target)
            assertNotNull(replacement.reshoot)
            assertNotEquals(future.target.slot.time, replacement.reshoot!!.photoTime)
            assertEquals(future.target.scene, replacement.scene); assertEquals(future.target.slot, replacement.slot)
            assertEquals(replacement, LocalPhotoBorrowStore(f.context).adjusted(f.chat, future.target))
            val due = LocalDate.parse(future.target.scene.dayId).atTime(LocalTime.parse(future.target.slot.time))
                .atZone(ZoneId.of(LocalDailyRules.TIMEZONE)).toInstant()
            val event = LocalDailyShareEvent(UUID.randomUUID().toString(), future.target.scene.dayId, future.target.scene.sceneId,
                LocalDailyRules.revision(future.target.scene), profile.visualVersion, future.target.slot, due, due.plusSeconds(1800))
            assertNull(LocalDailyShareMedia(f.context) { due.plusSeconds(5) }.prepare(f.chat, event, emptySet()))
        }
    }
    @Test fun unknownOrUnreviewedTomorrowPixelsCannotBecomeBorrowedSelfies() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val profile = f.visual.import(f.chat, f.png())
            val future = future(f, profile, reviewed = false)
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList(), "给我发张自拍")
            val reply = selector.commit(f.chat, plan, "已经发啦。") { it }
            assertNull(plan.borrowed); assertTrue(reply.attachments.isEmpty())
            assertFalse(LocalPhotoBorrowStore(f.context).borrowed(f.chat, future.image.sha256))
        }
    }
    @Test fun changedOcAfterOfferingCannotCommitTomorrowSourceOrConsumeItsReplacement() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val profile = f.visual.import(f.chat, f.png())
            val future = future(f, profile)
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList(), "给我发张自拍")
            assertNotNull(plan.borrowed)
            f.visual.import(f.chat, f.png(0xff954251.toInt()))
            assertTrue(selector.commit(f.chat, plan, "给你看看。") { it }.attachments.isEmpty())
            assertFalse(LocalPhotoBorrowStore(f.context).borrowed(f.chat, future.image.sha256))
            assertNull(LocalPhotoBorrowStore(f.context).adjusted(f.chat, future.target).reshoot)
        }
    }
    @Test fun changedTomorrowTrajectoryAfterOfferingRejectsBorrowWithoutConsumingIt() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val profile = f.visual.import(f.chat, f.png())
            val future = future(f, profile)
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList(), "给我发张自拍")
            assertNotNull(plan.borrowed)
            val raw = f.daily.readableDatabase.rawQuery("SELECT payload FROM days WHERE chat=? AND day=?",
                arrayOf(f.chat, future.target.scene.dayId)).use { check(it.moveToFirst()); it.getString(0) }
            val value = org.json.JSONObject(raw)
            value.getJSONArray("slots").getJSONObject(1).put("activity", "已变更的明日安排")
            val updated = value.toString()
            f.daily.writableDatabase.update("days", android.content.ContentValues().apply {
                put("payload", updated); put("sha", LocalMediaStore.sha(updated.toByteArray()))
            }, "chat=? AND day=?", arrayOf(f.chat, future.target.scene.dayId))
            assertTrue(selector.commit(f.chat, plan, "这张给你。") { it }.attachments.isEmpty())
            assertFalse(LocalPhotoBorrowStore(f.context).borrowed(f.chat, future.image.sha256))
        }
    }
    @Test fun aMixedReplyKeepsItsGeneratedAdviceWhenTheBorrowedPhotoStillPassesCommit() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val profile = f.visual.import(f.chat, f.png())
            val future = future(f, profile)
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList(), "给我发张自拍").copy(preserveGeneratedText = true)
            val generated = "考试没发挥好先别急，今晚先休息。这张先给你看看。"
            val reply = selector.commit(f.chat, plan, generated) { it }
            assertEquals("The verified attachment must not replace the rest of the answer", generated, reply.content)
            assertEquals(future.image.id, reply.attachments.single().id)
            assertTrue(LocalPhotoBorrowStore(f.context).borrowed(f.chat, future.image.sha256))
        }
    }
}
