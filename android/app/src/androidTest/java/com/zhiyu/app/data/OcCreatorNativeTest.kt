package com.zhiyu.app.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.GLES20
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Synthetic OC files only; no existing identity, media DB, provider Key, API or camera is accessed. */
class OcCreatorNativeTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val isolated = File(base.filesDir, "oc_test_${UUID.randomUUID()}")
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolated
    }
    private val chat = "a".repeat(32)
    private val asset = OcAssetRef("b".repeat(32), "c".repeat(64), 7)
    @After fun cleanup() {
        check(isolated.canonicalPath.startsWith(base.filesDir.canonicalPath + "/oc_test_"))
        isolated.deleteRecursively()
    }
    @Test fun revisionManifestSurvivesReloadWithTwelveWingsAndAWeapon() {
        val initial = OcIdentityRules.fresh("parts_2d")
        val parts = (0 until 12).map { OcPart("wing$it", "wing", if (it == 0) "left" else "right", x = .2f + it * .03f) } +
            OcPart("weapon", "weapon", "left", angle = -25f)
        val ready = OcIdentityRules.next(initial, initial.copy(parts = parts, reference = asset))
        val first = OcCreatorStore(context).commit(chat, 0, ready)
        val restored = checkNotNull(OcCreatorStore(context).read(chat))
        assertEquals(first, restored)
        assertEquals(13, restored.identity.parts.size)
        assertEquals("left", restored.identity.parts.last().side)
        assertEquals(first.packageSha, OcIdentityRules.packageSha(restored.identity))
        assertEquals(0, initial.revision)
        assertTrue(initial.parts.isEmpty())
    }
    @Test fun staleCommitAndTamperedPackageCannotReplaceSavedIdentity() {
        val initial = OcIdentityRules.fresh("avatar_3d").copy(reference = asset)
        val ready = OcIdentityRules.next(initial, initial)
        val store = OcCreatorStore(context)
        val saved = store.commit(chat, 0, ready)
        try { store.commit(chat, 0, ready); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(saved, store.read(chat))
        val file = File(isolated, "oc_identity_v1/$chat.json")
        val json = JSONObject(file.readText())
        json.getJSONObject("identity").getJSONObject("body").put("head_width", 1.2)
        val changed = json.toString()
        assertNotEquals(file.readText(), changed)
        file.writeText(changed)
        try { store.read(chat); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun photoNeutralRenderKeepsEveryOriginalPixelAndDetachedAccessory() {
        val photo = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            photo.eraseColor(0)
            for (y in 5..50) for (x in 18..46) photo.setPixel(x, y, 0xffd7a4a0.toInt())
            photo.setPixel(1, 1, 0xff5a7182.toInt()); photo.setPixel(62, 59, 0xffb1a267.toInt())
            val identity = OcIdentityRules.fresh("photo_subject")
            val rendered = OcFlatRenderer.render(identity, OcScene(background = 0), 64, 64, photo)
            try { for (y in 0 until 64) for (x in 0 until 64) assertEquals(photo.getPixel(x, y), rendered.getPixel(x, y)) }
            finally { rendered.recycle() }
            val moved = OcFlatRenderer.render(identity, OcScene(background = 0, motion = OcMotion(headTurn = 1f, mouthOpen = .7f)), 64, 64, photo)
            try { assertEquals(photo.getPixel(1, 1), moved.getPixel(1, 1)); assertEquals(photo.getPixel(62, 59), moved.getPixel(62, 59)) }
            finally { moved.recycle() }
        } finally { photo.recycle() }
    }
    @Test fun twoDimensionalRenderSupportsHeightExtremesAndIndependentAccessories() {
        val base = OcIdentityRules.fresh("parts_2d").copy(parts = listOf(
            OcPart("left", "wing", "left", x = .9f, y = .5f, width = .6f),
            OcPart("only_right", "wing", "right", x = .1f, y = .55f, angle = 22f),
            OcPart("sword", "weapon", "left", x = .7f, y = .63f, layer = 1)))
        for (height in listOf(.65f, 1.35f)) {
            val identity = base.copy(body = base.body.copy(height = height))
            val png = OcFlatRenderer.png(identity, OcScene(background = 0), 384, 512)
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
            try { assertEquals(384, bitmap.width); assertEquals(512, bitmap.height); assertTrue(colorCount(bitmap) > 20) }
            finally { bitmap.recycle() }
            assertEquals(3, identity.parts.size)
        }
    }
    @Test fun openGlPbufferRendersRealGeometryWithCameraDependentPixels() {
        val identity = OcIdentityRules.fresh("avatar_3d").copy(parts = listOf(
            OcPart("one_wing", "wing", "left", x = .77f, y = .4f),
            OcPart("sword", "weapon", "right", x = .26f, y = .55f, z = .4f)))
        val sha = OcIdentityRules.packageSha(identity)
        val front = AvatarStillRenderer.png(identity, OcScene(background = 0), 256, 384)
        val side = AvatarStillRenderer.png(identity, OcScene(cameraYaw = 70f, background = 0), 256, 384)
        assertNotEquals(hash(front), hash(side))
        val image = checkNotNull(BitmapFactory.decodeByteArray(front, 0, front.size))
        try { assertTrue(colorCount(image) > 20); assertEquals(256, image.width) } finally { image.recycle() }
        assertEquals(sha, OcIdentityRules.packageSha(identity))
        assertEquals(2, identity.parts.size)
    }
    @Test fun multiWingGeometryFitsTheWholeFrameAcrossCameraAngles() {
        val identity = OcIdentityRules.fresh("avatar_3d").copy(parts = (0 until 12).map {
            OcPart("wing$it", "wing", if (it % 2 == 0) "left" else "right", x = if (it % 2 == 0) .95f else .05f,
                y = .2f + (it / 2) * .12f, width = .85f, height = .4f, angle = (it - 6) * 8f)
        } + listOf(OcPart("weapon", "weapon", "left", x = .9f, y = .9f, height = .9f, z = .8f),
            OcPart("high_horn", "horn", "unspecified", x = .95f, y = 0f, width = .9f, height = .9f, z = 1f)))
        val sha = OcIdentityRules.packageSha(identity)
        for (yaw in listOf(0f, 60f, 140f)) {
            val bitmap = AvatarStillRenderer.render(identity, OcScene(cameraYaw = yaw, cameraPitch = 60f, background = 0), 256, 384)
            try {
                assertTrue(colorCount(bitmap) > 20)
                for (x in 0 until bitmap.width) {
                    assertEquals(0, bitmap.getPixel(x, 0)); assertEquals(0, bitmap.getPixel(x, bitmap.height - 1))
                }
                for (y in 0 until bitmap.height) {
                    assertEquals(0, bitmap.getPixel(0, y)); assertEquals(0, bitmap.getPixel(bitmap.width - 1, y))
                }
            } finally { bitmap.recycle() }
        }
        assertEquals(14, identity.parts.size); assertEquals(sha, OcIdentityRules.packageSha(identity))
    }
    @Test fun offscreenRenderingRestoresExistingEglBindingAndKeepsItsResourcesAlive() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val count = IntArray(1)
        val attributes = intArrayOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RENDERABLE_TYPE,
            EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE)
        assertTrue(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)); assertTrue(count[0] > 0)
        val config = checkNotNull(configs[0])
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 32, EGL14.EGL_HEIGHT, 32, EGL14.EGL_NONE), 0)
        try {
            assertNotEquals(EGL14.EGL_NO_CONTEXT, context); assertNotEquals(EGL14.EGL_NO_SURFACE, surface)
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
            repeat(3) { AvatarStillRenderer.render(OcIdentityRules.fresh("avatar_3d"), OcScene(), 64, 64).recycle() }
            assertEquals(context, EGL14.eglGetCurrentContext()); assertEquals(surface, EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW))
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
            GLES20.glClearColor(.5f, .25f, .75f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
        }
    }
    @Test fun sceneRendererUsesSavedPhotoSourceAndRejectsStaleRevisionBeforeReading() = runBlocking {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffc7b6a5.toInt()) }
        val encoded = ByteArrayOutputStream().use { out -> source.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        source.recycle()
        val sourceRef = OcAssetRef("d".repeat(32), hash(encoded), 3)
        val draft = OcIdentityRules.fresh("photo_subject").copy(reference = asset, photoSource = sourceRef)
        val saved = OcCreatorStore(context).commit(chat, 0, OcIdentityRules.next(draft, draft))
        var reads = 0
        val renderer = CharacterSceneRenderer(context) { ref -> assertEquals(sourceRef, ref); reads++; encoded }
        try { renderer.render(chat, "e".repeat(64), OcScene(), 64, 64); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, reads)
        val result = renderer.render(chat, saved.packageSha, OcScene(background = 0), 64, 64)
        assertEquals(1, reads); assertEquals(sourceRef.sha256, result.sourceSha)
        assertEquals(saved.identity.ocId, result.ocId); assertEquals(saved.identity.revision, result.revision)
        assertEquals(7, result.visualVersion); assertEquals(saved.packageSha, result.packageSha)
        val leaked = result.png; leaked[0] = 0
        assertEquals(0x89.toByte(), result.png[0])
        assertEquals(hash(result.png), result.sha256)
    }
    @Test fun pendingPublicationRecoversAfterProfilePublishWithoutRepeatingRenderOrChangingIdentity() {
        val draft = OcIdentityRules.fresh("parts_2d").copy(parts = listOf(OcPart("one", "wing", "left")))
        val proposed = OcIdentityRules.next(draft, draft)
        val store = OcCreatorStore(context)
        store.beginPublication(chat, 0, 6, proposed, asset.sha256, "first")
        assertNull(store.recoverPublication(chat, asset)) // The active attempt is still responsible for its commit.
        store.finishPublication(chat, "first", false)
        val recovered = checkNotNull(OcCreatorStore(context).recoverPublication(chat, asset))
        assertEquals(proposed.ocId, recovered.identity.ocId); assertEquals(1, recovered.identity.revision)
        assertEquals(proposed.parts, recovered.identity.parts); assertEquals(asset, recovered.identity.reference)
        assertFalse(File(isolated, "oc_identity_v1/$chat.pending.json").exists())
        assertEquals(recovered, OcCreatorStore(context).read(chat))
    }
    @Test fun pendingPublicationNeverAdoptsAnUnrelatedImageOrWrongProfileVersion() {
        val draft = OcIdentityRules.fresh("avatar_3d")
        val proposed = OcIdentityRules.next(draft, draft)
        val store = OcCreatorStore(context)
        store.beginPublication(chat, 0, 6, proposed, asset.sha256, "first")
        store.finishPublication(chat, "first", false)
        assertNull(store.recoverPublication(chat, asset.copy(sha256 = "e".repeat(64))))
        assertNull(store.recoverPublication(chat, asset.copy(visualVersion = 8)))
        assertNull(store.read(chat))
        assertTrue(File(isolated, "oc_identity_v1/$chat.pending.json").exists())
    }
    @Test fun concurrentCreatorCannotPublishTheSameBaseRevisionAndCompletedManifestCanCleanItsIntent() {
        val draft = OcIdentityRules.fresh("parts_2d")
        val proposed = OcIdentityRules.next(draft, draft)
        val store = OcCreatorStore(context)
        store.beginPublication(chat, 0, 6, proposed, asset.sha256, "first")
        try {
            try { OcCreatorStore(context).beginPublication(chat, 0, 6, proposed, asset.sha256, "second"); fail() }
            catch (_: IllegalArgumentException) { }
            val saved = store.commit(chat, 0, proposed.copy(reference = asset))
            store.finishPublication(chat, "first", false) // Simulate interruption after manifest commit, before intent cleanup.
            assertEquals(saved, store.recoverPublication(chat, asset))
            assertFalse(File(isolated, "oc_identity_v1/$chat.pending.json").exists())
        } finally { store.finishPublication(chat, "first", false) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun colorCount(bitmap: Bitmap): Int {
        val colors = HashSet<Int>()
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) colors.add(bitmap.getPixel(x, y))
        return colors.size
    }
}
