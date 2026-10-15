package com.zhiyu.app.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import rig2d.voice.*
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class DuplexVoiceControllerTest {
    private class Ports(val source:VoiceTokenSource) {
        val frames=Channel<VoicePcm>(16);var opens=0;var closes=0;var playing=false;var interrupted=0
        var lateLevel:((Float)->Unit)?=null
        val bundle=DuplexPorts(object:VoiceCapture {
            override fun frames()=frames.receiveAsFlow()
            override fun close(){closes++;frames.close()}
        },object:VoiceRecognizer {
            override suspend fun open(stamp:VoiceStamp):VoiceRecognition {
                val text="第${++opens}句话"
                return object:VoiceRecognition {
                    override suspend fun accept(frame:VoicePcm)="还没说完"
                    override suspend fun finish()=text
                    override fun close()=Unit
                }
            }
            override fun close(){closes++}
        },source,object:VoiceSynthesizer {
            override suspend fun synthesize(text:String,stamp:VoiceStamp,active:()->Boolean)=VoicePcm(FloatArray(320){0.1f},16000)
            override fun cancel(stamp:VoiceStamp)=Unit
            override fun close(){closes++}
        },object:VoicePlayer {
            override suspend fun play(audio:VoicePcm,stamp:VoiceStamp,onLevel:(Float)->Unit) {
                playing=true;lateLevel=onLevel;onLevel(0.7f)
                try{awaitCancellation()}finally{playing=false}
            }
            override fun stop(stamp:VoiceStamp?){if(playing)interrupted++}
            override fun close(){closes++}
        })
        suspend fun sentence() {
            repeat(3){frames.send(VoicePcm(FloatArray(320){0.25f},16000))}
            repeat(3){frames.send(VoicePcm(FloatArray(320),16000))}
        }
    }
    private val rules=DuplexConfig(startSpeechMillis=20,endSilenceMillis=40,minSpeechMillis=40,preRollMillis=20)
    @Test fun streamedSentenceStartsActualCorePlaybackBeforeResponseTailAndHangupCancelsTheStream()=runTest {
        lateinit var ports:Ports;var streamCancelled=false;var networkCalls=0
        val tail=CompletableDeferred<Unit>()
        val controller=DuplexVoiceController(this,{source,_->DuplexPortsFactory {Ports(source).also{ports=it}.bundle}},rules,StandardTestDispatcher(testScheduler))
        try {
            controller.start("a".repeat(32),stream={_,_->flow {
                networkCalls++;emit("先陪着你，今天发生什么了？")
                try {tail.await();emit("等你慢慢说。")}
                finally {streamCancelled=true}
            }}){_,_->error("The ordinary completion fallback must not be called twice")}
            runCurrent();ports.sentence();runCurrent()
            assertTrue("First speech must start while the response tail is still deferred",ports.playing)
            assertFalse(tail.isCompleted);assertEquals(DuplexPhase.SPEAKING,controller.phase)
            controller.stop();runCurrent()
            assertTrue(streamCancelled);assertFalse(ports.playing)
            assertEquals(1,networkCalls);assertEquals(4,ports.closes)
        }finally{tail.complete(Unit);controller.dispose();runCurrent()}
    }
    @Test fun continuousCaptureInterruptsActualPlaybackAndOnlyFinalsEnterTheSameChat()=runTest {
        lateinit var ports:Ports;var factoryCalls=0;val sent=mutableListOf<Pair<String,String>>()
        val controller=DuplexVoiceController(this,{source,_->DuplexPortsFactory {factoryCalls++;Ports(source).also {ports=it}.bundle}},rules,StandardTestDispatcher(testScheduler))
        try {
            controller.start("a".repeat(32)){chat,text->sent.add(chat to text);"我也想你。"};runCurrent()
            controller.start("a".repeat(32)){_,_->error("duplicate")};runCurrent();assertEquals(1,factoryCalls)
            ports.sentence();runCurrent();assertTrue(ports.playing);assertEquals(DuplexPhase.SPEAKING,controller.phase)
            ports.sentence();runCurrent();assertTrue(ports.interrupted>0);assertTrue(ports.playing)
            assertEquals(listOf("第1句话","第2句话"),sent.map {it.second});assertTrue(sent.all {it.first=="a".repeat(32)})
            controller.stop();runCurrent();assertEquals(DuplexPhase.IDLE,controller.phase);assertFalse(ports.playing)
            ports.lateLevel!!(1f);runCurrent();assertEquals(0f,controller.mouthLevel);assertEquals(4,ports.closes)
        }finally{controller.dispose();runCurrent()}
    }
    @Test fun hangingUpDuringNonCooperativeAcquireReleasesPortsAndRejectsLateCallbacks()=runTest {
        val opened=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>();lateinit var ports:Ports
        val controller=DuplexVoiceController(this,{source,caps->DuplexPortsFactory {
            opened.complete(Unit);withContext(NonCancellable){gate.await()};caps(true,false);Ports(source).also {ports=it}.bundle
        }},rules,StandardTestDispatcher(testScheduler))
        try {
            controller.start("a".repeat(32)){_,_->error("no audio")};runCurrent();opened.await()
            controller.stop();runCurrent();gate.complete(Unit);runCurrent()
            assertEquals(DuplexPhase.IDLE,controller.phase);assertEquals(4,ports.closes)
            assertNull(controller.aecEnabled);assertEquals(0f,controller.mouthLevel)
        }finally{controller.dispose();runCurrent()}
    }
    @Test fun duplicateHangupCannotPublishIdleAheadOfOwnedResourceRelease()=runTest {
        val dispatch=ReorderedDispatcher();lateinit var ports:Ports;var calls=0
        val controller=DuplexVoiceController(this,{source,_->DuplexPortsFactory{calls++;Ports(source).also{ports=it}.bundle}},rules,dispatch)
        try {
            controller.start("a".repeat(32)){_,_->"你好。"};dispatch.drain();runCurrent()
            assertEquals(DuplexPhase.LISTENING,controller.phase)
            controller.stop();controller.stop()
            dispatch.runLast();runCurrent()
            assertEquals("A later duplicate stop cannot report release before the owned run closes",DuplexPhase.STOPPING,controller.phase)
            controller.start("a".repeat(32)){_,_->"你好。"};dispatch.drain();runCurrent()
            assertEquals(1,calls);assertEquals(4,ports.closes);assertEquals(DuplexPhase.IDLE,controller.phase)
        }finally{controller.dispose();dispatch.drain();runCurrent()}
    }
    private class ReorderedDispatcher:CoroutineDispatcher() {
        private val tasks=ArrayDeque<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable){tasks.addLast(block)}
        fun runLast(){check(tasks.isNotEmpty());tasks.removeLast().run()}
        fun drain(){var remaining=200;while(tasks.isNotEmpty()){check(--remaining>0);tasks.removeFirst().run()}}
    }
}
