package com.zhiyu.app.data

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** Own the native bitmap before a cancellable dispatch can discard its return value. */
internal suspend fun ownedOcBitmap(decode: () -> Bitmap): Bitmap {
    val owned = AtomicReference<Bitmap?>()
    try {
        withContext(Dispatchers.IO) { owned.set(decode()) }
        currentCoroutineContext().ensureActive()
        return checkNotNull(owned.getAndSet(null))
    } finally { owned.getAndSet(null)?.recycle() }
}
