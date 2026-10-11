package com.zhiyu.app.data

import android.graphics.Bitmap
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class OcBitmapOwnershipNativeTest {
    @Test fun aSuccessfulDecodeTransfersOwnershipWithoutRecyclingTheCallersImage() = runBlocking {
        val bitmap = ownedOcBitmap { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) }
        try { assertFalse(bitmap.isRecycled); assertEquals(8, bitmap.width) } finally { bitmap.recycle() }
    }
    @Test fun cancellationBeforeTheDecodedBitmapReturnsRecyclesTheUntransferredImage() = runBlocking {
        val decoded = AtomicReference<Bitmap>(); val owner = AtomicReference<Job>()
        val job = launch(start = CoroutineStart.LAZY) {
            ownedOcBitmap {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
                    decoded.set(bitmap); owner.get().cancel(CancellationException("synthetic cancelled handoff"))
                }
            }
            fail("Cancelled decode must not transfer ownership")
        }
        owner.set(job); job.start(); job.join()
        val image = checkNotNull(decoded.get())
        try { assertTrue("Decoded bitmap was never handed to its caller, so it must be recycled", image.isRecycled) }
        finally { if (!image.isRecycled) image.recycle() }
    }
}
