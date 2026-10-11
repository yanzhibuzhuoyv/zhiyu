package com.zhiyu.app.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class LocalBackgroundConcurrencyTest {
    @Test fun losingReservationNeverClosesAnotherWorkersChargedRequest() = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            AgnesCredentialStore(context).write("synthetic-image-key")
            val png = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(0xff6699aa.toInt())
                ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
            }
            val response = JSONObject().put("data", org.json.JSONArray().put(JSONObject()
                .put("b64_json", Base64.getEncoder().encodeToString(png)))).toString()
            val arrived = AtomicInteger(); val both = CompletableDeferred<Unit>()
            val generated = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val client = AgnesImageClient(AgnesImageTransport { _, _ ->
                calls.incrementAndGet(); generated.complete(Unit); release.await(); response
            })
            val scenes = LocalGeneratedBackgrounds(context, client, BackgroundImageApproval { true }) {
                if (arrived.incrementAndGet() == 2) both.complete(Unit)
                both.await()
            }
            val chat = LocalMediaStore.identifier(); val slot = LocalDailyRules.scene(chat, 0, java.time.Instant.now()).slots.first()
            val first = async { scenes.get(chat, "2026-10-10", 0, slot) }
            val second = async { scenes.get(chat, "2026-10-10", 0, slot) }
            withTimeout(10_000) { generated.await() }
            val loser = withTimeout(10_000) { select { first.onAwait { it }; second.onAwait { it } } }
            assertNull(loser)
            release.complete(Unit)
            val results = listOf(first.await(), second.await())
            assertEquals(1, calls.get())
            assertEquals(1, results.count { it != null })
            assertTrue("生成的背景必须能用于本机合成", results.filterNotNull().single().isMutable)
            results.filterNotNull().forEach { it.recycle() }
            assertEquals("success", LocalImageJobStore(context).list(chat).single().state)
        }
    }
}
