package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LocalSceneStockNativeTest {
    @Test fun localStockNeedsNoUploadsOrImageKeyAndReusesOneDatedSet()=runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val chat=LocalMediaStore.identifier(); val stock=LocalSceneStock(context)
            stock.prepare(chat)
            val store=LocalMediaStore(context); val pictures=store.list("image")
            val scene=LocalDailyRepository(context).use { it.scene(chat,0) }
            assertEquals(scene.slots.size,pictures.size)
            assertEquals(pictures.size,pictures.map { it.sha256 }.distinct().size)
            assertTrue(pictures.all { it.dayId==LocalDailyRules.dayId(Instant.now()) && it.visualVersion==0 })
            stock.prepare(chat)
            assertEquals(pictures.map { it.id },store.list("image").map { it.id })
        }
    }
    @Test fun exactSavedTwoDimensionalIdentityFeedsScenesAndOldVersionCannotBeOffered()=runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val chat=LocalMediaStore.identifier(); val visual=LocalVisualRepository(context)
            val before=OcIdentityRules.fresh("parts_2d")
            val draft=OcIdentityRules.next(before,before.copy(parts=listOf(OcPart("only-wing","wing","left"),OcPart("weapon","weapon","right"))))
            val original=OcFlatRenderer.png(draft)
            val ref=visual.publishCharacter(chat,draft,original,0)
            OcCreatorStore(context).commit(chat,0,draft.copy(reference=ref))
            LocalSceneStock(context).prepare(chat)
            val assets=LocalMediaStore(context)
            val people=assets.list("image").filter { "contains_person" in assets.details(it.id).tags }
            assertTrue(people.isNotEmpty()); assertTrue(people.all { it.visualVersion==ref.visualVersion })
            assertArrayEquals(original,assets.download(ref.id,ref.sha256))
            visual.update(chat,"import","新场景设定","other","long")
            val newer=visual.profile(chat).visualVersion
            assertTrue(people.all { !LocalDayStockRules.available(it,assets.details(it.id).tags.toSet(),LocalDailyRules.dayId(Instant.now()),"23:59",newer) })
            assertArrayEquals(original,assets.download(ref.id,ref.sha256))
        }
    }
}
