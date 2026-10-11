package com.zhiyu.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhiyu.app.data.LocalPortraitProcessor
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private data class RefineImages(val original: Bitmap, val subject: Bitmap)

/** Coordinates always refer to the complete original, including detached wings and props. */
@Composable fun SubjectRefineDialog(preview: PortraitPreview, busy: Boolean, error: String?,
    loadImage: suspend (String, String) -> ByteArray, apply: (List<PortraitStroke>) -> Unit, close: () -> Unit) {
    var images by remember(preview.id, preview.subjectSha256) { mutableStateOf<RefineImages?>(null) }
    var loadError by remember(preview.id, preview.subjectSha256) { mutableStateOf<String?>(null) }
    var strokes by remember(preview.id, preview.subjectSha256) { mutableStateOf<List<PortraitStroke>>(emptyList()) }
    var current by remember { mutableStateOf<PortraitStroke?>(null) }
    var action by remember { mutableStateOf("keep") }
    var radius by remember { mutableFloatStateOf(.025f) }
    var warning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(preview.id, preview.subjectSha256) {
        try {
            val original = loadImage(preview.originalId, preview.originalSha256)
            val subject = loadImage(preview.subjectId, preview.subjectSha256)
            images = withContext(Dispatchers.IO) {
                val decoded = LocalPortraitProcessor.decode(original)
                try { RefineImages(decoded, LocalPortraitProcessor.decode(subject)) }
                catch (failure: Throwable) { decoded.recycle(); throw failure }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            loadError = failure.message ?: "图片读取失败，请重新打开修整"
        }
    }
    DisposableEffect(images) { val captured = images; onDispose { captured?.original?.recycle(); captured?.subject?.recycle() } }
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("修整角色轮廓", style = MaterialTheme.typography.titleLarge)
                Text("绿色保留，红色擦除。翅膀、头发、武器都可以分别选，不限制数量。淡色底图只是对照。", style = MaterialTheme.typography.bodySmall)
                images?.let { pictures ->
                    BrushCanvas(preview, pictures, strokes, current, action, radius, busy,
                        start = { point ->
                            if (strokes.size >= PortraitRefinementRules.MAX_STROKES) warning = "已绘制 128 笔，请先应用或撤销"
                            else current = PortraitStroke(action, listOf(point), radius)
                        }, drag = { point ->
                            current?.let { active ->
                                if (active.points.size < PortraitRefinementRules.MAX_POINTS) current = active.copy(points = active.points + point)
                                else warning = "这笔较长，请抬手后分几笔继续画"
                            }
                        }, finish = {
                            current?.let { strokes = strokes + it }
                            current = null
                        }, cancel = { current = null })
                } ?: run { if (loadError == null) LinearProgressIndicator(Modifier.fillMaxWidth()); Unit }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(action == "keep", { action = "keep" }, label = { Text("保留") }, enabled = !busy)
                    FilterChip(action == "remove", { action = "remove" }, label = { Text("擦除") }, enabled = !busy)
                    TextButton({ strokes = strokes.dropLast(1); warning = null }, enabled = !busy && strokes.isNotEmpty()) { Text("撤销") }
                    TextButton({ strokes = emptyList(); current = null; warning = null }, enabled = !busy && strokes.isNotEmpty()) { Text("重画") }
                }
                Text("笔刷大小", style = MaterialTheme.typography.labelSmall)
                Slider(radius, { radius = it }, valueRange = .002f.. .15f, enabled = !busy)
                (loadError ?: error ?: warning)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(close, enabled = !busy) { Text("取消") }
                    Button({ apply(PortraitRefinementRules.check(strokes)) }, enabled = !busy && images != null && strokes.isNotEmpty() && current == null) { Text("应用到预览") }
                }
            }
        }
    }
}

@Composable private fun BrushCanvas(preview: PortraitPreview, images: RefineImages, strokes: List<PortraitStroke>,
    current: PortraitStroke?, action: String, radius: Float, busy: Boolean,
    start: (PortraitPoint) -> Unit, drag: (PortraitPoint) -> Unit, finish: () -> Unit, cancel: () -> Unit) {
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    val originalImage = remember(images.original) { images.original.asImageBitmap() }
    val subjectImage = remember(images.subject) { images.subject.asImageBitmap() }
    Canvas(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 340.dp).height(300.dp)
        .onSizeChanged { dimensions = it }
        .pointerInput(preview.id, preview.subjectSha256, dimensions, action, radius, busy) {
            if (busy) return@pointerInput
            fun frame() = fitFrame(Size(dimensions.width.toFloat(), dimensions.height.toFloat()), preview)
            fun point(offset: Offset): PortraitPoint {
                val rect = frame()
                return PortraitPoint(((offset.x - rect.left) / rect.width).coerceIn(0f, 1f),
                    ((offset.y - rect.top) / rect.height).coerceIn(0f, 1f))
            }
            detectDragGestures(onDragStart = { if (frame().contains(it)) start(point(it)) },
                onDrag = { change, _ -> change.consume(); drag(point(change.position)) },
                onDragEnd = finish, onDragCancel = cancel)
        }.pointerInput(preview.id, preview.subjectSha256, dimensions, action, radius, busy) {
            if (busy) return@pointerInput
            detectTapGestures { offset ->
                val rect = fitFrame(Size(dimensions.width.toFloat(), dimensions.height.toFloat()), preview)
                if (rect.contains(offset)) {
                    start(PortraitPoint(((offset.x - rect.left) / rect.width).coerceIn(0f, 1f),
                        ((offset.y - rect.top) / rect.height).coerceIn(0f, 1f)))
                    finish()
                }
            }
        }) {
        drawRect(Color(0xffdddddd))
        val frame = fitFrame(size, preview)
        val imageSize = IntSize(frame.width.roundToInt().coerceAtLeast(1), frame.height.roundToInt().coerceAtLeast(1))
        drawImage(originalImage, dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()), dstSize = imageSize, alpha = .25f)
        val (left, top, right, bottom) = preview.bounds
        drawImage(subjectImage,
            dstOffset = IntOffset((frame.left + frame.width * left / preview.originalWidth).roundToInt(),
                (frame.top + frame.height * top / preview.originalHeight).roundToInt()),
            dstSize = IntSize((frame.width * (right-left) / preview.originalWidth).roundToInt().coerceAtLeast(1),
                (frame.height * (bottom-top) / preview.originalHeight).roundToInt().coerceAtLeast(1)))
        (strokes + listOfNotNull(current)).forEach { stroke ->
            val color = if (stroke.action == "keep") Color(0xff15ba63).copy(alpha = .55f) else Color(0xffe54848).copy(alpha = .55f)
            val brush = stroke.radius * min(frame.width, frame.height)
            val points = stroke.points.map { Offset(frame.left + it.x * frame.width, frame.top + it.y * frame.height) }
            for (index in 1 until points.size) drawLine(color, points[index-1], points[index], brush*2, StrokeCap.Round)
            points.forEach { drawCircle(color, brush, it) }
        }
    }
}

private fun fitFrame(size: Size, preview: PortraitPreview): Rect {
    val scale = min(size.width / preview.originalWidth, size.height / preview.originalHeight)
    val width = preview.originalWidth * scale; val height = preview.originalHeight * scale
    val left = (size.width - width) / 2; val top = (size.height - height) / 2
    return Rect(left, top, left + width, top + height)
}
