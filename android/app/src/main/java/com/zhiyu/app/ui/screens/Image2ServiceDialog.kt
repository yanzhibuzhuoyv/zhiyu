package com.zhiyu.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zhiyu.app.data.LocalDailyScheduler
import com.zhiyu.app.data.RemoteImageCredentialStore
import com.zhiyu.app.domain.Image2Protocol
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

@Composable fun Image2ServiceDialog(onSaved:()->Unit,onDismiss:()->Unit) {
    val app=LocalContext.current.applicationContext
    val credentials=remember(app){RemoteImageCredentialStore(app)}
    val scope=rememberCoroutineScope()
    var key by remember {mutableStateOf("")}
    var model by remember {mutableStateOf(Image2Protocol.DEFAULT_MODEL)}
    var configured by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var ready by remember {mutableStateOf(false)}
    LaunchedEffect(credentials) {
        try {val value=withContext(Dispatchers.IO){credentials.configuration()};configured=value!=null;model=value?.model?:model}
        catch(failure:Exception){if(failure is CancellationException)throw failure;error="图像服务 Key 无法读取，请重新保存"}
        finally{ready=true}
    }
    val picker=rememberLauncherForActivityResult(PlanarDocumentPicker()){uri->
        if(uri!=null&&!busy) {
            busy=true;error=null
            scope.launch {
                try {
                    val value=withContext(Dispatchers.IO){
                        val bytes=app.contentResolver.openInputStream(uri)?.use {input->
                            val out=ByteArrayOutputStream();val buffer=ByteArray(4097)
                            while(true) {val count=input.read(buffer);if(count<0)break
                                require(count>0&&out.size()+count<=4096);out.write(buffer,0,count)}
                            out.toByteArray()
                        }?:error("Key 文件无法读取")
                        bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim().also(Image2Protocol::checkKey)
                    }
                    key=value
                }catch(failure:Exception){if(failure is CancellationException)throw failure;error="文件应只包含图像服务 Key，请重新选择"}
                finally{busy=false}
            }
        }
    }
    fun save(clear:Boolean=false) {
        if(busy||!ready)return
        busy=true;error=null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if(clear)credentials.clear()
                    else credentials.write(key.trim().ifBlank {credentials.configuration()?.key?:error("请填写 Key")},model)
                }
                key="";onSaved();LocalDailyScheduler.refreshBoundPlan(app);onDismiss()
            }catch(failure:Exception){if(failure is CancellationException)throw failure;error="图像服务没有保存，请检查 Key 后重试"}
            finally{busy=false}
        }
    }
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("照片生成服务")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("用 image 2.5 提前准备恋人的生活照片，无需安装大模型。固定参考图随请求发送，保持人物和原画风；生成及审核仍可能出现偏差。")
            Text("图像 Key 与聊天 Key 分开，在手机加密保存。生图由 cn-api.ooapi.cc 计费；生活照描述和审图使用你的聊天 Key。")
            if(configured)Text("已保存 Key；留空保留，仅修改生图型号。",Modifier.testTag("image2_configured"))
            OutlinedTextField(key,{key=it},enabled=!busy&&ready,singleLine=true,label={Text("图像服务 API Key")},
                visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().testTag("image2_key"))
            TextButton({picker.launch(arrayOf("text/plain","application/octet-stream"))},enabled=!busy&&ready){Text("从 Key 文本文件导入")}
            Image2Protocol.MODELS.forEach {id->Row {
                RadioButton(model==id,{model=id},enabled=!busy&&ready)
                TextButton({model=id},enabled=!busy&&ready){Text(id)}
            }}
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton({save()},enabled=!busy&&ready&&(key.isNotBlank()||configured)){Text("保存")}},
        dismissButton={Row {
            if(configured)TextButton({save(true)},enabled=!busy){Text("移除 Key")}
            TextButton(onDismiss,enabled=!busy){Text("关闭")}
        }})
}
