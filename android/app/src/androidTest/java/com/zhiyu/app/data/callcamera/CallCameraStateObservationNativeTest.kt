package com.zhiyu.app.data.callcamera

import androidx.camera.core.CameraState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.MutableLiveData
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Real LiveData dispatch and lifecycle removal, with synthetic CameraX states and no camera access. */
class CallCameraStateObservationNativeTest {
    @Test fun runtimeErrorClosesObservationBeforeReportingAndOnlyReportsOnce() = onMain {
        val owner = TestOwner()
        val states = MutableLiveData(CameraState.create(CameraState.Type.OPEN))
        var errors = 0
        val watch = CallCameraStateObservation(states, owner, { true }) {
            assertFalse(states.hasObservers())
            errors++
        }
        try {
            watch.start()
            assertTrue(states.hasActiveObservers())
            assertEquals(0, errors)
            states.value = failedState()
            states.value = failedState()
            assertEquals(1, errors)
            assertFalse(states.hasObservers())
        } finally { watch.close(); owner.destroy() }
    }

    @Test fun initialErrorCanSynchronouslyCloseItsOwningBindingWithoutReattaching() = onMain {
        val owner = TestOwner()
        val states = MutableLiveData(failedState())
        var errors = 0
        var ownedWatch: CallCameraStateObservation? = null
        val watch = CallCameraStateObservation(states, owner, { true }) {
            errors++
            checkNotNull(ownedWatch).close()
        }
        // The engine must own the observer before start(), since observe() dispatches synchronously.
        ownedWatch = watch
        try {
            watch.start()
            watch.start()
            assertEquals(1, errors)
            assertFalse(states.hasObservers())
        } finally { watch.close(); owner.destroy() }
    }

    @Test fun staleGenerationCannotCloseTheReplacementCamera() = onMain {
        val owner = TestOwner()
        val gate = CallCameraSessionGate()
        val oldToken = checkNotNull(gate.begin())
        val oldStates = MutableLiveData(CameraState.create(CameraState.Type.OPEN))
        val newStates = MutableLiveData(CameraState.create(CameraState.Type.OPEN))
        var oldErrors = 0
        var newErrors = 0
        val oldWatch = CallCameraStateObservation(oldStates, owner, { gate.live(oldToken) }) { oldErrors++ }
        oldWatch.start()
        gate.pause()
        val newToken = checkNotNull(gate.begin())
        val newWatch = CallCameraStateObservation(newStates, owner, { gate.live(newToken) }) { newErrors++ }
        try {
            newWatch.start()
            oldStates.value = failedState()
            assertEquals(0, oldErrors)
            assertEquals(0, newErrors)
            assertTrue(newStates.hasObservers())
            newStates.value = failedState()
            assertEquals(1, newErrors)
        } finally { oldWatch.close(); newWatch.close(); owner.destroy() }
    }

    @Test fun explicitStopRemovesObserverAndPreventsLateErrorsOrRestart() = onMain {
        val owner = TestOwner()
        val states = MutableLiveData(CameraState.create(CameraState.Type.OPEN))
        var errors = 0
        val watch = CallCameraStateObservation(states, owner, { true }) { errors++ }
        try {
            watch.start()
            watch.close()
            watch.close()
            states.value = failedState()
            watch.start()
            assertEquals(0, errors)
            assertFalse(states.hasObservers())
        } finally { watch.close(); owner.destroy() }
    }

    @Test fun destroyedLifecycleAutomaticallyReleasesObserver() = onMain {
        val owner = TestOwner()
        val states = MutableLiveData(CameraState.create(CameraState.Type.OPEN))
        var errors = 0
        val watch = CallCameraStateObservation(states, owner, { true }) { errors++ }
        try {
            watch.start()
            owner.destroy()
            assertFalse(states.hasObservers())
            states.value = failedState()
            assertEquals(0, errors)
        } finally { watch.close() }
    }

    private fun failedState() = CameraState.create(
        CameraState.Type.CLOSED,
        CameraState.StateError.create(CameraState.ERROR_CAMERA_FATAL_ERROR),
    )

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private class TestOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        init { registry.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}
