package com.zhiyu.app.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.Message
import com.zhiyu.app.domain.VoiceTuningSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VoicePhase { IDLE, PREPARING, LISTENING, REVIEWING, WAITING, SPEAKING }

/** Foreground, one-turn-at-a-time voice. ASR text can be corrected before becoming a user message. */
@Stable class VoiceController(context: Context, private val scope: CoroutineScope,
    private val nativeSpeakerCount: (LocalVoiceEngine) -> Int = LocalVoiceEngine::speakerCount) {
    private val app = context.applicationContext
    private val preferences = LocalVoicePreferences(app)
    private val microphone = LocalVoiceMicrophone(app)
    private val playback = LocalVoicePlayback(app)
    private val operationLock = Mutex()
    private val settingsLock = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: LocalVoiceEngine? = null
    private var currentTask: Job? = null
    @Volatile private var version = 0L
    private var callActive = false
    private var seenAssistant: String? = null
    var selectedVoiceId by mutableIntStateOf(LocalVoiceRules.choices.first().id); private set
    var tuning by mutableStateOf(VoiceTuningSettings()); private set
    var settingsSaving by mutableStateOf(false); private set
    var phase by mutableStateOf(VoicePhase.IDLE); private set
    var transcript by mutableStateOf(""); private set
    var reviewText by mutableStateOf(""); private set
    var mouthLevel by mutableFloatStateOf(0f); private set
    var error by mutableStateOf<String?>(null); private set
    val busy: Boolean get() = phase in setOf(VoicePhase.PREPARING, VoicePhase.LISTENING, VoicePhase.WAITING, VoicePhase.SPEAKING)

    fun restore() { scope.launch { settingsLock.withLock {
        try {
            val (id, value) = withContext(Dispatchers.IO) { preferences.snapshot() }
            selectedVoiceId = id; tuning = value
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = "声音设置暂时没有读到，可以重新选择并保存"
        }
    } } }

    fun selectVoice(id: Int) {
        if (busy || settingsSaving) return
        updateSettings("声音选择没有保存，请重试") {
            LocalVoiceRules.voice(id)
            withContext(Dispatchers.Default) { operationLock.withLock {
                currentCoroutineContext().ensureActive()
                val current = engine ?: LocalVoiceEngine(app).also { engine = it }
                require(id < nativeSpeakerCount(current)) { "内置模型没有这个音色" }
            } }
            withContext(Dispatchers.IO) { preferences.select(id) }
            selectedVoiceId = id
        }
    }

    fun saveTuning(value: VoiceTuningSettings, onSaved: () -> Unit = {}) {
        if (busy || settingsSaving) return
        updateSettings("声音参数没有保存，请重试") {
            withContext(Dispatchers.IO) { preferences.saveTuning(value) }
            tuning = value
            onSaved()
        }
    }

    private fun updateSettings(message: String, action: suspend () -> Unit) {
        settingsSaving = true
        error = null
        scope.launch { try {
            settingsLock.withLock { action() }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = message
        } catch (_: LinkageError) {
            error = "当前安装包的本机语音无法运行，请使用适配这台手机的版本"
        } finally { settingsSaving = false } }
    }
    fun dismissError() { error = null }
    fun editReview(value: String) { if (phase == VoicePhase.REVIEWING) reviewText = value }
    fun permissionDenied() { error = "允许麦克风后，我才能听到你说话" }

    fun beginCall(lastAssistantId: String?) {
        if (callActive) return
        callActive = true
        seenAssistant = lastAssistantId
        phase = VoicePhase.IDLE
        error = null
    }

    fun startListening() {
        if (busy) return
        transcript = ""; reviewText = ""
        operation(VoicePhase.PREPARING) { current, token ->
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val text = microphone.capture({ current.openRecognition() },
                    onStarted = { scope.launch { if (version == token) phase = VoicePhase.LISTENING } },
                    onPartial = { value -> scope.launch { if (version == token) transcript = value } })
                withContext(Dispatchers.Main.immediate) {
                    if (version == token) { transcript = text; reviewText = text; phase = VoicePhase.REVIEWING }
                }
            }
        }
    }

    fun finishListening() { if (phase == VoicePhase.LISTENING) { phase = VoicePhase.PREPARING; microphone.finishTurn() } }

    fun submit(sendText: (String) -> Unit) {
        if (phase != VoicePhase.REVIEWING || reviewText.isBlank()) return
        try {
            com.zhiyu.app.domain.ConnectionRules.message(reviewText)
            phase = VoicePhase.WAITING
            sendText(reviewText)
        } catch (_: Exception) { phase = VoicePhase.REVIEWING; error = "这句话没能发出去，可以修改后再试" }
    }

    fun observeAssistant(message: Message?, chatBusy: Boolean, chatError: String? = null) {
        if (!callActive || phase != VoicePhase.WAITING || chatBusy) return
        if (chatError != null) { phase = VoicePhase.REVIEWING; error = chatError; return }
        if (message == null || message.role != "assistant" || message.id == seenAssistant) return
        seenAssistant = message.id
        if (message.content.isBlank()) { phase = VoicePhase.IDLE; return }
        speak(message.content)
    }

    fun preview(id: Int = selectedVoiceId, value: VoiceTuningSettings = tuning) {
        if (busy || settingsSaving) return
        speak("我在这里，慢慢说。", id, value)
    }

    fun stop() {
        version++
        callActive = false
        currentTask?.cancel()
        microphone.finishTurn()
        playback.stop()
        mouthLevel = 0f
        phase = VoicePhase.IDLE
        cleanupScope.launch { operationLock.withLock { engine?.close(); engine = null } }
    }

    private fun speak(text: String, id: Int = selectedVoiceId, value: VoiceTuningSettings = tuning) = operation(VoicePhase.PREPARING) { current, token ->
        val chunks = LocalVoiceRules.speechChunks(text)
        val job = currentCoroutineContext()[Job]!!
        for (chunk in chunks) {
            currentCoroutineContext().ensureActive()
            if (chunk.isBlank()) continue
            val audio = withContext(Dispatchers.Default) { current.synthesize(chunk, id, value) { job.isActive && version == token } }
            if (version != token) return@operation
            phase = VoicePhase.SPEAKING
            playback.play(audio, volume = value.volume, pitchSemitones = value.pitchSemitones,
                onLevel = { level -> scope.launch { if (version == token) mouthLevel = level } })
        }
        if (version == token) { mouthLevel = 0f; phase = VoicePhase.IDLE }
    }

    private fun operation(next: VoicePhase, action: suspend (LocalVoiceEngine, Long) -> Unit) {
        val token = ++version
        phase = next
        error = null
        currentTask = scope.launch {
            operationLock.withLock {
                try {
                    currentCoroutineContext().ensureActive()
                    val current = engine ?: withContext(Dispatchers.IO) { LocalVoiceEngine(app) }.also { engine = it }
                    action(current, token)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (version == token) {
                        error = when (failure) {
                            is com.zhiyu.app.domain.ApiFailure -> failure.message
                            is SecurityException -> "麦克风权限暂时没有允许，可以在系统设置里打开"
                            else -> "这次没有听清或说完，可以再试一次"
                        }
                        phase = VoicePhase.IDLE
                    }
                } catch (_: LinkageError) {
                    if (version == token) { error = "当前安装包的本机语音无法运行，请使用适配这台手机的版本"; phase = VoicePhase.IDLE }
                } finally {
                    if (version == token && phase in setOf(VoicePhase.PREPARING, VoicePhase.SPEAKING)) phase = VoicePhase.IDLE
                }
            }
        }
    }
}

@Composable fun rememberVoiceController(): VoiceController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(context, scope) { VoiceController(context, scope) }
    LaunchedEffect(controller) { controller.restore() }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }
    return controller
}
