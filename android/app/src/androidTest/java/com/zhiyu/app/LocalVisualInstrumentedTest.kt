package com.zhiyu.app

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.LocalMediaStore
import com.zhiyu.app.data.LocalVisualRepository
import com.zhiyu.app.data.LocalImageJobStore
import com.zhiyu.app.data.NativeV4Context
import com.zhiyu.app.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalVisualInstrumentedTest {
    private lateinit var context: NativeV4Context
    private lateinit var jobs: LocalImageJobStore
    private lateinit var repository: LocalVisualRepository
    private val chat = "a".repeat(32)
    private val otherChat = "b".repeat(32)
    private val keep = listOf(PortraitStroke("keep", listOf(PortraitPoint(.9f, .8f)), .025f))
    private fun original() = portraitFixture { x, y ->
        if (x in 50..73 && y in 30..53 || x in 112..122 && y in 65..85) Color.GREEN else Color.TRANSPARENT
    }
    @Before fun setup() {
        context = NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext)
        jobs = LocalImageJobStore(context)
        repository = LocalVisualRepository(context, imageKey = { null }, jobs = jobs)
    }
    @After fun cleanup() {
        jobs.close(); context.close()
        val base = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.canonicalFile
        val root = context.filesDir.canonicalFile
        check(root.parentFile == base && root.name.startsWith("v4_"))
        check(root.deleteRecursively())
    }
    @Test fun previewDoesNotAlterProfileOrGenerationQuotaAndOriginalIsByteExact() = runBlocking {
        val raw = original()
        val before = repository.profile(chat)
        val preview = repository.previewSubject(chat, raw)
        assertEquals(before, repository.profile(chat))
        assertEquals(digest(raw), preview.originalSha256)
        assertArrayEquals(raw, repository.download(preview.originalId, preview.originalSha256))
        assertEquals(preview, LocalVisualRepository(context).activePreview(chat))
        val refined = repository.refineSubject(chat, preview.id, preview.subjectSha256,
            listOf(PortraitStroke("remove", listOf(PortraitPoint(.45f, .43f)), .015f)))
        assertEquals(preview.originalSha256, refined.originalSha256)
        assertEquals(before, repository.profile(chat))
        assertArrayEquals(raw, repository.download(preview.originalId, preview.originalSha256))
    }
    @Test fun staleSubjectShaIsRejectedAndNoProfileWriteOccurs() = runBlocking {
        val preview = repository.previewSubject(chat, original())
        val refined = repository.refineSubject(chat, preview.id, preview.subjectSha256,
            listOf(PortraitStroke("remove", listOf(PortraitPoint(.45f, .43f)), .015f)))
        assertNotEquals(preview.subjectSha256, refined.subjectSha256)
        expectFailure(409) { repository.refineSubject(chat, preview.id, preview.subjectSha256, keep) }
        expectFailure(409) { repository.confirmSubject(chat, preview.id, "subject", preview.subjectSha256) }
        assertEquals(0, repository.profile(chat).visualVersion)
    }
    @Test fun anotherConversationCannotRefineOrConfirmThisPreview() = runBlocking {
        val preview = repository.previewSubject(chat, original())
        expectFailure(404) { repository.refineSubject(otherChat, preview.id, preview.subjectSha256, keep) }
        expectFailure(404) { repository.confirmSubject(otherChat, preview.id, "original", preview.subjectSha256) }
        assertEquals(0, repository.profile(chat).visualVersion)
        assertEquals(0, repository.profile(otherChat).visualVersion)
    }
    @Test fun profileVersionAdvanceRejectsPendingMask() = runBlocking {
        val preview = repository.previewSubject(chat, original())
        repository.update(chat, "import", "单翼 OC", "other", "long")
        expectFailure(409) { repository.refineSubject(chat, preview.id, preview.subjectSha256, keep) }
        assertNull(repository.activePreview(chat))
    }
    @Test fun confirmedPreviewCannotBeReusedAndQuotaIsUnspent() = runBlocking {
        val preview = repository.previewSubject(chat, original())
        val attached = repository.confirmSubject(chat, preview.id, "subject", preview.subjectSha256)
        assertEquals(preview.subjectId, attached.referenceId)
        assertEquals(1, attached.visualVersion)
        assertEquals(2, attached.generation.remaining)
        assertEquals(0, attached.generation.used)
        assertNull(repository.activePreview(chat))
        expectFailure(409) { repository.refineSubject(chat, preview.id, preview.subjectSha256, keep) }
        expectFailure(409) { repository.confirmSubject(chat, preview.id, "original", preview.subjectSha256) }
    }
    @Test fun twoConcurrentEditsCannotBothApplyAgainstSameSubjectSha() = runBlocking {
        val preview = repository.previewSubject(chat, original())
        val edits = List(2) { async {
            try {
                repository.refineSubject(chat, preview.id, preview.subjectSha256,
                    listOf(PortraitStroke("remove", listOf(PortraitPoint(.45f, .43f)), .015f)))
                200
            } catch (error: ApiFailure) { error.status }
        } }
        assertEquals(listOf(200, 409), edits.map { it.await() }.sorted())
        assertEquals(0, repository.profile(chat).visualVersion)
    }
    @Test fun expiredOrDismissedPreviewIsNotResumed() = runBlocking {
        val preview = repository.previewSubject(chat, original())
        val prefs = context.getSharedPreferences("local_visual_profiles", 0)
        val value = JSONObject(prefs.getString("preview_${preview.id}", null)!!).put("expires", 0L)
        assertTrue(prefs.edit().putString("preview_${preview.id}", value.toString()).commit())
        expectFailure(409) { repository.refineSubject(chat, preview.id, preview.subjectSha256, keep) }
        assertNull(repository.activePreview(chat))
        val next = repository.previewSubject(chat, original())
        repository.discardPreview(chat, next.id)
        assertNull(repository.activePreview(chat))
    }
    @Test fun failedValidationKeepsOriginalAndProfileAndQuota() = runBlocking {
        val raw = original()
        val preview = repository.previewSubject(chat, raw)
        try {
            repository.refineSubject(chat, preview.id, preview.subjectSha256,
                listOf(PortraitStroke("keep", listOf(PortraitPoint(Float.NaN, .5f)), .02f)))
            fail("invalid coordinates")
        } catch (_: IllegalArgumentException) { }
        expectFailure(503) { repository.generate(chat, "d".repeat(32), "描述") }
        assertEquals(GenerationQuota(false, 0, 0, 2), repository.profile(chat).generation)
        assertArrayEquals(raw, repository.download(preview.originalId, preview.originalSha256))
    }
    @Test fun storeDeduplicatesSameScopeButRetainsVersionAndKindSeparation() {
        val store = LocalMediaStore(context)
        val raw = original()
        val first = store.ingest("image", raw, description = "六对翼参考", tags = listOf("角色"))
        val duplicate = store.ingest("image", raw, tags = listOf("角色"))
        assertEquals(first.id, duplicate.id)
        val differentTags = store.ingest("image", raw, tags = listOf("风景"))
        assertNotEquals(first.id, differentTags.id)
        val newVersion = store.ingest("image", raw, visualVersion = 1)
        assertNotEquals(first.id, newVersion.id)
        val sticker = store.ingest("sticker", raw)
        assertNotEquals(first.id, sticker.id)
        store.ingest("image", raw, visualVersion = 2, purpose = "portrait")
        assertEquals(3, store.list("image").size)
        assertEquals(1, store.list("sticker").size)
        assertEquals(listOf("角色"), store.details(first.id).tags)
        assertArrayEquals(raw, store.download(first.id, first.sha256))
        assertEquals(2, context.filesDir.walkTopDown().count { it.isFile && it.extension == "bin" })
    }
    private suspend fun expectFailure(status: Int, block: suspend () -> Unit) {
        try { block(); fail("expected $status") } catch (error: ApiFailure) { assertEquals(status, error.status) }
    }
}
