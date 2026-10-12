package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.local.qwen.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QwenImageJobStoreNativeTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun job() = QwenImageJob("a".repeat(64),"b".repeat(32),"2026-10-10",2,null,null,
        "c".repeat(32),"07:30","早餐",listOf("food","morning"),42,"prepared","A sunny breakfast table.")
    @Test fun allPhasesAndReproductionMetadataSurviveAStoreRestart() {
        NativeV4Context(base).use { context ->
            val store = QwenImageJobStore(context)
            val created = job()
            store.write(created)
            assertEquals(created,QwenImageJobStore(context).read(created.id))
            val phase = store.phaseDirectory(created)
            val condition = File(phase,"conditions.f32").apply { writeBytes(ByteArray(16384)) }
            val encoded = created.copy(state="encoded",tokens=1,condition=store.relative(created,condition))
            store.write(encoded)
            assertEquals(encoded,QwenImageJobStore(context).read(created.id))
        }
    }
    @Test fun nextDaySweepRetainsDecodedImagesAndPromptReceiptsWhileRemovingOnlyTerminalIntermediates() {
        NativeV4Context(base).use { context ->
            val store = QwenImageJobStore(context)
            val created = job()
            store.write(created)
            val phase = store.phaseDirectory(created)
            File(phase,"sample.qwenlatent").writeText("synthetic intermediate")
            val decoded = store.file(created,"decoded-0.png").apply { writeText("synthetic decoded archive") }
            store.write(created.copy(state="approved",decoded="decoded-0.png",decodedSha="d".repeat(64)))
            val foreign = File(context.filesDir,"oc-original.png").apply { writeText("keep original") }
            store.sweepBefore("2026-10-11")
            assertFalse(phase.exists())
            assertEquals("synthetic decoded archive",decoded.readText())
            assertTrue(File(store.directory(created.id),"attempt-0.json").isFile)
            assertEquals("keep original",foreign.readText())
            assertEquals(created.prompt,store.read(created.id)!!.prompt)
        }
    }
    @Test fun aPendingNativePhaseIsNeverSweptAndCannotEscapeItsRegisteredDirectory() {
        NativeV4Context(base).use { context ->
            val store = QwenImageJobStore(context)
            val created = job()
            store.write(created)
            val phase = store.phaseDirectory(created)
            val input = File(phase,"conditions.f32").apply { writeText("retained active condition") }
            store.write(created.copy(state="encoded",condition=store.relative(created,input)))
            store.sweepBefore("2026-10-11")
            assertTrue(input.isFile)
            listOf("../oc.png","phases/../../oc.png","/outside.png").forEach { relative ->
                assertThrows(IllegalArgumentException::class.java) { store.file(created,relative) }
            }
        }
    }
    @Test fun oneCorruptRecordIsRetainedAndCannotBlockAnIndependentValidImage() {
        NativeV4Context(base).use { context ->
            val store = QwenImageJobStore(context); val valid = job()
            store.write(valid)
            val broken = store.directory("f".repeat(64)).apply { check(mkdirs()) }
            val record = File(broken,"job.json").apply { writeText("corrupt synthetic record") }
            assertEquals(listOf(valid), store.pending())
            try { store.registeredOrUnavailable(broken.name); fail("A corrupt paid reservation must not become a new job") }
            catch (failure: LocalSceneBackgroundUnavailable) { assertEquals("unknown",failure.reason) }
            assertNull(store.registeredOrUnavailable("e".repeat(64)))
            store.sweepBefore("2026-10-11")
            assertEquals("corrupt synthetic record", record.readText())
            assertEquals(valid,store.read(valid.id))
        }
    }
}
