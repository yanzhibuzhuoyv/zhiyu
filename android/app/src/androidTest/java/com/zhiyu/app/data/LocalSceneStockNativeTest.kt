package com.zhiyu.app.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant

class LocalSceneStockNativeTest {
    private suspend fun isolated(test: suspend (NativeV4Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = NativeV4Context(base)
        try { test(context) } finally {
            context.close()
            val root = context.filesDir.canonicalFile
            check(root.parentFile == base.filesDir.canonicalFile && root.name.startsWith("v4_"))
            check(root.deleteRecursively())
        }
    }
    private fun historicalPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff714e8a.toInt())
            return ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    @Test fun localStockNeedsNoUploadsOrImageKeyAndReusesOneDatedSet()=runBlocking {
        isolated { context ->
            val instant=Instant.now()
            val chat=LocalMediaStore.identifier(); val stock=LocalSceneStock(context,now={instant})
            stock.prepare(chat)
            val store=LocalMediaStore(context); val pictures=store.list("image")
            val plans=LocalDailyRepository(context).use { it.horizon(chat,0,instant) }
            assertEquals(LocalScenePrefetchRules.targets(plans,instant).size,pictures.size)
            assertEquals(pictures.size,pictures.map { it.sha256 }.distinct().size)
            assertTrue(pictures.all { it.dayId in LocalScenePrefetchRules.days(instant) && it.visualVersion==0 })
            assertEquals(plans[1].slots.size,pictures.count { it.dayId==plans[1].dayId })
            assertTrue(pictures.all { "local_placeholder" in store.details(it.id).tags })
            assertTrue(pictures.none { "qwen_generated" in store.details(it.id).tags })
            stock.prepare(chat)
            assertEquals(pictures.map { it.id },store.list("image").map { it.id })
        }
    }
    @Test fun historicalModelPicturesFeedScenesWithoutRepublishingAndOldVersionsCannotBeOffered()=runBlocking {
        for (route in listOf("parts_2d", "avatar_3d")) {
        isolated { context ->
            val chat=LocalMediaStore.identifier(); val visual=LocalVisualRepository(context)
            val before=OcIdentityRules.fresh(route)
            val draft=OcIdentityRules.next(before,before.copy(parts=listOf(OcPart("only-wing","wing","left"),OcPart("weapon","weapon","right"))))
            val original=historicalPng()
            // Simulate a prior version's already published PNG, never reopen model creation.
            val profile=visual.import(chat,original)
            val asset=LocalMediaStore(context).details(checkNotNull(profile.referenceId)).attachment
            val ref=OcAssetRef(asset.id,asset.sha256,profile.visualVersion)
            val archive=OcCreatorStore(context)
            val saved=archive.commit(chat,0,draft.copy(reference=ref))
            LocalSceneStock(context).prepare(chat)
            val assets=LocalMediaStore(context)
            val people=assets.list("image").filter { "contains_person" in assets.details(it.id).tags }
            assertTrue(people.isNotEmpty()); assertTrue(people.all { it.visualVersion==ref.visualVersion })
            assertArrayEquals(original,assets.download(ref.id,ref.sha256))
            assertEquals(saved,archive.read(chat))
            assertEquals(profile,visual.profile(chat))
            visual.update(chat,"import","新场景设定","other","long")
            val newer=visual.profile(chat).visualVersion
            assertTrue(people.all { !LocalDayStockRules.available(it,assets.details(it.id).tags.toSet(),LocalDailyRules.dayId(Instant.now()),"23:59",newer) })
            assertArrayEquals(original,assets.download(ref.id,ref.sha256))
        }
        }
    }
}
