package com.zhiyu.app.domain.planar

/** The UI owns immutable drafts; history stores no source Bitmap. */
class PlanarEditHistory<T>(initial: T, private val measure: (T) -> Int, private val limit: Int = 40,
    private val budget: Long = 32L * 1024 * 1024) {
    private data class Entry<T>(val draft:T,val bytes:Long)
    private val past=ArrayDeque<Entry<T>>()
    private val future=ArrayDeque<Entry<T>>()
    private var currentBytes=bytes(initial)
    init { require(limit in 1..200 && budget in 1..128L*1024*1024);require(currentBytes<=budget) }
    var value: T = initial; private set
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    private fun bytes(draft:T)=measure(draft).toLong().also {require(it>=0)}
    fun update(next: T): Boolean {
        if(next==value)return false
        val size=bytes(next);require(size<=budget){"这次修改超出撤销预算，请缩小范围"}
        past.addLast(Entry(value,currentBytes));future.clear();value=next;currentBytes=size
        while(past.size>limit || currentBytes+past.sumOf {it.bytes}>budget)past.removeFirst()
        return true
    }
    fun undo(): Boolean {
        if(!canUndo)return false
        future.addLast(Entry(value,currentBytes));val entry=past.removeLast();value=entry.draft;currentBytes=entry.bytes;return true
    }
    fun redo(): Boolean {
        if(!canRedo)return false
        past.addLast(Entry(value,currentBytes));val entry=future.removeLast();value=entry.draft;currentBytes=entry.bytes;return true
    }
}
