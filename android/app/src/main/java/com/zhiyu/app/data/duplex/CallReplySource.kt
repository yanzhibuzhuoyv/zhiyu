package com.zhiyu.app.data.duplex

import com.zhiyu.app.domain.CallCameraFrame
import com.zhiyu.app.domain.ConnectionRules
import com.zhiyu.app.domain.MediaRules
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import rig2d.voice.VoiceStamp
import rig2d.voice.VoiceTokenSource
import java.util.concurrent.atomic.AtomicReference

/** One final utterance enters the existing chat, owned by the collecting reply job. */
class CallReplySource private constructor(private val chat: String,
    private val send: suspend (String, String) -> String,
    private val stream: ((String, String) -> Flow<String>)?,
    private val camera: CallCameraTurnSource? = null,
    private val visualSend: (suspend (String, String, CallCameraFrame) -> String)? = null,
    private val visualStream: ((String, String, CallCameraFrame) -> Flow<String>)? = null,
    private val onVisualCancelled: (VoiceStamp) -> Unit = {}) : VoiceTokenSource {
    constructor(chat: String, send: suspend (String, String) -> String)
        : this(chat, send, null, null, null, null)

    constructor(chat: String, send: suspend (String, String) -> String,
        camera: CallCameraTurnSource, visualSend: suspend (String, String, CallCameraFrame) -> String,
        onVisualCancelled: (VoiceStamp) -> Unit = {})
        : this(chat, send, null, camera, visualSend, null, onVisualCancelled)

    companion object {
        fun streaming(chat: String, send: (String, String) -> Flow<String>): CallReplySource =
            CallReplySource(chat, { _, _ -> error("Streaming source requires its flow") }, send, null, null, null)

        fun streaming(chat: String, send: (String, String) -> Flow<String>,
            camera: CallCameraTurnSource, visualStream: (String, String, CallCameraFrame) -> Flow<String>,
            onVisualCancelled: (VoiceStamp) -> Unit = {}): CallReplySource =
            CallReplySource(chat, { _, _ -> error("Streaming source requires its flow") }, send,
                camera, null, visualStream, onVisualCancelled)
    }

    private val lock = Any()
    private var closed = false
    private var newest: VoiceStamp? = null
    private var pending: Pair<VoiceStamp, Job>? = null
    private val visual = AtomicReference<Pair<CallCameraFrame, VoiceStamp>?>()
    private val cameraObservation = camera?.observeInvalidation { frame ->
        visual.get()?.takeIf { it.first === frame }?.let {
            if (visual.compareAndSet(it, null)) onVisualCancelled(it.second)
        }
    }
    init { MediaRules.path(chat) }

    override fun tokens(transcript: String, stamp: VoiceStamp): Flow<String> = flow {
        val text = ConnectionRules.message(transcript)
        val job = checkNotNull(currentCoroutineContext()[Job])
        val owned = stamp to job
        synchronized(lock) {
            check(!closed && pending == null) { "通话请求已结束或仍在处理中" }
            check(stamp.session > 0 && stamp.utterance > 0 && newest?.let {
                stamp.session > it.session || stamp.session == it.session && stamp.utterance > it.utterance
            } != false) { "这句话已处理" }
            newest = stamp; pending = owned
        }
        visual.set(null)
        try {
            val frame = camera?.currentFrame()
            if (frame != null && (visualStream != null || visualSend != null)) {
                visual.set(frame to stamp)
                try {
                    camera.withFrame(frame) {
                        collectReply(
                            visualStream?.invoke(chat, text, frame)
                                ?: flow { emit(checkNotNull(visualSend).invoke(chat, text, frame)) },
                            owned)
                    }
                } catch (cancelled: CancellationException) {
                    if (cancelled.isCameraInvalidation()) onVisualCancelled(stamp)
                    throw cancelled
                }
            } else {
                collectReply(stream?.invoke(chat, text) ?: flow { emit(send(chat, text)) }, owned)
            }
        } finally {
            synchronized(lock) { if (pending == owned) pending = null }
        }
    }

    private suspend fun FlowCollector<String>.collectReply(reply: Flow<String>, owned: Pair<VoiceStamp, Job>) {
        reply.collect { delta ->
            currentCoroutineContext().ensureActive()
            synchronized(lock) { if (closed || pending != owned) throw CancellationException("Stale call reply") }
            emit(delta)
        }
    }

    private fun CancellationException.isCameraInvalidation(): Boolean {
        val seen = HashSet<Throwable>()
        var current: Throwable? = this
        while (current != null && seen.add(current)) {
            if (current is CallCameraCancelledException) return true
            current = current.cause
        }
        return false
    }

    override fun cancel(stamp: VoiceStamp) {
        val job = synchronized(lock) { pending?.takeIf { it.first == stamp }?.also { pending = null }?.second }
        job?.cancel()
    }

    override fun close() {
        cameraObservation?.close()
        visual.set(null)
        val job = synchronized(lock) { closed = true; pending?.also { pending = null }?.second }
        job?.cancel()
    }
}
