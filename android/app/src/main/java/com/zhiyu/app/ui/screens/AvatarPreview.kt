package com.zhiyu.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.zhiyu.app.data.OcFlatRenderer
import com.zhiyu.app.domain.*

@Composable fun AvatarPreview(identity: OcIdentity, scene: OcScene, source: Bitmap?, modifier: Modifier = Modifier) {
    if (identity.route != "photo_subject") {
        Box(modifier, contentAlignment = Alignment.Center) { Text("请通过导入或生成图片设置恋人外观。") }
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
        contentDescription = "人物局部动画预览"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val identity = identity ?: return
        if (width <= 0 || height <= 0 || identity.route == "photo_subject" && (source == null || source?.isRecycled == true)) return
        OcFlatRenderer.draw(canvas, identity, scene, width, height, source)
    }
}
