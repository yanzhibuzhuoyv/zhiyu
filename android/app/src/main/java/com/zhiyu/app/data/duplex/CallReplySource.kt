package com.zhiyu.app.data.duplex

import com.zhiyu.app.domain.ConnectionRules
import com.zhiyu.app.domain.MediaRules
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import rig2d.voice.VoiceStamp
import rig2d.voice.VoiceTokenSource

/** One final utterance enters the existing chat, owned by the collecting reply job. */
class CallReplySource private constructor(private val chat:String,private val send:suspend(String,String)->String,
    private val stream:((String,String)->Flow<String>)?):VoiceTokenSource {
    constructor(chat:String,send:suspend(String,String)->String):this(chat,send,null)
    companion object {
        fun streaming(chat: String, send: (String, String) -> Flow<String>): CallReplySource =
            CallReplySource(chat,{_,_->error("Streaming source requires its flow")},send)
    }
    private val lock=Any()
    private var closed=false
    private var newest:VoiceStamp?=null
    private var pending:Pair<VoiceStamp,Job>?=null
    init {MediaRules.path(chat)}
    override fun tokens(transcript:String,stamp:VoiceStamp):Flow<String> = flow {
        val text=ConnectionRules.message(transcript)
        val job=checkNotNull(currentCoroutineContext()[Job])
        val owned=stamp to job
        synchronized(lock) {
            check(!closed&&pending==null){"通话请求已结束或仍在处理中"}
            check(stamp.session>0&&stamp.utterance>0&&newest?.let {
                stamp.session>it.session||stamp.session==it.session&&stamp.utterance>it.utterance
            }!=false){"这句话已处理"}
            newest=stamp;pending=owned
        }
        try {
            val reply=stream?.invoke(chat,text) ?: flow {emit(send(chat,text))}
            reply.collect {delta->
                currentCoroutineContext().ensureActive()
                synchronized(lock){if(closed||pending!=owned)throw CancellationException("Stale call reply")}
                emit(delta)
            }
        } finally {synchronized(lock){if(pending==owned)pending=null}}
    }
    override fun cancel(stamp:VoiceStamp) {
        val job=synchronized(lock){pending?.takeIf {it.first==stamp}?.also {pending=null}?.second}
        job?.cancel()
    }
    override fun close() {
        val job=synchronized(lock){closed=true;pending?.also {pending=null}?.second}
        job?.cancel()
    }
}
