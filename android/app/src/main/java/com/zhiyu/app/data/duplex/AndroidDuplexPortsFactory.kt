package com.zhiyu.app.data.duplex

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.zhiyu.app.domain.VoiceTuningSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import rig2d.voice.DuplexPorts
import rig2d.voice.DuplexPortsFactory
import rig2d.voice.VoiceTokenSource

enum class DuplexAudioRoute { HEADSET, EARPIECE, SPEAKER, UNKNOWN }

/** Platform capability only. AEC=true does not certify real-room echo performance. */
data class DuplexAudioCapabilities(val aecEnabled: Boolean, val headset: Boolean,
    val route: DuplexAudioRoute = DuplexAudioRoute.UNKNOWN, val routeVerified: Boolean = false)

/** Fresh adapters per foreground call, with no recording until both native models have warmed. */
class AndroidDuplexPortsFactory(context: Context, private val voiceId: Int,
    private val tuning: VoiceTuningSettings, private val tokens: VoiceTokenSource,
    private val onCapabilities: (DuplexAudioCapabilities) -> Unit = {}) : DuplexPortsFactory {
    private val app = context.applicationContext

    override suspend fun open(): DuplexPorts {
        val resources = DuplexResourceScope()
        try {
            resources.own(tokens)
            val ports = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                check(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    "Microphone permission is required for a call"
                }
                val health = DuplexAudioHealth()
                val models = resources.own(AndroidDuplexModels(app, voiceId, tuning))
                models.warm()
                currentCoroutineContext().ensureActive()
                val communication = resources.own(DuplexCommunicationSession.open(app, health))
                communication.awaitRoute()
                val capture = resources.own(AndroidDuplexCapture.create(app, health,
                    onClose = communication::close, checkRoute = communication::checkRoute))
                val player = resources.own(AndroidDuplexPlayer(health, tuning))
                currentCoroutineContext().ensureActive(); health.check()
                onCapabilities(DuplexAudioCapabilities(capture.aecEnabled, communication.headset,
                    communication.route, communication.routeVerified))
                DuplexPorts(capture, models.recognizer, tokens, models.synthesizer, player)
            }
            // withContext may cancel while delivering its result. Keep rollback ownership until here.
            currentCoroutineContext().ensureActive()
            resources.transfer()
            return ports
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                try { resources.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }
}
