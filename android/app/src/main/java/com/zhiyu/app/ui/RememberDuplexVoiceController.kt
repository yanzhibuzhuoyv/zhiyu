package com.zhiyu.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.zhiyu.app.data.LocalVoicePreferences
import com.zhiyu.app.data.duplex.AndroidDuplexPortsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rig2d.voice.DuplexPortsFactory

@Composable fun rememberDuplexVoiceController():DuplexVoiceController {
    val context=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    val controller=remember(context,scope){DuplexVoiceController(scope,{source,report->DuplexPortsFactory {
        val (voice,tuning)=withContext(Dispatchers.IO){LocalVoicePreferences(context).snapshot()}
        AndroidDuplexPortsFactory(context,voice,tuning,source){report(it.aecEnabled,it.headset)}.open()
    }})}
    DisposableEffect(controller){onDispose{controller.dispose()}}
    return controller
}
