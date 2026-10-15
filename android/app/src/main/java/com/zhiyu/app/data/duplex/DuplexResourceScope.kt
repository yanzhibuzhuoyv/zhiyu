package com.zhiyu.app.data.duplex

/** Acquiring a call is transactional; ownership transfers only after the dispatcher boundary. */
internal class DuplexResourceScope : AutoCloseable {
    private val values = ArrayDeque<AutoCloseable>()
    private var finished = false

    fun <T : AutoCloseable> own(value: T): T = synchronized(this) {
        check(!finished) { "Call acquisition already finished" }
        values.addLast(value)
        value
    }

    fun transfer() = synchronized(this) {
        check(!finished) { "Call acquisition already finished" }
        values.clear()
        finished = true
    }

    override fun close() {
        val releasing = synchronized(this) {
            if (finished) return
            finished = true
            values.reversed().also { values.clear() }
        }
        var first: Throwable? = null
        releasing.forEach { value ->
            try { value.close() } catch (failure: Throwable) {
                if (first == null) first = failure else first!!.addSuppressed(failure)
            }
        }
        first?.let { throw it }
    }
}
