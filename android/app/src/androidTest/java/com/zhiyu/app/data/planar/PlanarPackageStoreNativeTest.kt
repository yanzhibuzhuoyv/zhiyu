package com.zhiyu.app.data.planar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import rig2d.planar.PlanarMesh

@RunWith(AndroidJUnit4::class)
class PlanarPackageStoreNativeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val contractText get() = context.assets.open("planar/contract.json").bufferedReader().use { it.readText() }
    private fun document(reader: PlanarPackageReader): PlanarDocument {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val png = try {
            bitmap.eraseColor(Color.rgb(130, 90, 60))
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
        val mesh = PlanarMesh(reader.contract, floatArrayOf(0f, 0f, 32f, 0f, 0f, 32f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))
        val definition = """{"anchors":{"head":[16,8],"neck":[16,20],"eyeLeft":[6,6,10,10],"eyeRight":[20,6,24,10],"mouth":[12,14,20,18]},"parts":[],"metadata":{"generator":"android-neighborhood-v1","desktopPixelEquivalent":false}}"""
        return PlanarDocument(32, 32, "本机存储验证", png, listOf(PlanarLayer("base", "base", -1001.0, png, mesh)), definition, reader.contract)
    }
    private fun withStore(block: (PlanarPackageStore, File, PlanarPackageReader) -> Unit) {
        val root = File(context.filesDir, "planar-test-${UUID.randomUUID()}")
        val text = contractText
        try { block(PlanarPackageStore(root, text), root, PlanarPackageReader(text)) }
        finally { check(root.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)); root.deleteRecursively() }
    }
    @Test fun androidPngAtomicStoreRecreationAndNativeArchiveRoundTrip() = withStore { store, root, reader ->
        val original = document(reader)
        store.save(original)
        val recovered = PlanarPackageStore(root, contractText).loadActive()!!
        assertEquals(original.name, recovered.name)
        val exported = reader.read(ByteArrayInputStream(store.export()))
        val bitmap = BitmapFactory.decodeByteArray(exported.sourcePng, 0, exported.sourcePng.size)
        try { assertEquals(Color.rgb(130, 90, 60), bitmap.getPixel(16, 16)) } finally { bitmap.recycle() }
        assertTrue(root.listFiles()!!.all { it.name == "active.planar" })
    }
    @Test fun cancellationDuringStreamingAndInvalidArchivePreserveActiveOnDevice() = withStore { store, _, reader ->
        store.save(document(reader))
        val previous = store.export()
        val source = previous.inputStream()
        var read = false
        val cancelled = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancelled")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (read) throw CancellationException("cancelled")
                read = true; return source.read(buffer, offset, length)
            }
        }
        assertThrows(CancellationException::class.java) { store.import(cancelled) }
        assertThrows(IllegalArgumentException::class.java) { store.import(byteArrayOf(1, 2, 3).inputStream()) }
        assertArrayEquals(previous, store.export())
    }
    @Test fun separateConfirmedSourceDirectoriesNeverShareActiveDocuments() = withStore { _, root, reader ->
        val first = PlanarPackageStore(File(root, "source-a"), contractText)
        val second = PlanarPackageStore(File(root, "source-b"), contractText)
        first.save(document(reader))
        assertNull(second.loadActive())
    }
}
