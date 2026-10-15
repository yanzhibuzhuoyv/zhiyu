package com.zhiyu.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zhiyu.app.data.remotescene.ScenePhotoRow
import com.zhiyu.app.ui.ScenePhotoAction
import com.zhiyu.app.ui.ScenePhotoConfirmation
import com.zhiyu.app.ui.ScenePhotoController
import com.zhiyu.app.ui.scenePhotoStatus
import java.time.LocalDate

@Composable fun ScenePhotoDialog(controller: ScenePhotoController, close: () -> Unit) {
    DisposableEffect(controller) { onDispose { controller.closePreview() } }
    LaunchedEffect(controller) { controller.reload() }
    Dialog(onDismissRequest = { if (!controller.busy) close() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().heightIn(max = 700.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("候选照片", style = MaterialTheme.typography.titleLarge)
                    TextButton(close, enabled = !controller.busy) { Text("关闭") }
                }
                Text("查看提前准备的生活照片。视觉复核可能出错；结果未确认的生成请求不会自动重试。", style = MaterialTheme.typography.bodySmall)
                Text("重新审图使用聊天 Key，可能产生费用；不会重新生图。",style=MaterialTheme.typography.bodySmall)
                if (controller.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("scene_photo_error")) }
                val bitmap = controller.preview
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), "所选候选照片", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(180.dp).testTag("scene_photo_image"))
                    TextButton(controller::closePreview, enabled = !controller.busy) { Text("收起预览") }
                }
                if (controller.items.isEmpty() && !controller.busy) Text("还没有候选照片。设置照片生成服务后，生活照片会逐步准备。")
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(controller.items, key = { it.requestId }) { row -> ScenePhotoItem(row, controller) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(controller::previousPage, enabled = !controller.busy && controller.canGoBack && controller.confirmation == null,
                        modifier = Modifier.testTag("scene_photo_previous")) { Text("上一页") }
                    Text("第 ${controller.pageNumber} 页", style = MaterialTheme.typography.bodySmall)
                    TextButton(controller::nextPage, enabled = !controller.busy && controller.nextCursor != null && controller.confirmation == null,
                        modifier = Modifier.testTag("scene_photo_next")) { Text("下一页") }
                }
                TextButton(controller::reload, enabled = !controller.busy && controller.confirmation == null,
                    modifier = Modifier.testTag("scene_photo_refresh")) { Text("刷新") }
            }
        }
    }
    controller.confirmation?.let { ScenePhotoConfirm(it, controller) }
}

@Composable private fun ScenePhotoItem(row: ScenePhotoRow, controller: ScenePhotoController) {
    val enabled = !controller.busy && controller.confirmation == null
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(scenePhotoTime(row), style = MaterialTheme.typography.labelLarge)
            Text(scenePhotoStatus(row.status), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.hasPicture) TextButton({ controller.showPreview(row.requestId) }, enabled = enabled,
                    modifier = Modifier.testTag("scene_photo_preview_${row.requestId}")) { Text("看照片") }
                if (row.canReview && row.hasPicture) TextButton({ controller.recheck(row.requestId) }, enabled = enabled,
                    modifier = Modifier.testTag("scene_photo_review_${row.requestId}")) { Text("重新审图") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.canRetry && row.status == "failed") TextButton({ controller.requestRetry(row.requestId) }, enabled = enabled,
                    modifier = Modifier.testTag("scene_photo_retry_${row.requestId}")) { Text("重新生成") }
                if (row.canClear) TextButton({ controller.requestClear(row.requestId) }, enabled = enabled,
                    modifier = Modifier.testTag("scene_photo_clear_${row.requestId}")) { Text("清理候选") }
            }
        }
    }
}

@Composable private fun ScenePhotoConfirm(value: ScenePhotoConfirmation, controller: ScenePhotoController) {
    val retry = value.action == ScenePhotoAction.RETRY_FAILED
    AlertDialog(onDismissRequest = controller::cancelConfirmation,
        title = { Text(if (retry) "重新生成这张照片？" else "清理候选图片？") },
        text = { Text(if (retry)
            "这会重新调用生图服务并产生费用。仅适用于已明确失败的请求；结果未确认的请求不会重新生成。"
        else "只清理这张候选的本机像素。恋人原图和已发送的照片会保留；生成记录仍会保留，避免重复扣费。") },
        confirmButton = { TextButton({ controller.confirm(value) }, enabled = !controller.busy,
            modifier = Modifier.testTag("scene_photo_confirm")) { Text(if (retry) "确认重新生成" else "确认清理") } },
        dismissButton = { TextButton(controller::cancelConfirmation,
            modifier = Modifier.testTag("scene_photo_confirm_cancel")) { Text("取消") } })
}

private fun scenePhotoTime(row: ScenePhotoRow): String {
    val day = try { LocalDate.parse(row.day).takeIf { it.toString() == row.day }?.let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" } }
        catch (_: Exception) { null }
    val time = row.slot?.takeIf { Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(it) }
    return "${day ?: "日期待确认"} · ${time ?: "时间待确认"}"
}
