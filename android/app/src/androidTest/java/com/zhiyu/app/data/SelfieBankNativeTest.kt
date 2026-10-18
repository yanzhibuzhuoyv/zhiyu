package com.zhiyu.app.data

import com.zhiyu.app.data.selfie.LocalSelfieBank
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SelfieBankNativeTest {
    @Test fun explicitFailedRefillIsAnAtomicBoundedTicketAdvanceAtTheCurrentRealSlot() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val profile = f.visual.import(f.chat, f.png())
            val bank = LocalSelfieBank(f.context)
            val originalScene = f.daily.scene(f.chat, profile.visualVersion, f.now)
            val usedTarget = bank.targets(f.chat, profile, originalScene).last()
            val used = f.image(labels = labels(f.chat, usedTarget), version = profile.visualVersion, color = 0xff254673.toInt())
            assertTrue(bank.consume(f.chat, f.assets.details(used.id), profile, originalScene))
            val later = f.daily.scene(f.chat, profile.visualVersion, f.now.plusSeconds(7 * 3600))
            val before = bank.targets(f.chat, profile, later)
            val ticket = checkNotNull(before.first().selfie)
            val first = checkNotNull(bank.refillFailedLane(f.chat, profile, later, before, ticket))
            assertEquals(ticket.copy(generation = 1), first.first().selfie)
            assertEquals(later.current, first.first().slot)
            assertEquals(before.drop(1), first.drop(1))
            assertNull(bank.refillFailedLane(f.chat, profile, later, before, ticket))
            repeat(127) {
                val expected = bank.targets(f.chat, profile, later)
                assertNotNull(bank.refillFailedLane(f.chat, profile, later, expected, checkNotNull(expected.first().selfie)))
            }
            val limit = bank.targets(f.chat, profile, later)
            assertEquals(128, limit.first().selfie?.generation)
            assertNull(bank.refillFailedLane(f.chat, profile, later, limit, checkNotNull(limit.first().selfie)))
            assertEquals(limit, LocalSelfieBank(f.context).targets(f.chat, profile, later))
            assertFalse(bank.acceptsPixels(f.chat, profile, limit.first(), used.sha256))
            assertArrayEquals(f.png(), f.assets.download(checkNotNull(profile.referenceId)))
        }
    }
    @Test fun readyStandaloneSelfieUsesAnActualAttachmentWithoutConsultingTheDenyingChatModel() = runBlocking {
        DailyShareNativeFixture().use { f -> SelfieReplyNativeFixture(f.context, CompanionProfile(style = "playful")).use { r ->
            val chat = r.open()
            val profile = f.visual.import(chat.id, f.png())
            val scene = f.daily.scene(chat.id, profile.visualVersion, Instant.now())
            val bank = LocalSelfieBank(f.context)
            val target = bank.targets(chat.id, profile, scene).first()
            val image = f.image(labels = labels(chat.id, target), day = scene.dayId,
                version = profile.visualVersion, color = 0xff254673.toInt())
            r.database.append(r.owner, chat.id, "assistant", "照片还没弄好，我不能发给你。")
            val reply = r.repo.sendMessage(chat.id, "给我拍张自拍").second
            assertEquals(0, r.calls)
            assertEquals(image.id, reply.mediaAttachments.single().id)
            assertTrue(reply.content.length in 1..24)
            listOf("不能", "没弄好", "催", "API", "Key", "系统").forEach { assertFalse(reply.content.contains(it)) }
            assertEquals(1, bank.targets(chat.id, profile, scene).first().selfie!!.generation)
            assertEquals(reply, r.database.history(r.owner, chat.id).messages.last())
        } }
    }

    @Test fun aMixedSelfieRequestKeepsTheModelsOtherAnswerAlongsideItsRealPhoto() = runBlocking {
        DailyShareNativeFixture().use { f -> SelfieReplyNativeFixture(f.context).use { r ->
            val chat = r.open()
            val profile = f.visual.import(chat.id, f.png())
            val scene = f.daily.scene(chat.id, profile.visualVersion, Instant.now())
            val target = LocalSelfieBank(f.context).targets(chat.id, profile, scene).first()
            val image = f.image(labels = labels(chat.id, target), day = scene.dayId,
                version = profile.visualVersion, color = 0xff254673.toInt())
            r.failIfCalled = false
            val reply = r.repo.sendMessage(chat.id, "给我拍张自拍，考试没发挥好怎么办").second
            assertEquals(1, r.calls); assertEquals(r.answer, reply.content)
            assertEquals(image.id, reply.mediaAttachments.single().id)
        } }
    }
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
