package com.zhiyu.app.ui

import android.content.Context
import androidx.compose.runtime.*
import com.zhiyu.app.data.*
import com.zhiyu.app.data.voicemessages.*
import com.zhiyu.app.domain.ConnectionRules
import com.zhiyu.app.domain.VoiceRecordingBuffer
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

interface ChatVoiceRecorder {
    suspend fun record(started: () -> Unit, partial: (String) -> Unit, samples: (FloatArray) -> Unit): String
    fun stop()
}

class AndroidChatVoiceRecorder(context: Context) : ChatVoiceRecorder {
    private val app = context.applicationContext
    private val microphone = LocalVoiceMicrophone(app)
    override suspend fun record(started: () -> Unit, partial: (String) -> Unit,
        samples: (FloatArray) -> Unit): String = withContext(Dispatchers.IO) {
        LocalVoiceEngine(app).use { engine ->
            microphone.capture({ engine.openRecognition() }, started, partial, maximumSeconds = 60,
                autoEndpoint = false, onSamples = samples)
        }
    }
    override fun stop() = microphone.finishTurn()
}

enum class ChatVoicePhase { IDLE, PREPARING, RECORDING, TRANSCRIBING, READY, PLAYING, SENDING }

/** Recording belongs to an explicit foreground action. A queued clip transfers ownership to the VM. */
@Stable class ChatVoiceController(context: Context, private val scope: CoroutineScope,
    private val recorder: ChatVoiceRecorder = AndroidChatVoiceRecorder(context),
    private val store: VoiceMessageStore = VoiceMessageStore(context),
) {
    private val playback = LocalVoicePlayback(context)
    var phase by mutableStateOf(ChatVoicePhase.IDLE); private set
    var draft by mutableStateOf<VoiceClipRecord?>(null); private set
    var transcript by mutableStateOf(""); private set
    var seconds by mutableStateOf(0); private set
    var error by mutableStateOf<String?>(null); private set
    private var job: Job? = null
    @Volatile private var epoch = 0L
    val capturing get() = phase in setOf(ChatVoicePhase.PREPARING, ChatVoicePhase.RECORDING, ChatVoicePhase.TRANSCRIBING)

    fun start() {
        if (job?.isActive == true || draft != null || !scope.isActive) return
        val token = ++epoch
        phase = ChatVoicePhase.PREPARING; error = null; transcript = ""; seconds = 0
        job = scope.launch {
            val buffer = VoiceRecordingBuffer()
            val created = AtomicReference<VoiceClipRecord?>()
            try {
                val recognized = recorder.record(
                    { if (epoch == token) phase = ChatVoicePhase.RECORDING },
                    { if (epoch == token) transcript = it },
                    { buffer.append(it); if (epoch == token) seconds = (buffer.durationMillis / 1000).toInt() })
                currentCoroutineContext().ensureActive()
                val text = ConnectionRules.message(recognized)
                val ready = withContext(Dispatchers.IO) {
                    store.recordDraft(buffer.samples(), 16000, text).also(created::set)
                }
                currentCoroutineContext().ensureActive()
                check(epoch == token)
                transcript = text; draft = ready; phase = ChatVoicePhase.READY
                created.set(null)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (epoch == token) { phase = ChatVoicePhase.IDLE; error = "这段语音没能准备好，请重新录一次。" }
            } finally {
                buffer.clear()
                created.getAndSet(null)?.let { clip ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        try { withTimeout(5_000) { store.discardDraft(clip.id) } }
                        catch (failure: Exception) {
                            android.util.Log.w("ZhiYuVoice", "Unowned recording cleanup deferred")
                        }
                    }
                }
                if (epoch == token && draft == null) phase = ChatVoicePhase.IDLE
            }
        }
    }

    fun finish() {
        if (!capturing) return
        if (phase == ChatVoicePhase.PREPARING) { cancel(); return }
        phase = ChatVoicePhase.TRANSCRIBING
        recorder.stop()
    }

    fun preview() {
        val clip = draft ?: return
        if (phase == ChatVoicePhase.PLAYING) { playback.stop(); job?.cancel(); phase = ChatVoicePhase.READY; return }
        if (job?.isActive == true) return
        val token = epoch
        phase = ChatVoicePhase.PLAYING; error = null
        job = scope.launch {
            try { playback.play(withContext(Dispatchers.IO) { store.read(clip.id) }) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (epoch == token) error = "这段语音暂时无法播放。"
            } finally { if (epoch == token && draft?.id == clip.id) phase = ChatVoicePhase.READY }
        }
    }

    fun send(enqueue: suspend (String, String) -> Boolean) {
        val clip = draft ?: return
        if (phase != ChatVoicePhase.READY) return
        val text = transcript
        phase = ChatVoicePhase.SENDING
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (enqueue(text, clip.id)) {
                    epoch++; draft = null; transcript = ""; phase = ChatVoicePhase.IDLE; seconds = 0
                } else phase = ChatVoicePhase.READY
            } catch (failure: Exception) {
                phase = ChatVoicePhase.READY
                if (failure is CancellationException) throw failure
                error = "语音暂时没能加入发送队列，录音已保留"
            }
        }
    }

    fun cancel() {
        if (phase == ChatVoicePhase.SENDING) return // Wait for durable ownership transfer before deleting a clip.
        epoch++; recorder.stop(); playback.stop(); job?.cancel()
        val previous = draft
        draft = null; transcript = ""; phase = ChatVoicePhase.IDLE; seconds = 0
        if (previous != null && !UnsentVoiceCleanup.request(store, previous.id)) {
            error = "录音草稿暂时没能清理，已发送的语音会保留。"
        }
    }
    fun pause() {
        if (capturing) cancel()
        else if (phase == ChatVoicePhase.PLAYING) { playback.stop(); job?.cancel(); phase = ChatVoicePhase.READY }
    }
}
