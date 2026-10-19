package com.zhiyu.app.data.duplex

import com.zhiyu.app.domain.CallCameraFrame
import com.zhiyu.app.domain.ChatPhotoPayload
import com.zhiyu.app.domain.ChatPhotoRules
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import rig2d.voice.VoiceStamp
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CallReplySourceTest {
    private val first=VoiceStamp(1,1)
    private val second=VoiceStamp(1,2)
    @Test fun firstDeltaReachesSpeechBeforeTheReplyHasCompletedWithoutRepeatingTheFullAnswer()=runTest {
        val finish=CompletableDeferred<Unit>();val heard=mutableListOf<String>();var calls=0
        val source=CallReplySource.streaming("a".repeat(32)){_,_->flow {
            calls++;emit("第一句。");finish.await();emit("后一句。")
        }}
        val pending=launch{source.tokens("分享今天",first).toList(heard)}
        runCurrent()
        assertEquals(listOf("第一句。"),heard);assertFalse(pending.isCompleted)
        finish.complete(Unit);pending.join()
        assertEquals(listOf("第一句。","后一句。"),heard);assertEquals(1,calls)
    }
    @Test fun cancelledStreamCannotEmitLateDeltasAndItsDuplicateFinalCannotRestart()=runTest {
        val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>();val heard=mutableListOf<String>()
        val source=CallReplySource.streaming("a".repeat(32)){_,_->flow {
            emit("已经说出的。");entered.complete(Unit)
            withContext(NonCancellable){gate.await()}
            emit("取消后的旧字句")
        }}
        val pending=launch{source.tokens("你好",first).toList(heard)}
        entered.await();source.cancel(first);gate.complete(Unit);pending.join()
        assertTrue(pending.isCancelled);assertEquals(listOf("已经说出的。"),heard)
        assertThrows(IllegalStateException::class.java){runBlocking{source.tokens("重发",first).toList()}}
    }
    @Test fun oneFinalEntersOnlyTheBoundChatAndDuplicateFinalCannotSendAgain()=runTest {
        val sent=mutableListOf<Pair<String,String>>()
        val source=CallReplySource("a".repeat(32)){chat,text->sent.add(chat to text);"我听见啦。"}
        assertEquals(listOf("我听见啦。"),source.tokens("想和你分享今天",first).toList())
        assertThrows(IllegalStateException::class.java){runBlocking{source.tokens("重复",first).toList()}}
        assertEquals(listOf("a".repeat(32) to "想和你分享今天"),sent)
    }
    @Test fun interruptionCancelsTheActualCollectingChatRequest()=runTest {
        val entered=CompletableDeferred<Unit>();var cancelled=false
        val source=CallReplySource("a".repeat(32)){_,_->entered.complete(Unit)
            try {awaitCancellation()}finally {cancelled=true}}
        val pending=launch{source.tokens("等回答",first).collect()}
        entered.await();source.cancel(first);pending.join()
        assertTrue(pending.isCancelled);assertTrue(cancelled)
    }
    @Test fun nonCooperativeOldReplyNeverEmitsAfterCancellationAndOldStopCannotCancelNewTurn()=runTest {
        val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val heard=mutableListOf<String>()
        val source=CallReplySource("a".repeat(32)){_,text->if(text=="旧句"){
            entered.complete(Unit);withContext(NonCancellable){gate.await()};"旧回复"
        }else "新回复"}
        val pending=launch{source.tokens("旧句",first).toList(heard)}
        entered.await();source.cancel(first);gate.complete(Unit);pending.join()
        source.cancel(first)
        assertEquals(listOf("新回复"),source.tokens("新句",second).toList());assertTrue(heard.isEmpty())
    }
    @Test fun closeCancelsOwnedRequestAndPermanentlyRejectsMoreFinals()=runTest {
        val entered=CompletableDeferred<Unit>();var calls=0
        val source=CallReplySource("a".repeat(32)){_,_->calls++;entered.complete(Unit);awaitCancellation()}
        val pending=launch{source.tokens("未完",first).collect()}
        entered.await();source.close();source.close();pending.join()
        assertTrue(pending.isCancelled)
        assertThrows(IllegalStateException::class.java){runBlocking{source.tokens("挂断后",second).toList()}}
        assertEquals(1,calls)
    }

    @Test fun onlyTheTurnWithACurrentFrameUsesTheVisualPath()=runTest {
        val frame=CallCameraFrame(ChatPhotoPayload(
            ChatPhotoRules.sha(byteArrayOf(-1,-40,-1,1)),ChatPhotoRules.sha(byteArrayOf(-1,-40,-1,1)),
            "image/jpeg",byteArrayOf(-1,-40,-1,1)),Instant.EPOCH,1000)
        val camera=object:CallCameraTurnSource {
            var current:CallCameraFrame?=frame
            var leases=0
            override fun currentFrame()=current
            override suspend fun <T> withFrame(frame:CallCameraFrame,block:suspend()->T):T {
                leases++;return block()
            }
        }
        val source=CallReplySource.streaming("a".repeat(32),{_,_->flow{emit("纯文字")}},
            camera,{_,_,_->flow{emit("看见画面")}})
        assertEquals(listOf("看见画面"),source.tokens("看这个",first).toList())
        camera.current=null
        assertEquals(listOf("纯文字"),source.tokens("接着说",second).toList())
        assertEquals(1,camera.leases)
    }
}
