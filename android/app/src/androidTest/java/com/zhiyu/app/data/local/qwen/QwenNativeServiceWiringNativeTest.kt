package com.zhiyu.app.data.local.qwen

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import org.json.JSONObject

/** Real App-UID Binder + actual JNI loading, but no model weights, user chat, Key or image reads. */
class QwenNativeServiceWiringNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = QwenImageJobStore(context)
    private val ownedIds = mutableListOf<String>()

    @After fun cleanup() {
        ownedIds.forEach { id ->
            val directory = store.directory(id)
            require(directory.parentFile == store.root && directory.name == id && !Files.isSymbolicLink(directory.toPath()))
            if (!directory.exists()) return@forEach
            Files.walk(directory.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                require(path.startsWith(directory.toPath())); Files.deleteIfExists(path)
            } }
        }
    }

    @Test fun textAndImageHelloLoadActualJniInSeparateOwnedProcessesAndConfirmTheirExit() = runBlocking {
        val client = QwenNativeClient(context)
        val text = client.hello("text")
        val image = client.hello("inspect")
        listOf(text, image).forEach {
            assertEquals(QwenRelease.IDENTITY, it.runtimeIdentity)
            assertEquals(context.applicationInfo.uid, it.uid)
            assertTrue(it.pid > 0 && it.pid != Process.myPid())
        }
        assertEquals(context.packageName + ":qwen_text", text.process)
        assertEquals(context.packageName + ":qwen_image", image.process)
        // hello() returns only after an actual linked Binder DeathRecipient has fired.
    }

    @Test fun registeredMalformedCheckpointFailsWithoutModelLoadingAndAnotherOwnedProcessCanStart() = runBlocking {
        val id = MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        ownedIds.add(id)
        val job = QwenImageJob(id, fixtureId(), LocalDate.now().toString(), 0,
            null, null, fixtureId(), "12:00", "synthetic fixture", emptyList(), 42,
            state = "sampled", tokens = 1, checkpoint = "phases/${UUID.randomUUID()}/partial.qwenlatent")
        store.write(job)
        val checkpoint = store.file(job, requireNotNull(job.checkpoint))
        require(requireNotNull(checkpoint.parentFile).mkdirs())
        checkpoint.writeBytes("broken".toByteArray(Charsets.US_ASCII))
        try {
            QwenNativeClient(context).run(job, "inspect", checkpoint)
            fail("A partial checkpoint was accepted")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("checkpoint", ignoreCase = true) == true)
        }
        assertEquals(6L, checkpoint.length())
        assertEquals(job, store.read(id))
        assertEquals(context.packageName + ":qwen_image", QwenNativeClient(context).hello("inspect").process)
    }

    @Test fun realPhoneCheckpointRejectsWrongSeedThenAcceptsEncodedRetrySeedWithoutLoadingModels() = runBlocking {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("qwen/phone-seed42-reference.qwenlatent").use { it.readBytes() }
        val sha = digest(bytes)
        assertEquals(262288, bytes.size)
        assertEquals("305ea06a35066e3fd290b6e43293128215bde0eb9549d711e999ce59d84f9b61", sha)
        val id = digest(UUID.randomUUID().toString().toByteArray())
        ownedIds.add(id)
        val wrong = QwenImageJob(id, fixtureId(), LocalDate.now().toString(), 0,
            null, null, fixtureId(), "12:00", "real checkpoint reader fixture", emptyList(), 41,
            state = "encoded", tokens = 27, checkpoint = "phases/${UUID.randomUUID()}/real.zqcp")
        store.write(wrong)
        val checkpoint = store.file(wrong, requireNotNull(wrong.checkpoint))
        require(requireNotNull(checkpoint.parentFile).mkdirs())
        checkpoint.writeBytes(bytes)
        val client = QwenNativeClient(context)
        assertEquals(QwenRelease.IDENTITY, client.hello("inspect").runtimeIdentity)
        try { client.run(wrong, "inspect", checkpoint); fail("Seed mismatch was accepted") }
        catch (failure: IllegalStateException) {
            assertFalse("Process deferral cannot substitute for wrong-seed rejection", failure is QwenProcessDeferred)
        }
        assertEquals(wrong, store.read(id))
        assertEquals(sha, digest(checkpoint.readBytes()))
        val retried = wrong.copy(attempt = 1)
        store.write(retried)
        val metadata = JSONObject(requireNotNull(client.run(retried, "inspect", checkpoint).metadata))
        assertEquals(42L, metadata.getLong("seed"))
        assertEquals(8, metadata.getInt("steps"))
        assertEquals(262288L, metadata.getLong("bytes"))
        assertTrue(metadata.getBoolean("crc_verified"))
        assertTrue(metadata.getBoolean("finite"))
        assertFalse(metadata.getBoolean("model_loaded"))
        assertEquals("diffusion_normalized", metadata.getString("stage"))
        assertEquals(retried, store.read(id))
        assertEquals(sha, digest(checkpoint.readBytes()))
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun fixtureId(): String = UUID.randomUUID().toString().replace("-", "")
}
