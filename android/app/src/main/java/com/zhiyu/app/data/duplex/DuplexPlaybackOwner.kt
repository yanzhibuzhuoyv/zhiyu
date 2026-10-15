package com.zhiyu.app.data.duplex

import rig2d.voice.VoiceStamp

/** stop and replace are linearized together so a stale stop cannot pause a newer track. */
internal class DuplexPlaybackOwner<T : Any>(private val halt: (T) -> Unit) {
    private data class Entry<T>(val stamp: VoiceStamp, val value: T, val stopped: Boolean = false)
    private var entry: Entry<T>? = null

    @Synchronized fun attach(stamp: VoiceStamp, value: T) {
        check(entry == null) { "A previous audio track has not been released" }
        entry = Entry(stamp, value)
    }

    @Synchronized fun active(stamp: VoiceStamp, value: T): Boolean =
        entry?.let { it.stamp == stamp && it.value === value && !it.stopped } == true

    @Synchronized fun runIfActive(stamp: VoiceStamp, value: T, action: () -> Unit): Boolean {
        if (!active(stamp, value)) return false
        action()
        return true
    }

    @Synchronized fun detach(value: T) {
        if (entry?.value === value) entry = null
    }

    @Synchronized fun stop(stamp: VoiceStamp?) {
        val current = entry ?: return
        if (current.stopped || (stamp != null && stamp != current.stamp)) return
        entry = current.copy(stopped = true)
        halt(current.value)
    }
}
