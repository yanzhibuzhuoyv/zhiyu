package com.zhiyu.app.data.planar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.planar.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** Full-size synthetic masks. Sampling is emulator process heap, never a phone or automatic quality claim. */
class PlanarResourceBudgetNativeTest {
    private val reader get()=PlanarPackageReader(InstrumentationRegistry.getInstrumentation().targetContext.assets
        .open("planar/contract.json").bufferedReader().use {it.readText()})
    @Test(timeout=120000) fun sixty1536MasksAreDecodedSequentiallyWithinTextureBudget() {
        val side=1536
        val source=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888).also {it.eraseColor(0xff967a62.toInt())}
        try {
            val draft=PlanarDraft.suggest(side,side).copy(suggestionsConfirmed=true,
                featureVisibility=PlanarFeatureVisibility(false,false,false),parts=masks(side,60,false))
            measured("sixty_masks_1536") {
                val document=PlanarPhotoBuilder().build(source,draft,"60个附件",reader.contract)
                assertEquals(61,document.layers.size)
                val textureBytes=document.layers.sumOf {layer->
                    val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(layer.texturePng,0,layer.texturePng.size,options)
                    options.outWidth.toLong()*options.outHeight*4
                }
                assertTrue(textureBytes<=PlanarDraft.MAX_TEXTURE_BYTES)
                assertEquals(0xff967a62.toInt(),source.getPixel(0,0))
            }
        } finally {source.recycle()}
    }
    @Test(timeout=120000) fun sparseHugeLayerBoundsFailBeforeAllocatingOversizedTextures() {
        val side=1536
        val source=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888).also {it.eraseColor(0xff967a62.toInt())}
        try {
            val draft=PlanarDraft.suggest(side,side).copy(suggestionsConfirmed=true,
                featureVisibility=PlanarFeatureVisibility(false,false,false),parts=masks(side,3,true))
            measured("texture_bounds_fail_fast_1536") {
                val failure=assertThrows(IllegalArgumentException::class.java){PlanarPhotoBuilder().build(source,draft,"过大图层",reader.contract)}
                assertTrue(failure.message!!.contains("24MB"));assertFalse(source.isRecycled)
                assertEquals(0xff967a62.toInt(),source.getPixel(0,0))
            }
        } finally {source.recycle()}
    }
    private fun masks(side:Int,count:Int,hugeBounds:Boolean):List<PlanarPart> {
        val bitmap=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888)
        try {return (0 until count).map {index->
            bitmap.eraseColor(0xff000000.toInt())
            val x=if(hugeBounds)10+index else 20+(index%10)*120
            val y=if(hugeBounds)10+index else 20+(index/10)*120
            bitmap.setPixel(x,y,-1)
            if(hugeBounds)bitmap.setPixel(side-11-index,side-11-index,-1)
            val bytes=ByteArrayOutputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it));it.toByteArray()}
            PlanarPart("part_$index","附件$index","accessory",20.0+index,emptyList(),PlanarPoint(x.toDouble(),y.toDouble()),
                baseMaskPng=Base64.getEncoder().encodeToString(bytes))
        }}finally {bitmap.recycle()}
    }
    private fun measured(label:String,block:()->Unit) {
        val runtime=Runtime.getRuntime();val running=AtomicBoolean(true)
        val beforeJava=runtime.totalMemory()-runtime.freeMemory();val beforeNative=Debug.getNativeHeapAllocatedSize()
        var peakJava=beforeJava;var peakNative=beforeNative;var samples=0
        val started=SystemClock.elapsedRealtime()
        val sampler=Thread {
            while(running.get()) {
                peakJava=maxOf(peakJava,runtime.totalMemory()-runtime.freeMemory())
                peakNative=maxOf(peakNative,Debug.getNativeHeapAllocatedSize());samples++
                try {Thread.sleep(10)}catch(interrupted:InterruptedException){Thread.currentThread().interrupt();break}
            }
        }.apply {isDaemon=true;name="planar-heap-sampler";start()}
        try {block()}finally {
            running.set(false);sampler.join(2000);check(!sampler.isAlive)
            val report=JSONObject().put("label",label).put("elapsedMs",SystemClock.elapsedRealtime()-started)
                .put("javaBeforeBytes",beforeJava).put("javaSamplePeakBytes",peakJava).put("javaLimitBytes",runtime.maxMemory())
                .put("nativeBeforeBytes",beforeNative).put("nativeSamplePeakBytes",peakNative).put("samples",samples)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {putString("stream","\nPLANAR_RESOURCE $report\n")})
        }
    }
}
