package com.zhiyu.app.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class LocalMediaSnapshotTest {
    @Test fun identicalPixelsKeepSeparateDaysAndSlotsWhileSharingTheOriginalBlob() {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val bitmap = Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).apply { eraseColor(0xff8899aa.toInt()) }
            val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG,100,out); bitmap.recycle(); out.toByteArray() }
            val store = LocalMediaStore(context)
            val old = store.ingest("image",bytes,dayId="2026-10-09",tags=listOf("daily_stock","slot_0000"))
            val next = store.ingest("image",bytes,dayId="2026-10-10",tags=listOf("daily_stock","slot_0000"))
            val afternoon = store.ingest("image",bytes,dayId="2026-10-10",tags=listOf("daily_stock","slot_1300"))
            assertNotEquals(old.id,next.id); assertNotEquals(next.id,afternoon.id)
            assertEquals("2026-10-10",next.dayId)
            assertEquals(next.id,store.ingest("image",bytes,dayId="2026-10-10",tags=listOf("daily_stock","slot_0000")).id)
            assertEquals(1,File(context.filesDir,"local_media/image/blobs").listFiles()!!.size)
            assertArrayEquals(bytes,store.download(old.id,old.sha256))
        }
    }

    @Test fun aDescriptionWithoutAPortraitCanStillSendTodaysPreparedLandscape()=runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val chat=LocalMediaStore.identifier()
            val visual=LocalVisualRepository(context)
            val profile=visual.update(chat,"import","短发的原创角色","other","short")
            assertNull(profile.referenceId); assertTrue(profile.visualVersion>0)
            LocalSceneStock(context).prepare(chat)
            val media=LocalReplyMedia(context); val plan=media.prepare(chat,null,emptyList())
            val store=LocalMediaStore(context)
            val chosen=plan.candidates.first { "daily_stock" in store.details(it.id).tags }
            val reply=media.commit(chat,plan,"陪你看看今天的光。\n[[ZHIYU_MEDIA_${plan.nonce}:${chosen.id}]]") { it }
            assertEquals(listOf(chosen),reply.attachments)
        }
    }
}
