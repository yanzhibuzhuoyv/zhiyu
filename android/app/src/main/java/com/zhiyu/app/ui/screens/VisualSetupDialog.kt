package com.zhiyu.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhiyu.app.ui.LocalMediaController
import com.zhiyu.app.ui.MediaController
import com.zhiyu.app.ui.OcCreatorController
import com.zhiyu.app.domain.VisualProfile

private data class VisualDraft(val description: String, val gender: String, val hair: String)

@Composable fun VisualSetupDialog(controller: MediaController, ocCreator: OcCreatorController? = null, close: () -> Unit) {
    val profile = controller.profile
    var description by remember(profile?.description) { mutableStateOf(controller.pendingDescription ?: profile?.description.orEmpty()) }
    LaunchedEffect(controller.pendingDescription) { controller.pendingDescription?.let { description = it } }
    var gender by remember(profile?.gender) { mutableStateOf(profile?.gender ?: "unspecified") }
    var hair by remember(profile?.hair) { mutableStateOf(profile?.hair ?: "unspecified") }
    var sampleId by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { controller.importReference(it, false) }
    }
    CompositionLocalProvider(LocalMediaController provides controller) {
        AlertDialog(onDismissRequest = { if (!controller.busy) { controller.acknowledgeSetup(); close() } },
            title = { Text("恋人的 OC 外观") },
            text = {
                VisualBody(controller, VisualDraft(description, gender, hair), ocCreator,
                    change = { description = it.description; gender = it.gender; hair = it.hair },
                    sampleId = sampleId, chooseSample = { sampleId = it },
                    importReference = { picker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif")) })
            },
            confirmButton = { TextButton(onClick = { controller.acknowledgeSetup(); close() }, enabled = !controller.busy) { Text("完成") } },
            dismissButton = { TextButton(onClick = { controller.acknowledgeSetup(); close() }, enabled = !controller.busy) { Text(if (profile?.visualVersion == 0) "暂时不用" else "关闭") } })
    }
}

@Composable private fun VisualBody(controller: MediaController, draft: VisualDraft, ocCreator: OcCreatorController?, change: (VisualDraft) -> Unit,
    sampleId: String?, chooseSample: (String?) -> Unit, importReference: () -> Unit,
) {
    val profile = controller.profile
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("导入后可在聊天菜单的动态形象中调整人物动作。",style=MaterialTheme.typography.bodySmall)
        Text("可以导入你自己的角色参考图，也可以先不设外观。有人物的素材会按这位 OC 匹配。")
        if (controller.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (profile != null && controller.error != null) TextButton(onClick = controller::reload, enabled = !controller.busy) { Text("重新读取外观资料") }
        if (profile == null) {
            TextButton(onClick = controller::reload, enabled = !controller.busy) { Text("读取外观资料") }
        } else {
            profile.referenceId?.let { MediaImage(it) }
            VisualTemplates(controller, sampleId, chooseSample)
            VisualFields(controller, profile, draft, change)
            Text("原图背景会完整保留。动态形象只调整人物动作，不删除场景。",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = importReference, enabled = !controller.busy, modifier = Modifier.testTag("oc_import")) { Text("导入自己的 OC 图片") }
            VisualGeneration(controller, profile, draft.description)
            GeneratedCandidates(controller)
        }
        controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun SubjectPreview(controller: MediaController) {
    val preview = controller.subjectPreview
    if (preview != null) {
        var showOriginal by remember(preview.id) { mutableStateOf(false) }
        var refining by remember(preview.id) { mutableStateOf(false) }
        Text("先看看角色、翅膀和道具有没有保留完整。原图会保留；有多个人物时，可擦除其余人物。")
        if (refining) SubjectRefineDialog(preview, controller.busy, controller.error,
            loadImage = { id, sha -> controller.image(id, sha) },
            apply = { strokes -> controller.refineSubject(strokes) { refining = false } },
            close = { refining = false })
        if (showOriginal) MediaImage(preview.originalId, preview.originalSha256, description = "保留的 OC 原图")
        else MediaImage(preview.subjectId, preview.subjectSha256, description = "待确认的 OC 人物主体")
        TextButton(onClick = { showOriginal = !showOriginal }) { Text(if (showOriginal) "看分离效果" else "对照原图") }
        OutlinedButton(onClick = { refining = true }, enabled = !controller.busy) { Text("修整翅膀、道具与边缘") }
        FilledTonalButton(onClick = { controller.confirmSubject("subject") }, enabled = !controller.busy,
            modifier = Modifier.testTag("oc_subject_confirm")) { Text("使用这个人物") }
        TextButton(onClick = { controller.confirmSubject("original") }, enabled = !controller.busy) { Text("仍使用原图") }
        TextButton(onClick = controller::discardPreview, enabled = !controller.busy) { Text("重新选择图片") }
    } else if (controller.canKeepOriginal && !controller.busy) {
        TextButton(onClick = controller::keepOriginal) { Text("先保留原图") }
    }
}

@Composable private fun VisualTemplates(controller: MediaController, sampleId: String?, choose: (String?) -> Unit) {
    if (controller.templates.isEmpty()) return
    Text("也可以选用虚构 OC 样例", style = MaterialTheme.typography.labelMedium)
    controller.templates.forEach { sample ->
        TextButton(onClick = { choose(if (sampleId == sample.id) null else sample.id) }, enabled = !controller.busy) {
            Text("${sample.name} · ${sample.imageCount} 张素材")
        }
        if (sampleId == sample.id) {
            MediaImage(sample.id, sample.referenceSha256, description = "虚构 OC 样例预览", template = true)
            OutlinedButton(onClick = { controller.useTemplate(sample.id) }, enabled = !controller.busy) { Text("使用这个 OC 样例") }
        }
    }
}

@Composable private fun VisualFields(controller: MediaController, profile: VisualProfile, draft: VisualDraft, change: (VisualDraft) -> Unit) {
    OutlinedTextField(draft.description, { if (it.length <= 1000) change(draft.copy(description = it)) }, label = { Text("外观描述") },
        modifier = Modifier.fillMaxWidth().testTag("oc_description"), minLines = 2, maxLines = 5, enabled = !controller.busy && controller.pendingDescription == null)
    Text("角色性别", style = MaterialTheme.typography.labelMedium)
    Options(draft.gender, listOf("unspecified" to "不指定", "female" to "女", "male" to "男", "other" to "其他"), !controller.busy) { change(draft.copy(gender = it)) }
    Text("头发", style = MaterialTheme.typography.labelMedium)
    Options(draft.hair, listOf("unspecified" to "不指定", "short" to "短发", "long" to "长发", "other" to "其他"), !controller.busy) { change(draft.copy(hair = it)) }
    OutlinedButton(onClick = { controller.save(if (profile.mode == "none") "import" else profile.mode, draft.description, draft.gender, draft.hair) }, enabled = !controller.busy) { Text("保存外观描述") }
}

@Composable private fun VisualGeneration(controller: MediaController, profile: VisualProfile, description: String) {
    val pending = controller.pendingDescription
    Text("描述生成机会：剩余 ${profile.generation.remaining} / ${profile.generation.limit} 次", style = MaterialTheme.typography.bodySmall)
    if (!profile.generation.configured) Text("请在图像服务中设置 Key，生成机会会保留。", style = MaterialTheme.typography.bodySmall)
    FilledTonalButton(onClick = { controller.generate(pending ?: description) },
        enabled = !controller.busy && (pending != null || profile.generation.configured && profile.generation.remaining > 0 && description.isNotBlank()),
        modifier = Modifier.testTag("oc_generate")) { Text(if (pending != null) "查询上次生成结果" else "按描述生成外观") }
}

@Composable private fun GeneratedCandidates(controller: MediaController) {
    if (controller.generatedCandidates.isEmpty()) return
    Text("生成过的外观候选", style = MaterialTheme.typography.titleSmall)
    Text("先看清人物、翅膀和道具，再选择采用。", style = MaterialTheme.typography.bodySmall)
    controller.generatedCandidates.forEach { asset ->
        MediaImage(asset.id, asset.sha256, description = "已生成的 OC 外观候选")
        OutlinedButton(onClick = { controller.useGeneratedCandidate(asset.id) }, enabled = !controller.busy,
            modifier = Modifier.testTag("oc_use_generated_${asset.id}")) { Text("使用这张外观") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Options(selected: String, values: List<Pair<String, String>>, enabled: Boolean, choose: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { (value, label) -> FilterChip(selected == value, { choose(value) }, label = { Text(label) }, enabled = enabled) }
    }
}
