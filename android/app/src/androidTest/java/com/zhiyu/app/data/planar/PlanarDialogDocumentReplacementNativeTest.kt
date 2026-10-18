package com.zhiyu.app.data.planar

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import com.zhiyu.app.ui.screens.PlanarPreview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.BeforeClass
import androidx.test.platform.app.InstrumentationRegistry
import rig2d.planar.PlanarMesh
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A real dialog window and real Surface pixels, not just an existing PlanarDocument. */
class PlanarDialogDocumentReplacementNativeTest {
    companion object {
        @BeforeClass @JvmStatic fun foregroundTargetOnlyForAnExplicitUsbDiagnostic() {
            if(InstrumentationRegistry.getArguments().getString("zhiyu_usb_diagnostics")!="true")return
            val result=InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("am start -W -n com.zhiyu.app/.MainActivity")
            val reply=ParcelFileDescriptor.AutoCloseInputStream(result).bufferedReader().use {it.readText()}
            check(!reply.lineSequence().any {it.startsWith("Error")}) {"Target app could not be foregrounded for the USB diagnostic"}
        }
    }
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun replacingTheDocumentInAnOpenDialogLaysOutTheNewSurfaceAndDrawsItsOwnPixels() {
        val red=document(Color.RED,"first")
        val green=document(Color.GREEN,"replacement")
        val blue=document(Color.BLUE,"second replacement")
        var current by mutableStateOf(red)
        val root=AtomicReference<View>()
        val frames=AtomicInteger()
        val lastFrame=AtomicReference<PlanarFrame>()
        val background=AtomicInteger()
        compose.setContent {
            Dialog(onDismissRequest={},properties=DialogProperties(usePlatformDefaultWidth=false)) {
                val view=LocalView.current
                val themeSurface=MaterialTheme.colorScheme.surface.toArgb()
                SideEffect {root.set(view.rootView);background.set(themeSurface)}
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(14.dp)) {
                        PlanarPreview(current,0f,true,Modifier.fillMaxWidth().height(260.dp),
                            onFrame={lastFrame.set(it);frames.incrementAndGet()})
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        compose.waitUntil(10000) {frames.get()>=6}
        val first=surface(root)
        assertPixels(first,Color.RED,background.get())
        for((document,color) in listOf(green to Color.GREEN,blue to Color.BLUE)) {
            val previous=frames.get()
            val previousGeneration=checkNotNull(lastFrame.get()).generation
            compose.runOnIdle {current=document}
            compose.waitUntil(10000) {frames.get()>=previous+6}
            val replacement=surface(root)
            assertSame("A document change must keep the already visible native surface",first,replacement)
            assertTrue("New document must publish a new GPU resource generation",checkNotNull(lastFrame.get()).generation>previousGeneration)
            assertTrue("Replacement surface has to be measured",replacement.width>0&&replacement.height>0)
            assertPixels(replacement,color,background.get())
        }
        val beforePauseGeneration=checkNotNull(lastFrame.get()).generation
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.runOnIdle {current=red}
        val paused=frames.get()
        Thread.sleep(200)
        assertEquals("Publishing a document while paused must not run GPU frames",paused,frames.get())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10000) {frames.get()>=paused+6}
        val resumed=surface(root)
        assertSame(first,resumed)
        assertTrue(checkNotNull(lastFrame.get()).generation>beforePauseGeneration)
        assertPixels(resumed,Color.RED,background.get())
    }

    private fun surface(root:AtomicReference<View>):PlanarSurfaceView {
        var found:PlanarSurfaceView?=null
        compose.runOnIdle {found=find(checkNotNull(root.get()))}
        return checkNotNull(found) {"Dialog does not contain its preview surface"}
    }
    private fun find(view:View):PlanarSurfaceView? {
        if(view is PlanarSurfaceView)return view
        if(view is ViewGroup)for(index in 0 until view.childCount)find(view.getChildAt(index))?.let {return it}
        return null
    }
    private fun assertPixels(view:PlanarSurfaceView,color:Int,background:Int) {
        val copy=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        try {
            val result=CompletableFuture<Int>()
            PixelCopy.request(view,copy,{result.complete(it)},Handler(Looper.getMainLooper()))
            assertEquals("GPU surface must supply actual pixels",PixelCopy.SUCCESS,result.get(5,TimeUnit.SECONDS).toInt())
            val center=copy.getPixel(16,16)
            assertEquals(Color.red(color),Color.red(center))
            assertEquals(Color.green(color),Color.green(center))
            assertEquals(Color.blue(color),Color.blue(center))
            assertEquals(255,Color.alpha(center))
            val edge=copy.getPixel(0,16)
            assertEquals("Dialog letterbox must hide underlying chat",255,Color.alpha(edge))
            assertEquals(Color.red(background),Color.red(edge))
            assertEquals(Color.green(background),Color.green(edge))
            assertEquals(Color.blue(background),Color.blue(edge))
        } finally {copy.recycle()}
    }
    private fun document(color:Int,name:String):PlanarDocument {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val contract=PlanarPackageReader(context.assets.open("planar/contract.json").bufferedReader().use {it.readText()}).contract
        val image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        val png=try {image.eraseColor(color);ByteArrayOutputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG,100,it));it.toByteArray()}}
            finally {image.recycle()}
        val mesh=PlanarMesh(contract,floatArrayOf(0f,0f,32f,0f,0f,32f,32f,32f),
            floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f),intArrayOf(0,1,2,1,3,2))
        return PlanarDocument(32,32,name,png,listOf(PlanarLayer("base","base",0.0,png,mesh)),"{}",contract)
    }
}
