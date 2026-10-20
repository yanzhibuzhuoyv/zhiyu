package com.zhiyu.app.data.callcamera

import androidx.annotation.MainThread
import androidx.camera.core.CameraState
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

/** Owned before start(): LiveData may report the first error synchronously inside observe(). */
internal class CallCameraStateObservation(
    private val states: LiveData<CameraState>,
    private val owner: LifecycleOwner,
    private val isCurrent: () -> Boolean,
    private val onFailure: () -> Unit,
) : AutoCloseable {
    private var observing = false
    private var closed = false
    private val observer = Observer<CameraState> { state ->
        if (!closed && isCurrent() && state?.error != null) {
            close()
            onFailure()
        }
    }

    @MainThread
    fun start() {
        if (closed || observing) return
        observing = true
        states.observe(owner, observer)
    }

    @MainThread
    override fun close() {
        if (closed) return
        closed = true
        if (observing) {
            observing = false
            states.removeObserver(observer)
        }
    }
}
