package com.zhiyu.app.data.duplex

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.zhiyu.app.domain.VoiceTuningSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import rig2d.voice.VoiceStamp
import rig2d.voice.VoiceTokenSource
import rig2d.voice.VoicePcm
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/** AVD-only virtual PCM and synthetic local models; no physical microphone, saved PCM, or cloud call. */
class AndroidDuplexPlatformNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun preparesCommunicationRecordAndTrackWithoutStartingMicrophoneOrChangingVolume() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.RECORD_AUDIO")).use { it.readBytes() }
        AndroidDuplexCapture.create(context).use { capture ->
            assertEquals(AudioRecord.STATE_INITIALIZED, capture.audioRecord.state)
            assertEquals(AudioRecord.RECORDSTATE_STOPPED, capture.audioRecord.recordingState)
            assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, capture.audioRecord.audioSource)
            assertEquals(16000, capture.audioRecord.sampleRate)
            val track = DuplexAudioTrackFactory.create(16000)
            try {
                assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
                assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, track.audioAttributes.usage)
                assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, track.audioAttributes.contentType)
            } finally { track.release() }
        }
    }

    @Test fun recognitionRemainsUsableWhileRealNativeSynthesisIsWaitingInItsCallback() = runBlocking {
        withContext(Dispatchers.IO) {
            AndroidDuplexModels(context, 10, VoiceTuningSettings()).use { models ->
                models.warm()
                assertNotSame(models.asrOwner, models.ttsOwner)
                val blocked = CountDownLatch(1); val release = CountDownLatch(1)
                val calls = AtomicInteger(); val workers = Executors.newFixedThreadPool(2)
                val synthesis = workers.submit {
                    models.ttsOwner.synthesize("你好，我在这里。", 10) {
                        if (calls.incrementAndGet() == 2) {
                            blocked.countDown()
                            release.await(8, TimeUnit.SECONDS)
                        } else true
                    }
                }
                try {
                    assertTrue("native callback must be reached", blocked.await(10, TimeUnit.SECONDS))
                    val recognition = workers.submit<String> {
                        models.asrOwner.openRecognition().use { it.accept(FloatArray(640)) }
                    }
                    assertNotNull("ASR must not wait for TTS lock", recognition.get(2, TimeUnit.SECONDS))
                } finally {
                    release.countDown()
                    try { synthesis.get(15, TimeUnit.SECONDS) } finally { workers.shutdownNow() }
                }
            }
        }
    }

    @Test fun foregroundFactoryRestoresRealModeAndVolumeWithoutCollectingMicrophoneOrCallingProvider() = runBlocking {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.RECORD_AUDIO")).use { it.readBytes() }
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(AudioManager::class.java)
            val previousMode = manager.mode
            val previousVolume = manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            var capabilities: DuplexAudioCapabilities? = null
            var tokenClosed = false
            val tokenSource = object : VoiceTokenSource {
                override fun tokens(transcript: String, stamp: VoiceStamp) = flow<String> {
                    error("Construction must not invoke a provider")
                }
                override fun cancel(stamp: VoiceStamp) = Unit
                override fun close() { tokenClosed = true }
            }
            val ports = AndroidDuplexPortsFactory(context, 10, VoiceTuningSettings(volume = 0f), tokenSource) {
                capabilities = it
            }.open()
            val cleanup = DuplexResourceScope()
            cleanup.own(ports.capture); cleanup.own(ports.recognizer); cleanup.own(ports.tokens)
            cleanup.own(ports.synthesizer); cleanup.own(ports.player)
            try {
                assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
                assertNotNull(capabilities)
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    assertTrue("API 31+ must verify the applied route", capabilities!!.routeVerified)
                }
                val capture = ports.capture as AndroidDuplexCapture
                assertEquals(AudioRecord.RECORDSTATE_STOPPED, capture.audioRecord.recordingState)
                assertEquals(capture.aecEnabled, capabilities!!.aecEnabled)
                assertEquals(previousVolume, manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
            } finally { cleanup.close() }
            assertTrue(tokenClosed)
            assertEquals(previousMode, manager.mode)
            assertEquals(previousVolume, manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
        }
    }

    @Test fun virtualAvdCaptureAndCommunicationPlaybackActuallyOverlapAndCancelCleanly() = runBlocking {
        assumeTrue("Only the owned AVD may collect virtual microphone PCM in this test",
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish") || android.os.Build.MODEL.contains("sdk_gphone"))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.RECORD_AUDIO")).use { it.readBytes() }
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(AudioManager::class.java)
            val previousMode = manager.mode
            val previousVolume = manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val tokenClosed = AtomicBoolean(false)
            val tokenSource = object : VoiceTokenSource {
                override fun tokens(transcript: String, stamp: VoiceStamp) = flow<String> { error("No provider is allowed") }
                override fun cancel(stamp: VoiceStamp) = Unit
                override fun close() { tokenClosed.set(true) }
            }
            val ports = withTimeout(30000) {
                // The owned emulator is launched with -no-audio. Keep this track's gain positive:
                // public activePlaybackConfigurations excludes MUTED_BY_CLIENT_VOLUME players,
                // so volume=0 races the asynchronous mute update even while PCM is being consumed.
                AndroidDuplexPortsFactory(context, 10, VoiceTuningSettings(volume = .01f), tokenSource).open()
            }
            val capture = ports.capture as AndroidDuplexCapture
            val cleanup = DuplexResourceScope()
            cleanup.own(ports.capture); cleanup.own(ports.recognizer); cleanup.own(ports.tokens)
            cleanup.own(ports.synthesizer); cleanup.own(ports.player)
            val frames = AtomicInteger()
            try {
                exerciseVirtualOverlap(ports, capture, manager, frames)
            } finally { cleanup.close() }
            assertTrue(tokenClosed.get())
            assertEquals(AudioRecord.STATE_UNINITIALIZED, capture.audioRecord.state)
            assertEquals(previousMode, manager.mode)
            assertEquals(previousVolume, manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("duplex_virtual_avd_overlap", "overlap=true; frames=${frames.get()}; frame_ms=40; physical_aec=not_tested")
            })
        }
    }

    private suspend fun exerciseVirtualOverlap(ports: rig2d.voice.DuplexPorts, capture: AndroidDuplexCapture,
        manager: AudioManager, frames: AtomicInteger) = coroutineScope {
        val firstFrame = CompletableDeferred<Unit>(); val overlap = CompletableDeferred<Unit>()
        val writing = AtomicBoolean(false)
        val playback = java.util.concurrent.atomic.AtomicReference<Job?>()
        val recording = launch(Dispatchers.IO) {
            ports.capture.frames().collect { frame ->
                assertEquals(16000, frame.sampleRate); assertEquals(640, frame.sampleCount)
                assertEquals(40L, frame.durationMillis)
                frames.incrementAndGet(); firstFrame.complete(Unit)
                if (writing.get() && playback.get()?.isActive == true &&
                    capture.audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING &&
                    manager.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION }) {
                    overlap.complete(Unit)
                }
            }
        }
        val stamp = VoiceStamp(1, 1)
        try {
            withTimeout(5000) { firstFrame.await() }
            val synthetic = VoicePcm(FloatArray(32000) { index -> (.03 * sin(2 * PI * 220 * index / 16000)).toFloat() }, 16000)
            playback.set(launch(Dispatchers.IO) {
                ports.player.play(synthetic, stamp) { level -> if (level > 0f) writing.set(true) }
            })
            withTimeout(5000) { overlap.await() }
            assertTrue("Capture must continue during actual AudioTrack playback", recording.isActive)
            assertTrue("Playback must still be active while capture frames arrive", playback.get()!!.isActive)
            assertTrue(frames.get() >= 2)
        } finally {
            withContext(NonCancellable) {
                val stopFailure = runCatching { ports.player.stop(stamp) }.exceptionOrNull()
                playback.get()?.cancel()
                recording.cancel()
                withTimeout(5000) {
                    playback.get()?.join()
                    recording.join()
                }
                stopFailure?.let { throw it }
            }
        }
    }
}
