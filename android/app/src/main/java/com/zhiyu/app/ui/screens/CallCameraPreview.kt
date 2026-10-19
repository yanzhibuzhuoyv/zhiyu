package com.zhiyu.app.ui.screens

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhiyu.app.data.callcamera.CallCameraEngineFactory
import com.zhiyu.app.data.callcamera.CameraXCallCameraEngine
import com.zhiyu.app.domain.CallCameraFrame

internal val LocalCallCameraEngineFactory = staticCompositionLocalOf {
    CallCameraEngineFactory { context, owner, view, front, frame, error ->
        CameraXCallCameraEngine(context, owner, view, front, frame, error)
    }
}

internal val LocalCallCameraScreenVisible = staticCompositionLocalOf<(Context) -> Boolean> {
    { context -> context.getSystemService(PowerManager::class.java)?.isInteractive != false &&
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true }
}

/** Compose only after explicit camera permission and while the user has enabled an active call camera. */
@Composable fun CallCameraPreview(front: Boolean, onFrame: (CallCameraFrame) -> Unit,
    onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val factory = LocalCallCameraEngineFactory.current
    val screenVisible = LocalCallCameraScreenVisible.current
    val latestFrame = rememberUpdatedState(onFrame)
    val latestError = rememberUpdatedState(onError)
    val view = remember(context) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
        contentDescription = "我的摄像头预览"
    } }
    AndroidView(factory = { view }, modifier = modifier.testTag("call_camera_preview"))
    DisposableEffect(context, owner, view, front, factory, screenVisible) {
        val engine = factory.create(context, owner, view, front, { latestFrame.value(it) }, { latestError.value(it) })
        fun reconcile() {
            val visible = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                screenVisible(context)
            if (visible) engine.start() else engine.stop()
        }
        val observer = LifecycleEventObserver { _, _ -> reconcile() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) engine.stop() else reconcile()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        owner.lifecycle.addObserver(observer)
        reconcile()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            try { context.unregisterReceiver(receiver) }
            finally { engine.close() }
        }
    }
}
