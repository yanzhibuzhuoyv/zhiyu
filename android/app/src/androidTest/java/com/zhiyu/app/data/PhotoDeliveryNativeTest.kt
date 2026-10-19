package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Screenshot regression using actual local pixels; never calls a paid provider. */
class PhotoDeliveryNativeTest {
    @Test fun aQuestionAboutAnExistingPictureDoesNotBypassTheProviderOrSendOtherStock() = runBlocking {
        DailyShareNativeFixture().use { f -> SelfieReplyNativeFixture(f.context).use { r ->
            f.withoutBundledSeeds()
            val chat = r.open()
            f.image(labels = listOf("cat"), day = null)
            r.failIfCalled = false
            val question = "看看这张猫咪照片里写了什么"
            val reply = r.repo.sendMessage(chat.id, question).second
            assertEquals(1, r.calls)
            assertEquals(r.answer, reply.content)
            assertTrue(reply.mediaAttachments.isEmpty())
            assertEquals(question, r.database.history(r.owner, chat.id).messages.first().content)
        } }
    }
    @Test fun repositoryReadsTheRealOfferFromPromptContextInsteadOfEmptyMediaMetadata() = runBlocking {
        DailyShareNativeFixture().use { f -> SelfieReplyNativeFixture(f.context).use { r ->
            f.withoutBundledSeeds()
            val chat = r.open()
            val image = f.image(labels = listOf("cat"), day = null)
            val offer = r.database.append(r.owner, chat.id, "assistant", "还有几张猫咪照片，你要看哪样？")
            assertEquals("", r.database.mediaHistory(r.owner, chat.id).single { it.id == offer.id }.content)
            assertEquals(offer.content, r.database.promptContext(r.owner, chat.id, 48_000).messages.single { it.id == offer.id }.content)
            val reply = r.repo.sendMessage(chat.id, "都看看").second
            assertEquals(0, r.calls)
            assertEquals(image.id, reply.mediaAttachments.single().id)
            assertEquals(reply, r.database.history(r.owner, chat.id).messages.last())
        } }
    }

    @Test fun acceptingAnOfferedLifePhotoAttachesRealStockWithoutAModelMarker() = runBlocking {
        DailyShareNativeFixture().use { f ->
            f.withoutBundledSeeds()
            val image = f.image(labels = listOf("cat"), day = null)
            val media = LocalReplyMedia(f.context) { f.now }
            val recent = listOf(f.message("assistant", "还有几张猫咪照片，你要看哪样？"))
            val plan = media.prepare(f.chat, CompanionProfile(), recent, "都看看")
            val reply = media.commit(f.chat, plan, "行，我翻翻看。") { it }
            assertEquals(image.id, reply.attachments.single().id)
            assertArrayEquals(f.png(), f.assets.download(reply.attachments.single().id))
            assertFalse(reply.content.contains("翻翻"))
        }
    }

    @Test fun theScreenshotPromiseChainStillSelectsOneRealLifePhotoOnAcceptance() = runBlocking {
        DailyShareNativeFixture().use { f ->
            f.withoutBundledSeeds()
            val image = f.image(labels = listOf("cat"), day = null)
            val media = LocalReplyMedia(f.context) { f.now }
            val recent = listOf(
                f.message("assistant", "还有几张照片，你要看哪样？"), f.message("user", "都看看"),
                f.message("assistant", "行，我翻翻看。"), f.message("user", "还没挑好吗"),
                f.message("assistant", "还没，翻来翻去差点意思。"), f.message("user", "只要你发的就行，发一张看看嘛"),
                f.message("assistant", "那挑张最顺眼的发你。"))
            val plan = media.prepare(f.chat, CompanionProfile(), recent, "好哦好哦")
            val reply = media.commit(f.chat, plan, "这张窗边橘猫窝毯子上，安静得跟我一样。") { it }
            assertEquals(image.id, reply.attachments.single().id)
            assertFalse(reply.content.contains("橘猫")) // Fixture is solid pixels, never a guessed cat caption.
        }
    }

