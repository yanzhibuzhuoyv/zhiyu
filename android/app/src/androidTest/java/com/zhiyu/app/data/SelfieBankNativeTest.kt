package com.zhiyu.app.data

import com.zhiyu.app.data.selfie.LocalSelfieBank
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SelfieBankNativeTest {
    @Test fun threeDurableOpportunitiesKeepActualTrajectoryAndRefillOnlyConsumedLane() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val profile = f.visual.import(f.chat, f.png())
            val scene = f.daily.scene(f.chat, profile.visualVersion, f.now)
            val first = LocalSelfieBank(f.context).targets(f.chat, profile, scene)
            assertEquals(3, first.size)
            assertTrue(first.all { it.scene == scene && it.slot == scene.slots[it.index] && it.selfie != null })
            assertEquals(first, LocalSelfieBank(f.context).targets(f.chat, profile, scene))
            val target = first.first()
            val image = f.image(labels = labels(f.chat, target), version = profile.visualVersion, color = 0xff254673.toInt())
            val bank = LocalSelfieBank(f.context)
            assertTrue(bank.eligible(f.chat, f.assets.details(image.id), profile, scene))
            assertTrue(bank.consume(f.chat, f.assets.details(image.id), profile, scene))
            assertFalse(bank.consume(f.chat, f.assets.details(image.id), profile, scene))
            val next = LocalSelfieBank(f.context).targets(f.chat, profile, scene)
            assertEquals(1, next.first().selfie!!.generation)
            assertEquals(first.drop(1), next.drop(1))
            assertArrayEquals(f.png(), f.assets.download(profile.referenceId!!))
        }
    }
    @Test fun originalReferenceAndUnreviewedOrOtherChatPicturesCannotBecomeNewSelfies() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val profile = f.visual.import(f.chat, f.png())
            val scene = f.daily.scene(f.chat, profile.visualVersion, f.now)
            val bank = LocalSelfieBank(f.context)
            val target = bank.targets(f.chat, profile, scene).firstOrNull()
            assertNotNull("自拍机会缺失", target)
            val tags = labels(f.chat, target!!)
            val samePixels = f.image(labels = tags, version = profile.visualVersion)
            val unreviewed = f.image(labels = tags - "composite_reviewed", version = profile.visualVersion, color = 0xff254673.toInt())
            val other = f.image(labels = labels("f".repeat(32), target), version = profile.visualVersion, color = 0xff456723.toInt())
            listOf(samePixels, unreviewed, other).forEach {
                assertFalse(bank.eligible(f.chat, f.assets.details(it.id), profile, scene))
            }
        }
    }
    @Test fun directRequestWithNoAvailablePhotoCannotClaimOneWasSent() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList(), "给我发张自拍")
            val result = selector.commit(f.chat, plan, "自拍发给你啦。") { it }
            assertTrue(result.attachments.isEmpty())
            assertFalse(result.content.contains("发给你啦"))
            assertTrue(result.content.contains("自拍"))
            listOf("OC", "库存", "系统", "服务", "审核").forEach { assertFalse(result.content.contains(it)) }
            assertEquals("oc_missing", plan.selfieStatus)
        }
    }
    @Test fun approvedSelfieIsAttachedWithoutModelMarkerAndCannotBeSentTwice() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val profile = f.visual.import(f.chat, f.png())
            val scene = f.daily.scene(f.chat, profile.visualVersion, f.now)
            val bank = LocalSelfieBank(f.context)
            val target = bank.targets(f.chat, profile, scene).firstOrNull()
            assertNotNull("自拍机会缺失", target)
            val image = f.image(labels = labels(f.chat, target!!), version = profile.visualVersion, color = 0xff254673.toInt())
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList(), "给我发张自拍")
            val result = selector.commit(f.chat, plan, "给你看看这张。") { it }
            assertEquals(image.id, result.attachments.single().id)
            assertEquals(1, bank.targets(f.chat, profile, scene).first().selfie!!.generation)
            assertTrue(selector.commit(f.chat, plan, "这张发给你啦。") { it }.attachments.isEmpty())
        }
    }
    @Test fun corruptOptionalCreatorKeepsNaturalPendingReplyAndPreservesTheBrokenManifest() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val profile = f.visual.import(f.chat, f.png())
            val scene = f.daily.scene(f.chat, profile.visualVersion, f.now)
            val target = LocalSelfieBank(f.context).targets(f.chat, profile, scene).first()
            f.image(labels = labels(f.chat, target), version = profile.visualVersion, color = 0xff254673.toInt())
            val broken = "{broken-creator-json".toByteArray()
            val manifest = java.io.File(f.context.filesDir, "oc_identity_v1/${f.chat}.json")
            LocalMediaStore.write(manifest, broken)
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList(), "给我发张自拍")
            assertEquals("inventory_invalid", plan.selfieStatus)
            val result = selector.commit(f.chat, plan, "自拍发给你啦。") { it }
            assertTrue(result.attachments.isEmpty())
            assertTrue(result.content.contains("自拍"))
            listOf("发给你啦", "OC", "库存", "系统", "服务", "审核").forEach { assertFalse(result.content.contains(it)) }
            assertArrayEquals(broken, manifest.readBytes())
            val ordinary = selector.commit(f.chat, LocalMediaPlan("", emptyList(), ""), "普通聊天仍能回复。") { it }
            assertEquals("普通聊天仍能回复。", ordinary.content)
            var saves = 0
            val failure = assertThrows(IllegalStateException::class.java) {
                selector.commit(f.chat, plan, "仍不能假称发送。") { saves++; throw IllegalStateException("synthetic save failure") }
            }
            assertEquals("synthetic save failure", failure.message)
            assertEquals(1, saves)
        }
    }
    private fun labels(chat: String, target: LocalScenePrefetchTarget) = target.slot.tags + listOf("selfie_stock", "decoded_final",
        "remote_generated", "composite_reviewed", "contains_person", "remote_chat_${chat.take(24)}",
        "slot_${target.slot.time.replace(":", "")}", "trajectory_${LocalDailyRules.revision(target.scene).take(24)}") +
        LocalSelfieRules.tags(checkNotNull(target.selfie))
}
