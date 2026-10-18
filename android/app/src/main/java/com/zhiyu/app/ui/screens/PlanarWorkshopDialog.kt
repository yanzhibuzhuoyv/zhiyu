package com.zhiyu.app.ui.screens

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhiyu.app.domain.planar.*
import com.zhiyu.app.ui.PlanarWorkshopController
import com.zhiyu.app.data.planar.PlanarHeadMotionSuggestion
import java.util.UUID

internal class PlanarDocumentPicker:ActivityResultContracts.OpenDocument() {
    override fun createIntent(context:Context,input:Array<String>):Intent =
        super.createIntent(context,input).addCategory(Intent.CATEGORY_OPENABLE)
}

@Composable fun PlanarWorkshopDialog(controller:PlanarWorkshopController,name:String,close:()->Unit) {
    var selected by remember {mutableStateOf("eyeLeft")}
    var tool by remember {mutableStateOf("polygon")}
    var radius by remember {mutableStateOf(24f)}
    var original by remember {mutableStateOf(false)}
    var animated by remember {mutableStateOf(false)}
    var gesture by remember {mutableStateOf(false)}
    var pickerBinding by remember {mutableStateOf<Long?>(null)}
    var exportBinding by remember {mutableStateOf<Long?>(null)}
    var discardConfirmation by remember {mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(PlanarDocumentPicker()){uri->
        val token=pickerBinding;pickerBinding=null;if(uri!=null&&token!=null)controller.import(uri,token)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->
        val token=exportBinding;exportBinding=null;if(uri!=null&&token!=null)controller.export(uri,token)}
    val draft=controller.draft;val photo=controller.source
    val enabled=!controller.busy&&!original&&!animated&&!gesture
    val maxRadius=minOf(160f,maxOf(photo?.width?:160,photo?.height?:160).toFloat())
    val effectiveRadius=radius.coerceIn(.5f,maxRadius)
    fun partChange(change:(PlanarPart)->PlanarPart){val d=draft?:return;controller.edit(d.copy(parts=d.parts.map {if(it.id==selected)change(it)else it}))}
    Dialog(onDismissRequest={if(!controller.busy&&!gesture)close()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("动态形象",style=MaterialTheme.typography.titleLarge)
                    TextButton(close,enabled=!controller.busy&&!gesture){Text("关闭")}
                }
                if(photo!=null&&draft!=null) {
                    if(animated&&controller.previewDocument!=null)PlanarPreview(controller.previewDocument!!,0f,true,Modifier.fillMaxWidth().height(260.dp))
                    else PlanarEditorCanvas(photo,draft,selected,tool,effectiveRadius.toDouble(),original,!controller.busy,
                        Modifier.fillMaxWidth().height(260.dp),{gesture=it},controller::edit)
                } else Text(if(controller.busy)"正在准备形象…" else "请先在恋人外观中导入或生成图片。",Modifier.padding(16.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    TextButton(controller::undo,enabled=enabled&&controller.canUndo,modifier=Modifier.testTag("planar_undo")){Text("撤销")}
                    TextButton(controller::redo,enabled=enabled&&controller.canRedo,modifier=Modifier.testTag("planar_redo")){Text("重做")}
                    TextButton({original=!original;animated=false},enabled=!controller.busy&&!gesture&&photo!=null){Text(if(original)"返回编辑"else "原图对照")}
                    TextButton({if(!animated)controller.buildPreview();animated=!animated;original=false},enabled=!controller.busy&&!gesture&&photo!=null&&draft!=null,modifier=Modifier.testTag("planar_preview")){Text(if(animated)"返回标注"else "动画预览")}
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("背景保持原样。建议位置供你确认：框选可见的眼口，点击定位头颈；侧脸、遮脸可关闭看不到的部位。",style=MaterialTheme.typography.bodySmall)
                    if(animated)Text("预览使用当前标注；保存后通话才会使用。",style=MaterialTheme.typography.bodySmall)
                    if(draft!=null) {
                        if(PlanarHeadMotionSuggestion.available(draft)) {
                            Text("当前还没有头部活动区域；仅框选眼口，不会产生头部微动。",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton({
                                controller.addHeadMotion()
                                controller.draft?.parts?.firstOrNull {it.id==PlanarHeadMotionSuggestion.ID}?.let {selected=it.id;tool="polygon"}
                            },enabled=enabled,modifier=Modifier.testTag("planar_add_head_motion")){Text("补充头部微动")}
                        }
                        if(draft.subjectBrush.isNotEmpty())Text("人物活动范围的擦除仍会限制眼口和头部；若动作被擦掉，用“画保留”恢复相应位置。",style=MaterialTheme.typography.bodySmall)
                        Row {listOf("eyeLeft" to "左眼","eyeRight" to "右眼","mouth" to "嘴巴").forEach {(id,label)->
                            TextButton({selected=id},enabled=enabled){Text(label+(if(selected==id)" ✓"else ""))}}}
                        Row {listOf("head" to "头部","neck" to "颈部").forEach {(id,label)->TextButton({selected=id},enabled=enabled){Text(label)}}
                            TextButton({selected="subject";tool="erase"},enabled=enabled){Text("人物活动范围")}}
                        val v=draft.featureVisibility
                        Row {Checkbox(v.eyeLeft,{controller.edit(draft.copy(featureVisibility=v.copy(eyeLeft=it)))},enabled=enabled);Text("左眼动作")}
                        Row {Checkbox(v.eyeRight,{controller.edit(draft.copy(featureVisibility=v.copy(eyeRight=it)))},enabled=enabled);Text("右眼动作")}
                        Row {Checkbox(v.mouth,{controller.edit(draft.copy(featureVisibility=v.copy(mouth=it)))},enabled=enabled);Text("口型")}
                        Row {
                            OutlinedButton({val id="part_"+UUID.randomUUID().toString().replace("-","").take(16)
                                val p=PlanarPart(id,"附件 ${draft.parts.size+1}","accessory",20.0+draft.parts.size,emptyList(),
                                    PlanarPoint(photo!!.width/2.0,photo.height/2.0),.5)
                                controller.edit(draft.copy(parts=draft.parts+p));selected=id;tool="polygon"
                            },enabled=enabled&&photo!=null&&draft.parts.size<60,modifier=Modifier.testTag("planar_add_part")){Text("添加头发／附件")}
                        }
                        draft.parts.forEach {p->TextButton({selected=p.id},enabled=enabled){Text(p.name+(if(selected==p.id)" ✓"else ""))}}
                        val p=draft.parts.firstOrNull {it.id==selected}
                        if(p!=null) {
                            OutlinedTextField(p.name,{value->if(value.codePointCount(0,value.length)<=80)partChange {it.copy(name=value)}},label={Text("图层名称")},enabled=enabled,modifier=Modifier.fillMaxWidth())
                            Row {TextButton({partChange {it.copy(role="hair")}},enabled=enabled){Text("头发")}
                                TextButton({partChange {it.copy(role="accessory")}},enabled=enabled){Text("翼／武器／道具")}}
                            Row {TextButton({partChange {it.copy(role="face")}},enabled=enabled){Text("人物头部")}
                                TextButton({partChange {it.copy(role="body")}},enabled=enabled){Text("人物身体")}}
                            Row {TextButton({partChange {it.copy(z=(it.z-1).coerceAtLeast(-1000.0))}},enabled=enabled&&p.z>-1000){Text("移后一层")}
                                TextButton({partChange {it.copy(z=(it.z+1).coerceAtMost(90.0))}},enabled=enabled&&p.z<90){Text("移前一层")}}
                            Text("随动幅度",style=MaterialTheme.typography.bodySmall)
                            Slider(p.strength.toFloat(),{v->partChange {it.copy(strength=v.toDouble())}},enabled=enabled,valueRange=0f..1f)
                            TextButton({controller.edit(draft.copy(parts=draft.parts.filter {it.id!=selected}));selected="eyeLeft"},enabled=enabled){Text("删除此层（可撤销）")}
                        }
                        if(p!=null||selected=="subject") {
                            Row {if(p!=null)TextButton({
                                if(p.baseMaskPng!=null||p.polygon.isNotEmpty())partChange {it.copy(baseMaskPng=null,polygon=emptyList(),brush=emptyList())}
                                tool="polygon"
                            },enabled=enabled){Text(if(p.baseMaskPng!=null||p.polygon.isNotEmpty())"重画轮廓（可撤销）"else "点选轮廓")}
                                TextButton({tool="keep"},enabled=enabled){Text("画保留")};TextButton({tool="erase"},enabled=enabled){Text("擦除")}}
                            if(p!=null)TextButton({if(p.polygon.size>=3)partChange {it.copy(pivot=PlanarPoint(it.polygon.map {q->q.x}.average(),it.polygon.map {q->q.y}.average()))}},enabled=enabled&&p.polygon.size>=3){Text("完成轮廓")}
                            Text("画笔半径：${effectiveRadius.toInt()} 像素",style=MaterialTheme.typography.bodySmall)
                            Slider(effectiveRadius,{radius=it},enabled=enabled,valueRange=.5f..maxRadius,modifier=Modifier.testTag("planar_brush_radius"))
                        }
                    }
                    controller.error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("planar_error"))}
                    controller.notice?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                    Text("关闭会暂存当前标注；下次打开可继续。撤销记录仅保留在本次编辑中。",style=MaterialTheme.typography.bodySmall)
                    TextButton({discardConfirmation=true},enabled=!controller.busy&&!gesture&&controller.dirty,
                        modifier=Modifier.testTag("planar_discard")){Text("放弃未保存的修改")}
                    OutlinedButton({pickerBinding=controller.bindingRevision;picker.launch(arrayOf("application/zip","application/octet-stream"))},enabled=!controller.busy&&!gesture){Text("打开角色包")}
                    Text("角色包用于通话动画；日常照片使用恋人外观中确认的图片。",style=MaterialTheme.typography.bodySmall)
                    TextButton({exportBinding=controller.bindingRevision;export.launch("${name.take(40)}.zyrig.zip")},enabled=!controller.busy&&!controller.dirty&&controller.active!=null&&!gesture){Text("导出角色包")}
                }
                Button({controller.save(name)},enabled=!controller.busy&&!gesture&&draft!=null&&photo!=null&&!original,
                    modifier=Modifier.fillMaxWidth().testTag("planar_save")){Text(if(controller.busy)"正在准备…" else "保存并用于通话")}
            }
        }
    }
    if(discardConfirmation)AlertDialog(onDismissRequest={discardConfirmation=false},title={Text("放弃这些修改？")},
        text={Text("仅删除未保存的标注草稿，已保存的动态形象、恋人原图和背景都会保留。")},
        confirmButton={TextButton({discardConfirmation=false;controller.discardDraft()},modifier=Modifier.testTag("planar_discard_confirm")){Text("放弃修改")}},
        dismissButton={TextButton({discardConfirmation=false}){Text("继续编辑")}})
}
