package com.zhiyu.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import com.zhiyu.app.domain.planar.*
import com.zhiyu.app.ui.rememberPreviewPresentation
import kotlin.math.*

@Composable internal fun PlanarEditorCanvas(source:Bitmap,draft:PlanarDraft,selected:String,tool:String,radius:Double,
    original:Boolean,enabled:Boolean,modifier:Modifier,gesture:(Boolean)->Unit,change:(PlanarDraft)->Unit) {
    var dimensions by remember {mutableStateOf(IntSize.Zero)}
    var trace by remember {mutableStateOf<List<PlanarPoint>>(emptyList())}
    val latest by rememberUpdatedState(draft)
    val onChange by rememberUpdatedState(change)
    val onGesture by rememberUpdatedState(gesture)
    val displayed=rememberPlanarEditorImage(source)
    fun point(p:Offset):PlanarPoint {
        val scale=min(dimensions.width.toDouble()/source.width,dimensions.height.toDouble()/source.height)
        val left=(dimensions.width-source.width*scale)/2;val top=(dimensions.height-source.height*scale)/2
        return PlanarPoint(((p.x-left)/scale).coerceIn(0.0,source.width-1.0),((p.y-top)/scale).coerceIn(0.0,source.height-1.0))
    }
    fun complete(points:List<PlanarPoint>) {
        if(points.isEmpty())return
        val d=latest
        if(selected in listOf("head","neck")) {
            val a=if(selected=="head")d.anchors.copy(head=points.last()) else d.anchors.copy(neck=points.last())
            onChange(d.copy(anchors=a));return
        }
        if(selected in listOf("eyeLeft","eyeRight","mouth")) {
            val p=points.first();val q=points.last();if(abs(p.x-q.x)<1||abs(p.y-q.y)<1)return
            val rect=PlanarRect(min(p.x,q.x),min(p.y,q.y),max(p.x,q.x),max(p.y,q.y))
            val a=when(selected){"eyeLeft"->d.anchors.copy(eyeLeft=rect);"eyeRight"->d.anchors.copy(eyeRight=rect);else->d.anchors.copy(mouth=rect)}
            onChange(d.copy(anchors=a));return
        }
        if(selected=="subject") {
            onChange(d.copy(subjectBrush=d.subjectBrush+PlanarBrushStroke(points,radius,tool=="keep")));return
        }
        onChange(d.copy(parts=d.parts.map { part -> if(part.id!=selected)part
            else if(tool=="polygon") {
                if(part.baseMaskPng!=null)part.copy(baseMaskPng=null,polygon=listOf(points.last()),brush=emptyList())
                else part.copy(polygon=part.polygon+points.last())
            }
            else part.copy(brush=part.brush+PlanarBrushStroke(points,radius,tool=="keep")) }))
    }
    val touch=Modifier.pointerInput(source,selected,tool,radius,enabled,original) {
        if(!enabled||original)return@pointerInput
        if(selected in listOf("head","neck") || (tool=="polygon"&&selected!="subject"&&selected !in listOf("eyeLeft","eyeRight","mouth"))) {
            detectTapGestures {complete(listOf(point(it)))}
        } else detectDragGestures(onDragStart={trace=listOf(point(it));onGesture(true)},
            onDragCancel={trace=emptyList();onGesture(false)},onDragEnd={
                try {complete(trace)} finally {trace=emptyList();onGesture(false)}
            }) {event,_->event.consume();if(trace.size<256)trace=trace+point(event.position)}
    }
    Canvas(modifier.onSizeChanged {dimensions=it}.then(touch).testTag("planar_editor_canvas")) {
        drawRect(Color(0xfff5efdf))
        val scale=min(size.width/source.width,size.height/source.height);val w=source.width*scale;val h=source.height*scale
        val left=(size.width-w)/2;val top=(size.height-h)/2
        drawImage(displayed,dstOffset=IntOffset(left.roundToInt(),top.roundToInt()),dstSize=IntSize(w.roundToInt().coerceAtLeast(1),h.roundToInt().coerceAtLeast(1)))
        if(original)return@Canvas
        fun offset(p:PlanarPoint)=Offset(left+p.x.toFloat()*scale,top+p.y.toFloat()*scale)
        fun box(r:PlanarRect,key:String){drawRect(if(selected==key)Color(0xffb66a65)else Color(0xff7e9275),
            Offset(left+r.left.toFloat()*scale,top+r.top.toFloat()*scale),
            androidx.compose.ui.geometry.Size((r.right-r.left).toFloat()*scale,(r.bottom-r.top).toFloat()*scale),style=Stroke(2f))}
        box(draft.anchors.eyeLeft,"eyeLeft");box(draft.anchors.eyeRight,"eyeRight");box(draft.anchors.mouth,"mouth")
        drawCircle(Color(0xff7e9275),4f,offset(draft.anchors.head));drawCircle(Color(0xff8d7762),4f,offset(draft.anchors.neck))
        val p=draft.parts.firstOrNull {it.id==selected}
        if(p!=null&&p.polygon.isNotEmpty()) {
            val path=Path();val start=offset(p.polygon.first());path.moveTo(start.x,start.y)
            p.polygon.drop(1).forEach {val q=offset(it);path.lineTo(q.x,q.y)};if(p.polygon.size>=3)path.close()
            drawPath(path,Color(0xffae6862),style=Stroke(2f));p.polygon.forEach {drawCircle(Color(0xffae6862),3f,offset(it))}
        }
        val strokes=if(selected=="subject")draft.subjectBrush else p?.brush ?: emptyList()
        strokes.forEach {s->s.points.zipWithNext().forEach {(a,b)->drawLine(if(s.keep)Color(0x667ba26b)else Color(0x77c27771),offset(a),offset(b),
            (s.radius*2*scale).toFloat(),StrokeCap.Round)}}
        trace.zipWithNext().forEach {(a,b)->drawLine(Color(0xffae6862),offset(a),offset(b),3f)}
    }
}

/** Render commands may retain their image after the controller releases the editable source. */
@Composable internal fun rememberPlanarEditorImage(source:Bitmap):ImageBitmap=rememberPreviewPresentation(source,1536)
