package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Synthetic phone files only. A newer profile must not permanently trap a failed OC save. */
class OcPublicationSupersededNativeTest {
    private val context = NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext)
    private val chat = "a".repeat(32)
    private val published = OcAssetRef("b".repeat(32), "c".repeat(64), 7)
    private val later = OcAssetRef("d".repeat(32), "e".repeat(64), 8)
    private fun pendingFile() = File(context.filesDir, "oc_identity_v1/$chat.pending.json")
    private fun audits() = File(context.filesDir, "oc_identity_v1/aborted/$chat").listFiles()
        .orEmpty().filter { it.extension == "json" }.map { JSONObject(it.readText()) }
    @After fun cleanup() {
        val root = context.filesDir
        val parent = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.canonicalFile
        check(root.canonicalFile.parentFile == parent && root.name.startsWith("v4_"))
        context.close(); root.deleteRecursively()
    }
    private fun proposal(): OcIdentity {
        val initial = OcIdentityRules.fresh("parts_2d").copy(parts = listOf(OcPart("only_left", "wing", "left")))
        return OcIdentityRules.next(initial, initial)
    }
    private fun interrupted(store: OcCreatorStore, proposal: OcIdentity) {
        store.beginPublication(chat, 0, 6, proposal, published.sha256, "interrupted")
        store.finishPublication(chat, "interrupted", false)
    }
    private fun fresh(store: OcCreatorStore, reference: OcAssetRef?, version: Int?): OcIdentitySnapshot? =
        store.recoverPublication(chat, reference, version)

    @Test fun aLaterProfileArchivesTheAbandonedAttemptAndAllowsSavingTheSameOcAgain() {
        val store = OcCreatorStore(context); val proposed = proposal(); interrupted(store, proposed)
        assertNull(fresh(store, later, later.visualVersion))
        assertFalse("A superseded intent must not block all future saves", pendingFile().exists())
        val audit = audits().single()
        assertEquals("aborted", audit.getString("state")); assertEquals("superseded_by_profile", audit.getString("reason"))
        assertEquals(6, audit.getInt("expected_version")); assertEquals(8, audit.getInt("superseded_version"))
        assertEquals(OcIdentityRules.packageSha(proposed), audit.getString("identity_sha"))
        assertEquals(published.sha256, audit.getString("png_sha"))
        store.beginPublication(chat, 0, 8, proposed, "f".repeat(64), "retry")
        try {
            val reference = OcAssetRef("1".repeat(32), "f".repeat(64), 9)
            val saved = store.commit(chat, 0, proposed.copy(reference = reference))
            assertEquals(proposed.ocId, saved.identity.ocId); assertEquals(proposed.parts, saved.identity.parts)
        } finally { store.finishPublication(chat, "retry", true) }
        assertEquals(1, audits().size)
    }
    @Test fun clearingTheReferenceStillArchivesOnlyAfterAnExplicitFreshVersionAdvance() {
        val store = OcCreatorStore(context); interrupted(store, proposal())
        assertNull(fresh(store, null, null)); assertTrue(pendingFile().isFile); assertTrue(audits().isEmpty())
        assertNull(fresh(store, null, 8))
        assertFalse(pendingFile().exists()); assertEquals(8, audits().single().getInt("superseded_version"))
    }
    @Test fun aMatchingCompletedPublicationIsRecoveredAndNeverArchived() {
        val store = OcCreatorStore(context); val proposed = proposal(); interrupted(store, proposed)
        val saved = checkNotNull(fresh(store, published, 7))
        assertEquals(proposed.ocId, saved.identity.ocId); assertEquals(published, saved.identity.reference)
        assertFalse(pendingFile().exists()); assertTrue(audits().isEmpty())
    }
    @Test fun activePublicationCannotBeArchivedByAnotherControllerEvenWithANewerProfile() {
        val store = OcCreatorStore(context); val proposed = proposal()
        store.beginPublication(chat, 0, 6, proposed, published.sha256, "active")
        try {
            assertNull(fresh(OcCreatorStore(context), later, 8))
            assertTrue(pendingFile().isFile); assertTrue(audits().isEmpty())
            try { OcCreatorStore(context).beginPublication(chat, 0, 8, proposed, "f".repeat(64), "second"); fail() }
            catch (_: IllegalArgumentException) { }
        } finally { store.finishPublication(chat, "active", false) }
    }
    @Test fun unknownOrUnadvancedProfileCannotDiscardAnAttemptThatCanStillRecover() {
        val store = OcCreatorStore(context); interrupted(store, proposal())
        assertNull(fresh(store, later, null)); assertTrue(pendingFile().isFile)
        assertNull(fresh(store, later.copy(visualVersion = 6), 6)); assertTrue(pendingFile().isFile)
        assertTrue(audits().isEmpty())
        assertNotNull(fresh(store, published, 7))
    }
    @Test fun anExplicitReplacementAtThePublishVersionIsArchivedButAnUnknownSnapshotIsNot() {
        val store = OcCreatorStore(context); interrupted(store, proposal())
        val replacement = later.copy(visualVersion = 7)
        assertNull(store.recoverPublication(chat, replacement)); assertTrue(pendingFile().isFile)
        assertNull(fresh(store, replacement, 7))
        assertFalse(pendingFile().exists()); assertEquals(7, audits().single().getInt("superseded_version"))
    }
    @Test fun anAuditWriteFailureKeepsTheIntentAvailableForItsOriginalMatchingRecovery() {
        val store = OcCreatorStore(context); interrupted(store, proposal())
        val parent = File(context.filesDir, "oc_identity_v1/aborted").apply { check(mkdirs()) }
        File(parent, chat).writeText("synthetic directory blocker")
        try { fresh(store, later, 8); fail("Audit failure must prevent discarding pending") }
        catch (_: IllegalStateException) { }
        assertTrue(pendingFile().isFile); assertNull(store.read(chat))
        val recovered = checkNotNull(fresh(store, published, 7))
        assertEquals(published, recovered.identity.reference); assertFalse(pendingFile().exists())
    }
}
