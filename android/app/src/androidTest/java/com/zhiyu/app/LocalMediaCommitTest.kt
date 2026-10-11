package com.zhiyu.app

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic images and isolated preferences; no cloud or real OC is used. */
class LocalMediaCommitTest {
    private val context = IsolatedVisualContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private val chat = UUID.randomUUID().toString().replace("-", "")
    private val visual = LocalVisualRepository(context)
    private val assets = LocalMediaStore(context)
    private var now = Instant.parse("2026-10-09T15:59:59Z")
    private val replies = LocalReplyMedia(context) { now }
    @After fun cleanup() { context.clean() }
    private fun image(color: Int) = portraitFixture { _, _ -> color }
    private fun plan(asset: MediaAttachment) = LocalMediaPlan("", listOf(asset), "synthetic")
    private fun answer(asset: MediaAttachment, text: String = "完整的自然正文") =
        "$text\n[[ZHIYU_MEDIA_synthetic:${asset.id}]]"

    @Test fun aNewOcDuringTheWaitDropsTheOldPersonButPreservesTextAndHistoricalImage() = runBlocking {
        val before = visual.import(chat, image(Color.RED))
        val person = assets.ingest("image", image(Color.BLUE), visualVersion = before.visualVersion,
            dayId = "2026-10-09", tags = listOf("contains_person"))
        val prepared = plan(person)
        val gate = CompletableDeferred<Unit>()
        val content = "同我慢慢说。".repeat(1000)
        val pending = async { gate.await(); withContext(Dispatchers.IO) { replies.commit(chat, prepared, answer(person, content)) { it } } }
        visual.import(chat, image(Color.GREEN))
        gate.complete(Unit)
        val result = pending.await()
        assertEquals(content, result.content)
        assertTrue(result.attachments.isEmpty())
        assertArrayEquals(image(Color.BLUE), assets.download(person.id, person.sha256))
        assertEquals(1, person.visualVersion)
        assertEquals(2, visual.profile(chat).visualVersion)
    }

    @Test fun crossingBeijingMidnightDropsYesterdaysImageAndKeepsTheAnswer() {
        val landscape = assets.ingest("image", image(Color.BLUE), dayId = "2026-10-09")
        val prepared = plan(landscape)
        now = Instant.parse("2026-10-09T16:00:00Z")
        val reply = replies.commit(chat, prepared, answer(landscape)) { it }
        assertEquals("完整的自然正文", reply.content)
        assertTrue(reply.attachments.isEmpty())
        assertArrayEquals(image(Color.BLUE), assets.download(landscape.id, landscape.sha256))
    }

    @Test fun changingTheOcCannotInterleaveBetweenItsFinalCheckAndAppend() = runBlocking {
        val before = visual.import(chat, image(Color.RED))
        val person = assets.ingest("image", image(Color.BLUE), visualVersion = before.visualVersion,
            dayId = "2026-10-09", tags = listOf("contains_person"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saved = async(Dispatchers.IO) { replies.commit(chat, plan(person), answer(person)) {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); it
        } }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val updaterStarted = CompletableDeferred<Unit>()
        val update = async(Dispatchers.IO) { updaterStarted.complete(Unit); visual.import(chat, image(Color.GREEN)) }
        try {
            updaterStarted.await(); delay(100)
            assertFalse(update.isCompleted)
        } finally { release.countDown() }
        assertEquals(listOf(person), saved.await().attachments)
        assertEquals(2, update.await().visualVersion)
    }
}
