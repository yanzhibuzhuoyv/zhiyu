package com.zhiyu.app.ui

import androidx.compose.runtime.*
import com.zhiyu.app.data.duplex.CallCameraTurnSource
import com.zhiyu.app.data.duplex.CallReplySource
import com.zhiyu.app.domain.CallCameraFrame
import com.zhiyu.app.domain.MediaRules
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rig2d.voice.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class DuplexPhase { IDLE, PREPARING, LISTENING, THINKING, SPEAKING, STOPPING }

@Stable class DuplexVoiceController(private val uiScope:CoroutineScope,
    private val factory:(VoiceTokenSource,(Boolean,Boolean)->Unit)->DuplexPortsFactory,
    private val config:DuplexConfig=DuplexConfig(endSilenceMillis=com.zhiyu.app.domain.CallTurnPolicy.endpointMillis,maxReplyCodePoints=32768),
    private val dispatcher:CoroutineDispatcher=Dispatchers.Default) {
    var phase by mutableStateOf(DuplexPhase.IDLE);private set
    var transcript by mutableStateOf("");private set
    var mouthLevel by mutableFloatStateOf(0f);private set
    var error by mutableStateOf<String?>(null);private set
    var aecEnabled by mutableStateOf<Boolean?>(null);private set
    var headset by mutableStateOf<Boolean?>(null);private set
    val active get()=phase!=DuplexPhase.IDLE
    private data class Run(val core:DuplexCallSession,val source:CallReplySource,val scope:CoroutineScope)
    private val worker=CoroutineScope(SupervisorJob()+dispatcher)
    private val lifecycle=Mutex()
    private val epoch=AtomicLong()
    private val current=AtomicReference<Run?>()
    private var starting:Job?=null
    private var stopping:Job?=null
    private var stamp:VoiceStamp?=null
    private var disposed=false

    fun start(chat:String,stream:((String,String)->Flow<String>)?=null,
        camera:CallCameraTurnSource?=null,
        visualStream:((String,String,CallCameraFrame)->Flow<String>)?=null,
        visualSend:(suspend(String,String,CallCameraFrame)->String)?=null,
        send:suspend(String,String)->String) {
        if(disposed||active)return
        MediaRules.path(chat)
        val token=epoch.incrementAndGet()
        phase=DuplexPhase.PREPARING;error=null;transcript="";mouthLevel=0f;stamp=null;aecEnabled=null;headset=null
        starting=worker.launch {lifecycle.withLock {
            release(current.getAndSet(null))
            ensureActive();if(epoch.get()!=token)return@withLock
            var run:Run?=null
            try {
                run=createRun(token,chat,send,stream,camera,visualStream,visualSend);current.set(run)
                run.core.start();ensureActive()
                if(epoch.get()!=token)throw CancellationException("Stale call startup")
            }catch(cancelled:CancellationException) {
                current.compareAndSet(run,null);release(run);throw cancelled
            }catch(_:Exception) {
                current.compareAndSet(run,null);release(run)
                update(token){error="通话暂时没有准备好，请检查麦克风后再试";phase=DuplexPhase.IDLE}
            }catch(_:LinkageError) {
                current.compareAndSet(run,null);release(run)
                update(token){error="当前安装包的本机语音无法运行，请使用适配这台手机的版本";phase=DuplexPhase.IDLE}
            }
        }}
    }
    private fun createRun(token:Long,chat:String,send:suspend(String,String)->String,stream:((String,String)->Flow<String>)?,
        camera:CallCameraTurnSource?,visualStream:((String,String,CallCameraFrame)->Flow<String>)?,
        visualSend:(suspend(String,String,CallCameraFrame)->String)?):Run {
        val source=if(stream==null) {
            if(camera!=null&&visualSend!=null)CallReplySource(chat,send,camera,visualSend)
            else CallReplySource(chat,send)
        } else {
            if(camera!=null&&visualStream!=null)CallReplySource.streaming(chat,stream,camera,visualStream)
            else CallReplySource.streaming(chat,stream)
        }
        val callScope=CoroutineScope(SupervisorJob(worker.coroutineContext[Job])+dispatcher)
        try {
            val ports=factory(source){aec,phones->update(token){aecEnabled=aec;headset=phones}}
            val core=DuplexCallSession(callScope,ports,config,
                onEvent={incoming->update(token){event(incoming)}},
                onMouth={voiceStamp,level->update(token){if(stamp==voiceStamp) {
                    mouthLevel=level.coerceIn(0f,1f);if(level>0f)phase=DuplexPhase.SPEAKING
                }}})
            return Run(core,source,callScope)
        }catch(failure:Throwable){source.close();callScope.cancel();throw failure}
    }
    private fun update(token:Long,action:()->Unit) {uiScope.launch {if(!disposed&&epoch.get()==token)action()}}
    private fun event(value:VoiceEvent) {
        when(value) {
            is VoiceEvent.Started->phase=DuplexPhase.LISTENING
            is VoiceEvent.Listening->{stamp=value.stamp;transcript="";mouthLevel=0f;phase=DuplexPhase.LISTENING}
            is VoiceEvent.Transcript->if(stamp==value.stamp){transcript=value.text;phase=if(value.final)DuplexPhase.THINKING else DuplexPhase.LISTENING}
            is VoiceEvent.Interrupted->if(stamp==value.stamp){mouthLevel=0f;phase=DuplexPhase.LISTENING}
            is VoiceEvent.ReplyComplete->if(stamp==value.stamp){mouthLevel=0f;phase=DuplexPhase.LISTENING}
            is VoiceEvent.Fault->{mouthLevel=0f;error=when(value.fault) {
                VoiceFault.CAPTURE,VoiceFault.CAPTURE_OVERFLOW->"收音暂时中断了，可以重新接通"
                VoiceFault.RECOGNITION->"刚才没听清，可以重新接通再说一次"
                VoiceFault.GENERATION->"刚才的回答没有完成，你说的话仍会保留"
                VoiceFault.SYNTHESIS,VoiceFault.PLAYBACK->"声音暂时中断，聊天里的回复仍会保留"
                VoiceFault.RELEASE->"通话资源正在释放，请稍后重新接通"
            };phase=DuplexPhase.LISTENING}
            is VoiceEvent.Stopped->{mouthLevel=0f;phase=DuplexPhase.IDLE}
        }
    }
    private suspend fun release(run:Run?)=withContext(NonCancellable) {
        if(run!=null)try {run.core.stop()}finally {run.source.close();run.scope.cancel()}
    }
    fun stop() {stopOwned()}
    private fun stopOwned():Job {
        // Closing the dialog can call stop twice. Both calls must await the same release;
        // independently scheduled workers could otherwise report idle before the owner closes.
        stopping?.takeIf{!it.isCompleted}?.let{return it}
        val token=epoch.incrementAndGet()
        starting?.cancel();starting=null
        val run=current.getAndSet(null)
        mouthLevel=0f;transcript="";stamp=null
        if(!disposed)phase=DuplexPhase.STOPPING
        val cleanup=worker.launch(start=CoroutineStart.LAZY) {
            try {lifecycle.withLock {release(run)}}
            finally {update(token){phase=DuplexPhase.IDLE}}
        }
        stopping=cleanup;cleanup.start()
        return cleanup
    }
    fun dispose() {
        if(disposed)return
        disposed=true
        stopOwned().invokeOnCompletion {worker.cancel()}
    }
    fun permissionDenied(){error="允许麦克风后，我才能听到你说话"}
}
