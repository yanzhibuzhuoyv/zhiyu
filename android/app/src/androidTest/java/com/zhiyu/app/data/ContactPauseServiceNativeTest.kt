package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class ContactPauseServiceNativeTest {
    private class Binding:CredentialStore {
        override fun read():SavedConnection?=error("No Key read in this test")
        override fun readBinding()=CompanionBinding("deepseek","synthetic-model",CompanionProfile(style="anxious"))
        override fun write(connection:SavedConnection)=error("Unexpected change")
        override fun writeBinding(binding:CompanionBinding)=error("Unexpected change")
        override fun clear()=error("Unexpected change")
    }
    private class Fixture:AutoCloseable {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val context=NativeV4Context(base)
        val id="synthetic_oc"
        var clock=Instant.parse("2026-10-14T06:00:00Z")
        val pause=ContactPauseStore(context,nowMillis={clock.toEpochMilli()})
        var sent=0
        var onSend:()->Unit={}
        val done=mutableMapOf<String,Message>()
        val gateway=object:LocalProactiveGateway {
            override suspend fun conversations()=listOf(Conversation(id,"合成","synthetic-model",true,clock.toString(),clock.toString()))
            override suspend fun result(id:String,requestId:String)=done[requestId]
            override suspend fun history(id:String)=emptyList<Message>()
            override suspend fun send(id:String,requestId:String,event:LocalNightEvent?):Message {
                sent++;onSend()
                return Message("synthetic_reply","assistant","合成消息",emptyList(),clock.toString(),origin="proactive").also {done[requestId]=it}
            }
        }
        fun service()=LocalProactiveService(context,Binding(),gateway,contactPause=pause,now={clock})
        suspend fun enable(){service().savePreferences(id,ProactivePreferences(enabled=true))}
        override fun close(){context.close();val root=context.filesDir.canonicalFile
            check(root.parentFile==base.filesDir.canonicalFile&&root.name.startsWith("v4_"));root.deleteRecursively()}
    }
    @Test fun explicitPauseBlocksEvenAnxiousDispatchBeforeNetwork()=runBlocking {
        Fixture().use {f->f.enable();f.pause.apply(f.id,ContactPauseCommand.PAUSE,"user_pause")
            val result=f.service().check(f.id,UUID.randomUUID().toString())
            assertFalse(result.emitted);assertEquals("user_paused",result.reason);assertEquals(0,f.sent)}
    }
    @Test fun pauseDuringRequestPreventsReturningAnEmittedMessage()=runBlocking {
        Fixture().use {f->f.enable();f.onSend={f.pause.apply(f.id,ContactPauseCommand.PAUSE,"user_pause")}
            val result=f.service().check(f.id,UUID.randomUUID().toString())
            assertFalse(result.emitted);assertEquals(1,f.sent)}
    }
    @Test fun resumingNeverReplaysAPendingMessageCreatedBeforeThePause()=runBlocking {
        Fixture().use {f->f.enable();val request=UUID.randomUUID().toString()
            assertTrue(f.service().check(f.id,request).emitted)
            f.clock=f.clock.plusSeconds(2);f.pause.apply(f.id,ContactPauseCommand.PAUSE,"user_pause")
            f.clock=f.clock.plusSeconds(2);f.pause.apply(f.id,ContactPauseCommand.RESUME,"user_resume")
            assertFalse(f.service().check(f.id,request).emitted);assertEquals(1,f.sent)}
    }
}
