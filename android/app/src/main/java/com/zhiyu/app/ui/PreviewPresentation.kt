package com.zhiyu.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Display lists may outlive composition. Their snapshot must never be manually recycled by a controller. */
@Composable internal fun rememberPreviewPresentation(source:Bitmap,maxSide:Int=512):ImageBitmap = remember(source,maxSide) {
    require(maxSide in 1..1536&&!source.isRecycled&&source.width in 1..maxSide&&source.height in 1..maxSide)
    checkNotNull(source.copy(Bitmap.Config.ARGB_8888,false)){"预览暂时无法显示"}.asImageBitmap()
    // The view system/GC owns this bounded immutable copy. Do not recycle it in onDispose:
    // a hardware display list may still reference it after the composable is detached.
}
