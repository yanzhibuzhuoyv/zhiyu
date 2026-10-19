package com.zhiyu.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import com.zhiyu.app.ui.screens.CharacterPreview
import com.zhiyu.app.ui.theme.ZhiYuTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream

/** Isolated synthetic pictures; no provider Key, image API, microphone or real chat is accessed. */
class CallCharacterNativeTest {
    @get:Rule val compose = createComposeRule()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = NativeV4Context(base)
    private val jobs = LocalImageJobStore(context)
    private val repository = LocalVisualRepository(context, imageKey = { null }, jobs = jobs)
    private val assets = LocalMediaStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val chat = "a".repeat(32)
    private val color = 0xff714e8a.toInt()

    @After fun cleanup() {
        scope.cancel(); jobs.close(); context.close()
        val root = context.filesDir.canonicalFile
        check(root.parentFile == base.filesDir.canonicalFile && root.name.startsWith("v4_"))
        check(root.deleteRecursively())
    }
    private fun png(tint: Int = color): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(tint)
            return ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    private fun reference(profile: VisualProfile): OcAssetRef? = profile.referenceId?.let {
        val asset = assets.details(it).attachment
        OcAssetRef(asset.id, asset.sha256, profile.visualVersion)
    }
    private suspend fun controller(profile: VisualProfile): OcCreatorController = OcCreatorController(context, scope,
        publish = { _, _, _, _ -> throw AssertionError("call must not republish or consume an image opportunity") },
        loadImage = { assets.download(it.id, it.sha256) }).also { it.connect(chat, reference(profile), profile.visualVersion) }
    private suspend fun assertSource(controller: OcCreatorController, profile: VisualProfile, expected: ByteArray) {
        val identity = checkNotNull(controller.callIdentity)
        assertTrue(identity.confirmed); assertEquals("photo_subject", identity.route)
        assertEquals(reference(profile), identity.photoSource)
        assertArrayEquals(expected, assets.download(checkNotNull(profile.referenceId)))
        val bitmap = checkNotNull(controller.source(identity))
        val expectedImage = checkNotNull(BitmapFactory.decodeByteArray(expected, 0, expected.size))
        try {
            assertEquals(expectedImage.width, bitmap.width); assertEquals(expectedImage.height, bitmap.height)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                assertEquals(expectedImage.getPixel(x, y), bitmap.getPixel(x, y))
        } finally { expectedImage.recycle(); bitmap.recycle() }
        assertNull(OcCreatorStore(context).read(chat))
    }
    @Test fun existingImportedImageReallyAppearsInTheCallWithoutAnEditorSave() {
        val controller = runBlocking {
            val profile = repository.import(chat, png())
            controller(profile).also { assertSource(it, profile, png()) }
        }
        compose.setContent { ZhiYuTheme {
            CharacterPreview(controller.callIdentity, OcMotion(), controller::source, Modifier.size(200.dp).testTag("call_photo_preview"))
        } }
        waitForPhoto(color)
        compose.onNodeWithText("先设定恋人的形象").assertDoesNotExist()
        val pixels = compose.onNodeWithTag("call_photo_preview").captureToImage().toPixelMap()
        assertEquals(color, pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }
    private fun waitForPhoto(expectedColor: Int = color) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("形象准备中…").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("先设定恋人的形象").assertDoesNotExist()
        compose.onNodeWithText("形象暂时无法显示").assertDoesNotExist()
        compose.onNodeWithTag("call_photo_preview").assertExists()
        // Bitmap readiness replaces the loading label before AndroidView has necessarily
        // completed its first native draw. Await the real pixels, not an arbitrary sleep.
        compose.waitUntil(10_000) {
            val pixels = compose.onNodeWithTag("call_photo_preview").captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2].toArgb() == expectedColor
        }
    }
    @Test fun anOpenCallUpdatesItsPictureWithoutDependingOnAnimationFrames() {
        val controller = runBlocking { controller(repository.import(chat, png())) }
        compose.setContent { ZhiYuTheme {
            CharacterPreview(controller.callIdentity, OcMotion(), controller::source, Modifier.size(200.dp).testTag("call_photo_preview"))
        } }
        waitForPhoto()
        val before = controller.identity
        runBlocking {
            val fresh = repository.import(chat, png(0xff927043.toInt()))
            controller.connect(chat, reference(fresh), fresh.visualVersion)
        }
        assertEquals(before, controller.identity)
        compose.waitForIdle(); waitForPhoto(0xff927043.toInt())
        val pixels = compose.onNodeWithTag("call_photo_preview").captureToImage().toPixelMap()
        assertEquals(0xff927043.toInt(), pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }
    @Test fun confirmedCutoutNeedsNoSeparateManifestOrGeneration() = runBlocking {
        val preview = repository.previewSubject(chat, png())
        val visible = repository.refineSubject(chat, preview.id, preview.subjectSha256,
            listOf(PortraitStroke("keep", listOf(PortraitPoint(.5f, .5f)), .1f)))
        val profile = repository.confirmSubject(chat, visible.id, "subject", visible.subjectSha256)
        assertSource(controller(profile), profile, assets.download(visible.subjectId, visible.subjectSha256))
        assertEquals(2, profile.generation.remaining)
    }
    @Test fun adoptingAnExistingGeneratedCandidateIsUsableAfterControllerRecreation() = runBlocking {
        val raw = png()
        val asset = assets.ingest("image", raw, "synthetic generated OC", 1, purpose = "portrait")
        val request = "d".repeat(32)
        jobs.reserve(LocalGenerationJob(request, chat, "oc", 0, "2026-10-11", "e".repeat(64), "reserved"))
        jobs.finish(request, "success", asset.id)
        val profile = repository.useGeneratedCandidate(chat, asset.id)
        assertEquals("generate", profile.mode)
        assertSource(controller(profile), profile, raw)
        assertSource(controller(repository.profile(chat)), profile, raw)
        assertEquals(1, repository.profile(chat).generation.used)
    }
    @Test fun savingGenderAndHairKeepsTheConfirmedPictureAndStillAdvancesVersion() = runBlocking {
        val before = repository.import(chat, png())
        val after = repository.update(chat, before.mode, "单翼，长发", "female", "long")
        assertEquals(before.referenceId, after.referenceId)
        assertEquals(before.visualVersion + 1, after.visualVersion)
        assertSource(controller(after), after, png())
    }
    @Test fun switchingPicturesAndChatsNeverKeepsAnOldSource() = runBlocking {
        val first = repository.import(chat, png())
        val controller = controller(first)
        val next = repository.import(chat, png(0xff927043.toInt()))
        controller.connect(chat, reference(next), next.visualVersion)
        assertEquals(reference(next), controller.callIdentity?.photoSource)
        val bitmap = checkNotNull(controller.source(checkNotNull(controller.callIdentity)))
        try { assertEquals(0xff927043.toInt(), bitmap.getPixel(32, 32)) } finally { bitmap.recycle() }
        controller.connect("b".repeat(32), null)
        assertNull(controller.callIdentity)
        controller.connect(null, null)
        assertNull(controller.callIdentity)
    }
    @Test fun descriptionOnlyAndExplicitRemovalDoNotInventAPhoto() = runBlocking {
        val description = repository.update(chat, "import", "长发单翼", "other", "long")
        assertNull(controller(description).callIdentity)
        val before = repository.import(chat, png())
        val removed = repository.update(chat, "none", before.description, before.gender, before.hair)
        assertNull(removed.referenceId)
        assertNull(controller(removed).callIdentity)
    }

    @Test fun legacyModelsKeepTheirExactPublishedPictureAndArchiveForCallsAndScenes() = runBlocking {
        val raw = png()
        val profile = repository.import(chat, raw)
        val ref = checkNotNull(reference(profile))
        val store = OcCreatorStore(context)
        for ((index, route) in listOf("parts_2d", "avatar_3d").withIndex()) {
            val old = store.read(chat)?.identity ?: OcIdentityRules.fresh(route)
            val proposal = old.copy(route = route, parts = listOf(OcPart("left_wing", "wing", "left")),
                body = OcBody(headWidth = 1.2f))
            val saved = store.commit(chat, index, OcIdentityRules.next(old, proposal).copy(reference = ref))
            val display = checkNotNull(controller(profile).callIdentity)
            assertEquals("photo_subject", display.route); assertEquals(ref, display.photoSource)
            assertTrue(display.parts.isEmpty()); assertEquals(OcBody(), display.body)
            var reads = 0
            val rendered = CharacterSceneRenderer(context) { source -> assertEquals(ref, source); reads++; raw }
                .render(chat, saved.packageSha, OcScene(background = 0), 64, 64)
            assertEquals(1, reads); assertEquals(ref.sha256, rendered.sourceSha)
            assertEquals(saved.packageSha, rendered.packageSha)
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(rendered.png, 0, rendered.png.size))
            try { assertEquals(color, bitmap.getPixel(32, 32)) } finally { bitmap.recycle() }
            assertEquals(saved, store.read(chat))
        }
    }

    @Test fun controllerAndPublicationRejectModelCreationWithoutChangingThePicture() = runBlocking {
        val profile = repository.import(chat, png())
        val controller = controller(profile)
        for (route in listOf("parts_2d", "avatar_3d")) {
            try { controller.draft(route); fail("model draft accepted") } catch (_: IllegalArgumentException) { }
            val model = OcIdentityRules.fresh(route).copy(confirmed = true)
            try { repository.publishCharacter(chat, model, png(), profile.visualVersion); fail("model published") }
            catch (_: IllegalArgumentException) { }
            assertEquals(profile, repository.profile(chat))
            val reject = controller(profile)
            val initial = checkNotNull(reject.identity)
            var completed = false
            reject.save(initial.copy(route = route)) { completed = true }
            withTimeout(10_000) { while (reject.error == null) delay(10) }
            assertEquals("请通过导入或生成图片设置恋人外观", reject.error)
            assertFalse(completed); assertEquals(profile, repository.profile(chat))
        }
    }

    @Test fun appearancePanelOnlyOffersPictureAnimationWithNoModelChoices() {
        val controller = runBlocking { controller(repository.import(chat, png())) }
        compose.setContent { ZhiYuTheme { com.zhiyu.app.ui.screens.OcCreatorPanel(controller) } }
        compose.onNodeWithTag("oc_photo_animation").assertIsEnabled()
        compose.onNodeWithText("非真人 2D").assertDoesNotExist()
        compose.onNodeWithText("非真人 3D").assertDoesNotExist()
        compose.onNodeWithTag("oc_add_part").assertDoesNotExist()
        compose.onNodeWithTag("oc_face_width").assertDoesNotExist()
    }
}