    @Test fun anUnavailableAcceptedPhotoCannotDescribeANonexistentAttachment() = runBlocking {
        DailyShareNativeFixture().use { f ->
            f.withoutBundledSeeds()
            val media = LocalReplyMedia(f.context) { f.now }
            val recent = listOf(f.message("assistant", "还有几张猫咪照片，要看吗？"))
            val plan = media.prepare(f.chat, CompanionProfile(), recent, "看看")
            val reply = media.commit(f.chat, plan, "这张窗边橘猫窝毯子上，安静得跟我一样。") { it }
            assertTrue(reply.attachments.isEmpty())
            assertFalse(reply.content.contains("橘猫"))
            assertFalse(reply.content.contains("等我"))
            assertFalse(reply.content.contains("自拍"))
        }
    }

    @Test fun theBundledOrangeCatIsAnActualPhotoAndNotAnInventedOrPaidSelfie() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val media = LocalReplyMedia(f.context) { f.now }
            val plan = media.prepare(f.chat, CompanionProfile(), listOf(f.message("assistant", "还有几张猫咪照片，要看吗？")), "都看看")
            val reply = media.commit(f.chat, plan, "我翻翻看。") { it }
            val image = reply.attachments.single()
            assertTrue(plan.photoRequested); assertFalse(plan.selfieRequested)
            assertTrue(f.assets.details(image.id).tags.contains("cat"))
            assertEquals("AI 生成的虚构生活场景", image.sourceLabel)
            assertTrue(f.assets.download(image.id, image.sha256).size > 1000)
        }
    }

    @Test fun aDeliveredRequiredLifePhotoCannotBeConsumedTwiceEvenWithANewSelector() = runBlocking {
        DailyShareNativeFixture().use { f ->
            f.withoutBundledSeeds()
            val image = f.image(labels = listOf("cat"), day = null)
            val recent = listOf(f.message("assistant", "还有几张猫咪照片，要看吗？"))
            val media = LocalReplyMedia(f.context) { f.now }
            val plan = media.prepare(f.chat, null, recent, "看看")
            assertEquals(image.id, media.commit(f.chat, plan, "给你看看这张。") { it }.attachments.single().id)
            assertTrue(media.commit(f.chat, plan, "照片发给你了。") { it }.attachments.isEmpty())
            val next = LocalReplyMedia(f.context) { f.now }
            val nextPlan = next.prepare(f.chat, null, recent, "看看")
            assertTrue(next.commit(f.chat, nextPlan, "这张橘猫挺可爱。") { it }.attachments.isEmpty())
        }
    }

    @Test fun losingTheActualFileAfterSelectionCannotLeaveAPhotoCaptionAtCommit() = runBlocking {
        DailyShareNativeFixture().use { f ->
            f.withoutBundledSeeds()
            val image = f.image(labels = listOf("cat"), day = null)
            val media = LocalReplyMedia(f.context) { f.now }
            val plan = media.prepare(f.chat, null, listOf(f.message("assistant", "有张猫咪照片，给你看看？")), "好哦")
            assertEquals(image.id, plan.requiredAttachmentId)
            f.corruptBlob(image)
            val reply = media.commit(f.chat, plan, "这张橘猫很乖。") { it }
            assertTrue(reply.attachments.isEmpty()); assertFalse(reply.content.contains("橘猫"))
            assertFalse(reply.content.contains("等我"))
        }
    }

    @Test fun ordinaryLifeRequestsRejectUnreviewedGeneratedStockAndUserOriginals() = runBlocking {
        DailyShareNativeFixture().use { f ->
            f.withoutBundledSeeds()
            f.image(labels = listOf("cat", "remote_generated"), day = null)
            f.image(labels = listOf("cat"), day = null, purpose = "chat", color = 0xff294173.toInt())
            f.image(labels = listOf("cat"), day = null, purpose = "portrait", color = 0xff294174.toInt())
            val media = LocalReplyMedia(f.context) { f.now }
            val plan = media.prepare(f.chat, null, emptyList(), "给我发张猫咪照片")
            assertTrue(plan.photoRequested); assertFalse(plan.selfieRequested)
            assertTrue(media.commit(f.chat, plan, "这张橘猫很好看。") { it }.attachments.isEmpty())
        }
    }

    private fun DailyShareNativeFixture.message(role: String, content: String) = Message(LocalMediaStore.identifier(), role, content,
        emptyList(), now.toString())
    private fun DailyShareNativeFixture.withoutBundledSeeds() {
        check(context.getSharedPreferences("native_media_seed", 0).edit().putInt("version", 2).commit())
        assertTrue(assets.list("image").isEmpty())
    }
}
