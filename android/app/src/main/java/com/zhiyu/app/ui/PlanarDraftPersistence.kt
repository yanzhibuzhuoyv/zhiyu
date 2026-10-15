package com.zhiyu.app.ui

import com.zhiyu.app.data.planar.PlanarDraftCheckpoint
import com.zhiyu.app.data.planar.PlanarDraftStore
import com.zhiyu.app.domain.planar.PlanarDraft
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Short lived application IO work survives Activity disposal. No bitmaps or user keys enter this queue. */
internal object PlanarDraftPersistence {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val lock=Any()
    private val pending=mutableMapOf<String,Deferred<Unit>>()
    private val revisions=linkedMapOf<String,Long>()
    private var nextRevision=0L
    private val writer=Mutex()
    fun revision(store:PlanarDraftStore):Long=synchronized(lock) {
        revisions[store.identity]?:run {
            while(revisions.size>=32){val expired=revisions.keys.firstOrNull {it !in pending}?:break;revisions.remove(expired)}
            (++nextRevision).also {revisions[store.identity]=it}
        }
    }
    fun submit(store:PlanarDraftStore,revision:Long,binding:String,parent:String?,width:Int,height:Int,source:ByteArray,
        draft:PlanarDraft,immediate:Boolean=false):Deferred<Unit> = synchronized(lock) {
        if(revisions[store.identity]!=revision)return@synchronized CompletableDeferred<Unit>().also {it.cancel()}
        pending[store.identity]?.cancel()
        val task=scope.async(start=CoroutineStart.LAZY) {
            if(!immediate)delay(150)
            writer.withLock {
                checkCurrent(store,revision)
                val text=PlanarDraftCodec.encodeCheckpoint(draft,width,height)
                ensureActive();checkCurrent(store,revision)
                store.save(PlanarDraftCheckpoint(binding,parent,width,height,source,text)){
                    ensureActive();checkCurrent(store,revision)
                }
            }
        }
        pending[store.identity]=task
        task.invokeOnCompletion {synchronized(lock){if(pending[store.identity]===task)pending.remove(store.identity)}}
        task.start();task
    }
    suspend fun awaitPending(store:PlanarDraftStore) {
        while(true){val current=synchronized(lock){pending[store.identity]}?:return
            try {current.await()}catch(failure:CancellationException){currentCoroutineContext().ensureActive()}}
    }
    suspend fun load(store:PlanarDraftStore,binding:String,parent:String?)=writer.withLock {
        withContext(Dispatchers.IO){store.load(binding,parent)}
    }
    suspend fun clear(store:PlanarDraftStore):Long = writer.withLock {
        val next=synchronized(lock) {
            pending.remove(store.identity)?.cancel()
            (++nextRevision).also {revisions[store.identity]=it}
        }
        withContext(Dispatchers.IO){store.clear()};next
    }
    private fun checkCurrent(store:PlanarDraftStore,revision:Long) {
        if(synchronized(lock){revisions[store.identity]}!=revision)throw CancellationException("stale planar draft")
    }
}
