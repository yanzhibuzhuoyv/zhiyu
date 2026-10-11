package com.zhiyu.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhiyu.app.domain.*
import com.zhiyu.app.ui.OcCreatorController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.sin

@Composable fun OcCreatorPanel(controller: OcCreatorController, enabled: Boolean = true) {
    var route by remember { mutableStateOf<String?>(null) }
    Text("恋人的形象", style = MaterialTheme.typography.titleSmall)
    Text("真人保留确认后的照片；非真人可以亲手制作二维或三维形象。外观修改不会改变恋人的性格。", style = MaterialTheme.typography.bodySmall)
    val identity = controller.identity
    OcChoices(identity?.route.orEmpty(), listOf("photo_subject" to "真人照片", "parts_2d" to "非真人 2D", "avatar_3d" to "非真人 3D"),
        enabled && !controller.busy && identity != null) { route = it }
    if (identity?.confirmed == true) Text("当前：${routeLabel(identity.route)}", style = MaterialTheme.typography.labelMedium)
    controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    route?.let { selected ->
        val initial = remember(selected, identity?.revision) { controller.draft(selected) }
        OcCreatorDialog(initial, controller.busy, controller.error, controller::source,
            save = { controller.save(it) { route = null } }, close = { route = null })
    }
}

/** Editor is independently testable; no API/provider/secret is needed to create and render an OC. */
@Composable fun OcCreatorDialog(initial: OcIdentity, busy: Boolean, error: String?,
    loadSource: suspend (OcIdentity) -> Bitmap?, save: (OcIdentity) -> Unit, close: () -> Unit) {
    var draft by remember(initial.ocId, initial.revision, initial.route) { mutableStateOf(initial) }
    var page by remember { mutableStateOf(if (initial.route == "photo_subject") "rig" else "body") }
    var source by remember(initial.photoSource) { mutableStateOf<Bitmap?>(null) }
    var loadError by remember(initial.photoSource) { mutableStateOf<String?>(null) }
    var animate by remember { mutableStateOf(false) }
    var motion by remember { mutableStateOf(OcMotion()) }
    var cameraYaw by remember { mutableFloatStateOf(0f) }
    var cameraPitch by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(initial.photoSource, initial.route) {
        try { source = loadSource(initial) }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; loadError = failure.message ?: "角色图片暂时无法读取" }
    }
    DisposableEffect(source) { val captured = source; onDispose { captured?.recycle() } }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var t = 0f
        try { while (true) {
            motion = OcMotion(headTurn = sin(t * .65f) * .6f, breath = sin(t * 1.8f),
                blink = if (t % 4f < .18f) 1f - (t % 4f) / .18f else 0f, accessorySway = sin(t) * .45f)
            delay(50); t += .05f
        } } finally { motion = OcMotion() }
    }
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(routeLabel(initial.route), style = MaterialTheme.typography.titleLarge)
                if (initial.route != "photo_subject" || source != null) AvatarPreview(draft,
                    OcScene(cameraYaw = cameraYaw, cameraPitch = cameraPitch, motion = motion), source,
                    Modifier.fillMaxWidth().height(240.dp).testTag("oc_character_preview"))
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (initial.route == "photo_subject") {
                        Text("先在外观页面导入并确认完整人物。这里让头、呼吸、眼嘴和道具局部活动，不重画脸。单张照片没有背面和被遮住的部位。", style = MaterialTheme.typography.bodySmall)
                        OcRigEditor(draft.rig, source, !busy) { draft = draft.copy(rig = it) }
                    } else {
                        OcChoices(page, listOf("body" to "脸与身体", "parts" to "附属物", "motion" to "动作"), !busy) { page = it }
                        when (page) {
                            "body" -> OcBodyControls(draft.body, !busy) { draft = draft.copy(body = it) }
                            "parts" -> OcPartEditor(draft.parts, !busy, initial.route == "avatar_3d") { draft = draft.copy(parts = it) }
                        }
                    }
                    if (page == "motion" || initial.route == "photo_subject") {
                        Row { Checkbox(animate, { animate = it }, enabled = !busy); Text("预览呼吸、眨眼与轻微动作") }
                        if (!animate) {
                            OcSlider("头部转动", motion.headTurn, -1f..1f, !busy) { motion = motion.copy(headTurn = it) }
                            OcSlider("闭眼", motion.blink, 0f..1f, !busy) { motion = motion.copy(blink = it) }
                            OcSlider("张嘴", motion.mouthOpen, 0f..1f, !busy) { motion = motion.copy(mouthOpen = it) }
                            OcSlider("呼吸", motion.breath, -1f..1f, !busy) { motion = motion.copy(breath = it) }
                            OcSlider("附属物摆动", motion.accessorySway, -1f..1f, !busy) { motion = motion.copy(accessorySway = it) }
                        }
                    }
                    if (initial.route == "avatar_3d") {
                        OcSlider("绕人物查看", cameraYaw, -180f..180f, !busy, "oc_camera_yaw") { cameraYaw = it }
                        OcSlider("上下视角", cameraPitch, -60f..60f, !busy) { cameraPitch = it }
                    }
                    (loadError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(close, enabled = !busy) { Text("取消") }
                    Button({ save(OcIdentityRules.check(draft)) }, enabled = !busy && loadError == null &&
                        (initial.route != "photo_subject" || source != null), modifier = Modifier.testTag("oc_save_identity")) { Text(if (busy) "保存中…" else "确认这个形象") }
                }
            }
        }
    }
}
private fun routeLabel(route: String) = when (route) { "photo_subject" -> "真人照片与局部动画"; "parts_2d" -> "二维角色制作"; "avatar_3d" -> "三维角色制作"; else -> "尚未选择" }
