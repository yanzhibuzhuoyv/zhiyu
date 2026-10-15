package com.zhiyu.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhiyu.app.domain.OcFraming
import com.zhiyu.app.ui.OcExpansionController
import com.zhiyu.app.ui.rememberPreviewPresentation

@Composable fun OcExpansionDialog(controller:OcExpansionController,close:()->Unit) {
    val policy=controller.view?.policy
    var framing by remember(policy){mutableStateOf(policy?.framing?:OcFraming.ORIGINAL)}
    var allow by remember(policy){mutableStateOf(policy?.allowExpansion?:true)}
    val partial=framing==OcFraming.HEAD||framing==OcFraming.BUST
    val enabled=!controller.busy&&!controller.generationConfirmation
    AlertDialog(onDismissRequest={if(!controller.busy)close()},title={Text("OC 取景与补全")},text={
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("原图和通话形象会保留。若想让半身或头像出现在全身生活照中，可以先补全，再由你确认。")
            Text("当前原图拍到了哪里？",style=MaterialTheme.typography.labelLarge)
            listOf(OcFraming.ORIGINAL to "保留原取景",OcFraming.HEAD to "头像",OcFraming.BUST to "半身",OcFraming.FULL to "已有全身").forEach {(value,label)->
                Row(Modifier.fillMaxWidth()) {
                    RadioButton(framing==value,{framing=value},enabled=enabled,modifier=Modifier.testTag("oc_crop_${value.name}"))
                    TextButton({framing=value},enabled=enabled){Text(label)}
                }
            }
            if(partial)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("允许准备全身补全图",Modifier.weight(1f))
                Switch(allow,{allow=it},enabled=enabled,modifier=Modifier.testTag("oc_allow_expansion"))
            }
            if(!partial||!allow)Text("之后的生活照会遵守确认的原取景；已有全身原图可继续使用全身。",style=MaterialTheme.typography.bodySmall)
            OutlinedButton({controller.choose(framing,partial&&allow)},enabled=enabled&&controller.view!=null,
                modifier=Modifier.testTag("oc_crop_save")){Text("保存取景选择")}
            if(policy?.accepted!=null)Text("已采用一张补全参考，可用于后续生活照片。")
            if(controller.view?.configured==false)Text("请先在照片生成服务中设置图像 Key。",style=MaterialTheme.typography.bodySmall)
            Text("生成使用图像 Key；提示词和重新审图使用聊天 Key，均可能产生服务费用。",style=MaterialTheme.typography.bodySmall)
            if(controller.view?.canGenerate==true&&controller.view?.candidate==null)FilledTonalButton(controller::requestGeneration,
                enabled=enabled,modifier=Modifier.testTag("oc_expand_generate")){Text("准备补全候选")}
            controller.view?.candidate?.let {candidate->
                Text(expansionStatus(candidate.status),style=MaterialTheme.typography.bodyMedium)
                if(candidate.imageSha!=null)TextButton(controller::showPreview,enabled=enabled,
                    modifier=Modifier.testTag("oc_expand_preview")){Text("查看补全图")}
                if(candidate.canReview)TextButton(controller::recheck,enabled=enabled,
                    modifier=Modifier.testTag("oc_expand_review")){Text("重新审图")}
                controller.preview?.let {image->Image(rememberPreviewPresentation(image),"待确认的补全参考",Modifier.fillMaxWidth().height(200.dp),contentScale=ContentScale.Fit)}
                if(candidate.canConfirm) {
                    Text("请确认脸、衣饰、动作、翅膀和道具。看过图片后才能采用；自动审图仍可能出错。",style=MaterialTheme.typography.bodySmall)
                    FilledTonalButton(controller::accept,enabled=enabled&&controller.canAccept,
                        modifier=Modifier.testTag("oc_expand_accept")){Text("采用这张完整参考")}
                }
            }
            if(controller.busy)Text("正在准备…",style=MaterialTheme.typography.bodySmall)
            controller.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            TextButton(controller::reload,enabled=enabled,modifier=Modifier.testTag("oc_expand_reload")){Text("刷新")}
        }
    },confirmButton={TextButton(close,enabled=!controller.busy){Text("关闭")}})
    if(controller.generationConfirmation)AlertDialog(onDismissRequest=controller::cancelGeneration,
        title={Text("准备全身补全候选？")},text={Text("将使用照片生成服务和聊天 Key，可能产生费用。原图不会被覆盖，补全图需要审核并由你查看确认后才能用于生活照。")},
        confirmButton={TextButton(controller::confirmGeneration,modifier=Modifier.testTag("oc_expand_confirm_fee")){Text("确认准备")}},
        dismissButton={TextButton(controller::cancelGeneration){Text("取消")}})
}

private fun expansionStatus(value:String)=when(value.lowercase()) {
    "approved","review_approved"->"补全图已通过自动复核，等待你确认"
    "rejected","review_rejected"->"这张补全还需要调整，暂不采用"
    "unknown","review_unknown"->"上次结果尚未确认，不会自动重复生成"
    "unavailable","review_unavailable"->"图片已保留，暂时无法完成审图"
    "failed"->"这次补全未完成，原图和原形象仍保留"
    "stale"->"原图或取景已经更新，请刷新后核对"
    "running","reserved"->"正在准备补全候选"
    "accepted"->"这张补全参考已采用"
    else->"候选图片已保留，请查看后确认"
}
