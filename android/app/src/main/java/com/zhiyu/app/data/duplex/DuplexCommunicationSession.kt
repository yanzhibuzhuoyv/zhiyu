package com.zhiyu.app.data.duplex

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/** A foreground call owns one focus request and one communication route, restored at capture close. */
@Suppress("DEPRECATION")
internal class DuplexCommunicationSession private constructor(private val manager: AudioManager,
    private val health: DuplexAudioHealth) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val previousMode = manager.mode
    private val previousSpeaker = manager.isSpeakerphoneOn
    private var focused = false
    private var modeChanged = false
    private var routeRequested = false
    private var legacyRouteChanged = false
    private var devicesRegistered = false
    private var selected: AudioDeviceInfo? = null
    private var routed = false
    val headset: Boolean get() = selected?.type in HEADSETS
    val routeVerified: Boolean get() = routed
    val route: DuplexAudioRoute get() = when {
        headset -> DuplexAudioRoute.HEADSET
        selected?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> DuplexAudioRoute.EARPIECE
        selected?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> DuplexAudioRoute.SPEAKER
        else -> DuplexAudioRoute.UNKNOWN
    }

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(DuplexAudioTrackFactory.attributes()).setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) health.fail("voice_duplex_focus_lost")
        }, Handler(Looper.getMainLooper())).build()

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (!closed.get() && removed.any { it.id == selected?.id }) health.fail("voice_duplex_route_lost")
        }
    }

    private fun acquire() {
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Call audio focus denied" }
        focused = true
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        modeChanged = true
        check(manager.mode == AudioManager.MODE_IN_COMMUNICATION) { "Communication audio mode unavailable" }
        if (Build.VERSION.SDK_INT >= 31) {
            selected = manager.availableCommunicationDevices.filter { it.isSink }.minByOrNull { priority(it.type) }
            checkNotNull(selected) { "Communication output route unavailable" }
            check(manager.setCommunicationDevice(selected!!)) { "Communication output route denied" }
            routeRequested = true
        } else {
            selected = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink &&
                (it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO || manager.isBluetoothScoOn) }
                .filter { it.type in HEADSETS || it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }.minByOrNull { priority(it.type) }
            checkNotNull(selected) { "Communication output route unavailable" }
            val speaker = selected?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            if (previousSpeaker != speaker) {
                manager.isSpeakerphoneOn = speaker
                legacyRouteChanged = true
            }
        }
        manager.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        devicesRegistered = true
        health.check()
    }

    /** Poll from fixed-size capture reads: an accepted route request is not proof of its actual route. */
    fun checkRoute() {
        health.check()
        if (Build.VERSION.SDK_INT >= 31) {
            val actual = manager.communicationDevice
            if (actual?.id == selected?.id) routed = true
            else if (routed) health.fail("voice_duplex_route_changed")
        }
        health.check()
    }

    suspend fun awaitRoute() {
        if (Build.VERSION.SDK_INT < 31) return // Legacy availability is reported as unverified until physical testing.
        val deadline = SystemClock.elapsedRealtime() + 1500
        while (!routed) {
            currentCoroutineContext().ensureActive(); checkRoute()
            check(SystemClock.elapsedRealtime() <= deadline) { "Communication output route was not applied" }
            if (!routed) delay(10)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        fun release(action: () -> Unit) { try { action() } catch (problem: Throwable) {
            if (failure == null) failure = problem else failure!!.addSuppressed(problem)
        } }
        if (devicesRegistered) release { manager.unregisterAudioDeviceCallback(devices) }
        if (routeRequested && Build.VERSION.SDK_INT >= 31) release { manager.clearCommunicationDevice() }
        if (legacyRouteChanged) release { manager.isSpeakerphoneOn = previousSpeaker }
        if (modeChanged) release { manager.mode = previousMode }
        if (focused) release { manager.abandonAudioFocusRequest(focus) }
        health.close()
        failure?.let { throw it }
    }

    companion object {
        private val HEADSETS = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
        internal fun priority(type: Int): Int = when {
            type in HEADSETS -> 0
            type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
            type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 2
            else -> 3
        }

        fun open(context: Context, health: DuplexAudioHealth): DuplexCommunicationSession {
            val manager = checkNotNull(context.applicationContext.getSystemService(AudioManager::class.java))
            val session = DuplexCommunicationSession(manager, health)
            try { session.acquire(); return session }
            catch (failure: Throwable) {
                try { session.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
