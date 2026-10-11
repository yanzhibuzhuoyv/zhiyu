package com.zhiyu.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhiyu.app.data.AvatarGlRenderer
import com.zhiyu.app.data.OcFlatRenderer
import com.zhiyu.app.domain.*

@Composable fun AvatarPreview(identity: OcIdentity, scene: OcScene, source: Bitmap?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (identity.route == "avatar_3d") {
        var failure by remember(identity.ocId) { mutableStateOf<String?>(null) }
        val main = remember { Handler(Looper.getMainLooper()) }
        val renderer = remember(identity.ocId) { AvatarGlRenderer(identity, scene) {
            main.post { failure = "形象暂时无法显示，请重新打开" }
        } }
        val view = remember(context, renderer) {
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2); preserveEGLContextOnPause = true
                setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                contentDescription = "可旋转的三维角色预览"
            }
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(view, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) view.onPause()
                if (event == Lifecycle.Event.ON_RESUME) { view.onResume(); view.requestRender() }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
            onDispose { lifecycle.removeObserver(observer); view.onPause() }
        }
        if (failure == null) AndroidView(factory = { view }, update = { renderer.update(identity, scene); it.requestRender() }, modifier = modifier)
        else Box(modifier, contentAlignment = Alignment.Center) { Text(checkNotNull(failure)) }
    } else {
        AndroidView(factory = { FlatPreviewView(it) }, update = { view -> view.update(identity, scene, source) }, modifier = modifier)
    }
}

private class FlatPreviewView(context: Context) : View(context) {
    private var identity: OcIdentity? = null
    private var scene = OcScene()
    private var source: Bitmap? = null
    fun update(identity: OcIdentity, scene: OcScene, source: Bitmap?) {
        this.identity = identity; this.scene = scene; this.source = source
        contentDescription = if (identity.route == "photo_subject") "人物局部动画预览" else "可编辑的二维角色预览"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val identity = identity ?: return
        if (width <= 0 || height <= 0 || identity.route == "photo_subject" && (source == null || source?.isRecycled == true)) return
        OcFlatRenderer.draw(canvas, identity, scene, width, height, source)
    }
}
