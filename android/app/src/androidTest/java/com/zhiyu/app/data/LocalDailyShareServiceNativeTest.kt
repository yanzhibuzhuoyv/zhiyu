package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** UUID-scoped state, synthetic media/gateway and metadata-only binding, never a real Key. */
class LocalDailyShareServiceNativeTest {
    private class BindingStore : CredentialStore {
        var reads = 0
        override fun read(): SavedConnection? = error("Test must never read an API Key")
        override fun readBinding(): CompanionBinding { reads++; return CompanionBinding("synthetic", "synthetic", CompanionProfile(style = "anxious")) }
        override fun write(connection: SavedConnection) = error("No credentials may be changed")
        override fun clear() = error("No credentials may be changed")
        override fun writeBinding(binding: CompanionBinding) = error("No binding may be changed")
    }
    private class Preferences : ProactiveService {
        var value = ProactivePreferences(enabled = true)
        override suspend fun preferences(id: String) = value
        override suspend fun savePreferences(id: String, preferences: ProactivePreferences): ProactivePreferences { value = preferences; return value }
        override suspend fun check(id: String, requestId: String): ProactiveCheck = error("Do not invoke the ordinary contact policy")
    }
    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "daily_share_service_test_${UUID.randomUUID()}"
        val chat = UUID.randomUUID().toString().replace("-", "")
        val credentials = BindingStore()
        val preferences = Preferences()
        var clock = Instant.parse("2026-10-13T01:15:00Z")
        var allowed = true
        var optedIn = true
        var pictureAvailable = true
        var sendFailure = false
        var commitThenFail = false
        var onPicture: () -> Unit = {}
        var sceneCalls = 0
        var pictureCalls = 0
        var sends = 0
        var visualVersion = 1
        var activityChanged = false
        var extraSlots = emptyList<LocalDailySlot>()
        val results = mutableMapOf<String, Message>()
        val receivedSha = mutableListOf<Set<String>>()
        val requests = mutableListOf<String>()
        val slot get() = LocalDailySlot("09:00", if (activityChanged) "changed activity" else "合成书桌日常", listOf("object", "morning"))
        val scene get(): LocalDailyScene {
            val base = LocalDailyRules.scene(chat, visualVersion, clock)
            val slots = (listOf(LocalDailySlot("00:00", "休息", listOf("night")), slot,
                LocalDailySlot("12:00", "合成午餐", listOf("food"))) + extraSlots).sortedBy { it.time }
            return base.copy(slots = slots, current = LocalDailyRules.current(slots, clock))
        }
        val event get(): LocalDailyShareEvent {
            val base = LocalDate.parse(LocalDailyRules.dayId(clock)).atStartOfDay(ZoneId.of(LocalDailyRules.TIMEZONE)).toInstant()
            return LocalDailyShareEvent(UUID.nameUUIDFromBytes("$chat:${scene.dayId}:09:00".toByteArray()).toString(),
                scene.dayId, scene.sceneId, LocalDailyRules.revision(scene), 1,
                slot, base.plusSeconds(33000), base.plusSeconds(35700))
        }
        var image = MediaAttachment("a".repeat(32), "image", MediaRules.path("a".repeat(32)), "image/png", "b".repeat(64), "2026-10-13", 1, "synthetic")
        val gateway = object : LocalDailyShareGateway {
            override suspend fun result(id: String, requestId: String) = results[requestId]
            override suspend fun scene(id: String, now: Instant): LocalDailyScene { sceneCalls++; return scene }
            override suspend fun picture(id: String, event: LocalDailyShareEvent, usedSha: Set<String>): MediaAttachment? {
                pictureCalls++; receivedSha.add(usedSha.toSet()); onPicture(); return image.takeIf { pictureAvailable }
            }
            override suspend fun send(id: String, event: LocalDailyShareEvent, attachment: MediaAttachment): Message {
                results[event.requestId]?.let { return it }
                sends++; requests.add(event.requestId)
                if (sendFailure) throw IOException("synthetic offline before commit")
                val message = Message("synthetic-share-${event.requestId}", "assistant", "合成日常原话", emptyList(), clock.toString(), listOf(attachment), "daily_share")
                results[event.requestId] = message
                if (commitThenFail) throw IOException("synthetic crash after commit before notification")
                return message
            }
        }
        fun state() = LocalDailyShareStore(context, name)
        fun service() = LocalDailyShareService(context, credentials, gateway, state(), preferences, { optedIn }, { allowed }, { clock })
        fun request() = UUID.randomUUID().toString()
        fun prepare() { state().savePlan(chat, LocalDailySharePlan(event.dayId, 1, listOf(event))) }
        override fun close() { context.deleteSharedPreferences(name) }
    }

    @Test fun disabledOptInOrDeniedPermissionNeverReadsBindingOrRequestsMediaOrModel() = runBlocking {
        Fixture().use { f ->
            f.preferences.value = ProactivePreferences(enabled = false)
            assertEquals("disabled", f.service().check(f.chat, f.request()).reason)
            f.preferences.value = ProactivePreferences(enabled = true)
            f.optedIn = false
            assertEquals("disabled", f.service().check(f.chat, f.request()).reason)
            f.optedIn = true; f.allowed = false
            assertEquals("permission", f.service().check(f.chat, f.request()).reason)
            assertEquals(0, f.credentials.reads)
            assertEquals(0, f.sceneCalls + f.pictureCalls + f.sends)
        }
    }

    @Test fun aNewZeroQuotaDayPersistsWithoutLoadingSceneOrCallingModel() = runBlocking {
        Fixture().use { f ->
            val zeroDay = (0..1000).map { LocalDate.of(2026, 1, 1).plusDays(it.toLong()) }
                .first { LocalDailySharePolicy.quota(f.chat, "anxious", it.toString()) == 0 }
            f.clock = zeroDay.atTime(12, 0).atZone(ZoneId.of(LocalDailyRules.TIMEZONE)).toInstant()
            val first = f.service().check(f.chat, f.request())
            val second = f.service().check(f.chat, f.request())
            assertFalse(first.emitted || second.emitted)
            assertEquals(0, f.state().plan(f.chat, zeroDay.toString())!!.quota)
            assertEquals(0, f.sceneCalls + f.pictureCalls + f.sends)
            assertEquals(LocalDailyRules.nextMidnight(f.clock).toString(), second.nextDueAt)
        }
    }

    @Test fun matchingImageSendsOnceAndTheSamePendingRequestRecoversTheSameMessage() = runBlocking {
        Fixture().use { f ->
            f.prepare()
            val request = f.request()
            val first = f.service().check(f.chat, request)
            val retry = f.service().check(f.chat, request)
            val newRequest = f.service().check(f.chat, f.request())
            assertTrue(first.emitted && retry.emitted)
            assertEquals(first.message, retry.message)
            assertEquals("daily_share", first.message!!.origin)
            assertEquals(listOf(f.image), first.message.mediaAttachments)
            assertFalse(newRequest.emitted)
            assertEquals(1, f.sends)
            assertEquals(1, f.pictureCalls)
            assertEquals(listOf(f.event.requestId), f.requests)
            assertEquals(setOf(f.image.sha256), f.state().usedSha(f.chat))
        }
    }

    @Test fun missingImageDefersFifteenMinutesAndDoesNotConsumeTheEvent() = runBlocking {
        Fixture().use { f ->
            f.prepare(); f.pictureAvailable = false
            val first = f.service().check(f.chat, f.request())
            assertEquals("await_image", first.reason)
            assertEquals(f.clock.plusSeconds(900).toString(), first.nextDueAt)
            assertTrue(f.state().finished(f.chat, f.event.dayId).isEmpty())
            assertEquals(0, f.sends)
            f.clock = f.clock.plusSeconds(900); f.pictureAvailable = true
            assertTrue(f.service().check(f.chat, f.request()).emitted)
            assertEquals(1, f.sends)
        }
    }

    @Test fun expiredEventsAreFinishedWithoutPreparingOrSendingAPhoto() = runBlocking {
        Fixture().use { f ->
            f.prepare()
            val original = f.event
            f.clock = original.expires.plusSeconds(1)
            assertFalse(f.service().check(f.chat, f.request()).emitted)
            assertEquals(setOf(original.requestId), f.state().finished(f.chat, original.dayId))
            assertEquals(0, f.pictureCalls + f.sends)
        }
    }

    @Test fun usedImageShaFromAnEarlierPlanCannotBeSharedAgain() = runBlocking {
        Fixture().use { f ->
            val previous = f.event.copy(dayId = "2026-10-12", requestId = f.request(),
                due = f.event.due.minusSeconds(86400), expires = f.event.expires.minusSeconds(86400))
            f.state().savePlan(f.chat, LocalDailySharePlan(previous.dayId, 1, listOf(previous)))
            f.state().finish(f.chat, previous, f.image.sha256)
            f.prepare()
            val result = f.service().check(f.chat, f.request())
            assertFalse(result.emitted)
            assertEquals("await_image", result.reason)
            assertEquals(listOf(setOf(f.image.sha256)), f.receivedSha)
            assertEquals(0, f.sends)
        }
    }

    @Test fun networkFailureBeforeCommitRetriesTheStableEventWithoutConsumedQuota() = runBlocking {
        Fixture().use { f ->
            f.prepare(); f.sendFailure = true
            val request = f.request()
            try { f.service().check(f.chat, request); fail("Offline error was swallowed") } catch (_: IOException) { }
            assertTrue(f.state().finished(f.chat, f.event.dayId).isEmpty())
            f.sendFailure = false
            assertTrue(f.service().check(f.chat, request).emitted)
            assertEquals(listOf(f.event.requestId, f.event.requestId), f.requests)
        }
    }

    @Test fun committedThenCrashedMessageRecoversAfterMidnightWithoutAnotherImageOrModelCall() = runBlocking {
        Fixture().use { f ->
            f.prepare(); f.commitThenFail = true
            val request = f.request()
            val event = f.event
            try { f.service().check(f.chat, request); fail("Synthetic crash was swallowed") } catch (_: IOException) { }
            val committed = f.results[event.requestId]
            assertNotNull(committed)
            val sceneCalls = f.sceneCalls
            f.clock = LocalDailyRules.nextMidnight(f.clock).plusSeconds(1)
            val retry = f.service().check(f.chat, request)
            assertTrue(retry.emitted)
            assertEquals(committed, retry.message)
            assertEquals(1, f.sends)
            assertEquals(1, f.pictureCalls)
            assertEquals(sceneCalls, f.sceneCalls)
            assertEquals(setOf(event.requestId), f.state().finished(f.chat, event.dayId))
        }
    }

    @Test fun permissionRevokedWhileSelectingThePhotoPreventsGeneration() = runBlocking {
        Fixture().use { f ->
            f.prepare(); f.onPicture = { f.allowed = false }
            assertEquals("permission", f.service().check(f.chat, f.request()).reason)
            assertEquals(0, f.sends)
            assertTrue(f.state().finished(f.chat, f.event.dayId).isEmpty())
        }
    }

    @Test fun selectionThatCrossesTheExpiryBoundaryNeverCallsTheModel() = runBlocking {
        Fixture().use { f ->
            f.prepare()
            val event = f.event
            f.onPicture = { f.clock = event.expires }
            assertFalse(f.service().check(f.chat, f.request()).emitted)
            assertEquals(0, f.sends)
            assertEquals(setOf(event.requestId), f.state().finished(f.chat, event.dayId))
        }
    }

    @Test fun staleWrongKindOrWrongDayPicturesAreNeverPassedToTheModel() = runBlocking {
        Fixture().use { f ->
            f.prepare()
            val valid = f.image
            listOf(valid.copy(visualVersion = 2), valid.copy(kind = "sticker"), valid.copy(dayId = "2026-10-12")).forEach { invalid ->
                f.image = invalid
                assertFalse(f.service().check(f.chat, f.request()).emitted)
            }
            assertEquals(0, f.sends)
            assertTrue(f.state().finished(f.chat, f.event.dayId).isEmpty())
        }
    }

    @Test fun newVisualVersionUsesANewMatchingPhotoWithoutRerollingTheDayOrRepeatingASentEvent() = runBlocking {
        Fixture().use { f ->
            f.prepare()
            val original = f.state().plan(f.chat, f.event.dayId)!!
            f.visualVersion = 2
            f.image = f.image.copy(visualVersion = 2, sha256 = "c".repeat(64))
            val result = f.service().check(f.chat, f.request())
            assertTrue(result.emitted)
            val updated = f.state().plan(f.chat, original.dayId)!!
            assertEquals(original.quota, updated.quota)
            assertEquals(original.events.map { it.requestId }, updated.events.map { it.requestId })
            assertEquals(original.events.single().due, updated.events.single().due)
            assertEquals(original.events.single().slot, updated.events.single().slot)
            assertEquals(2, updated.events.single().visualVersion)
            assertEquals(LocalDailyRules.revision(f.scene), updated.events.single().revision)
            f.visualVersion = 3
            f.image = f.image.copy(visualVersion = 3)
            assertFalse(f.service().check(f.chat, f.request()).emitted)
            assertEquals(1, f.sends)
        }
    }

    @Test fun changedActualActivityExpiresTheOldSelectionWithoutReplacingItOrCallingTheModel() = runBlocking {
        Fixture().use { f ->
            f.prepare()
            val original = f.state().plan(f.chat, f.event.dayId)!!
            f.activityChanged = true
            assertFalse(f.service().check(f.chat, f.request()).emitted)
            assertEquals(original, f.state().plan(f.chat, original.dayId))
            assertEquals(setOf(original.events.single().requestId), f.state().finished(f.chat, original.dayId))
            assertEquals(0, f.sends + f.pictureCalls)
        }
    }

    @Test fun failedOldRequestReleasesItsExpiredEventAndOnlyANewRequestCanSendTheNextEvent() = runBlocking {
        Fixture().use { f ->
            val secondSlot = LocalDailySlot("10:00", "合成第二段日常", listOf("object", "morning"))
            f.extraSlots = listOf(secondSlot)
            val first = f.event
            val second = first.copy(requestId = f.request(), slot = secondSlot,
                due = first.due.plusSeconds(3600), expires = first.expires.plusSeconds(3600))
            f.state().savePlan(f.chat, LocalDailySharePlan(first.dayId, 2, listOf(first, second)))
            val oldRequest = f.request()
            f.sendFailure = true
            try { f.service().check(f.chat, oldRequest); fail("Synthetic pre-commit network failure was swallowed") }
            catch (_: IOException) { }
            assertEquals(first, f.state().aliasEvent(f.chat, oldRequest))
            f.sendFailure = false
            f.clock = second.due.plusSeconds(1)
            f.image = f.image.copy(id = "c".repeat(32), url = MediaRules.path("c".repeat(32)), sha256 = "d".repeat(64))
            val release = f.service().check(f.chat, oldRequest)
            assertFalse(release.emitted)
            assertEquals("not_due", release.reason)
            assertEquals(first, f.state().aliasEvent(f.chat, oldRequest))
            assertEquals(setOf(first.requestId), f.state().finished(f.chat, first.dayId))
            assertEquals(1, f.sends)
            val next = f.service().check(f.chat, f.request())
            assertTrue(next.emitted)
            assertEquals(2, f.sends)
            assertEquals(listOf(first.requestId, second.requestId), f.requests)
        }
    }
}
