package com.zhiyu.app.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import java.util.concurrent.atomic.AtomicInteger

class CareCancellationTest {
    private val context = CareTestContext(InstrumentationRegistry.getInstrumentation().targetContext)
    @After fun cleanup() { context.cleanup() }
    private fun snapshot(): WeatherSnapshot {
        val now = System.currentTimeMillis() / 1000
        return WeatherSnapshot(now - 60, now, 21.0, 20.0, 0, 0.0, 5.0)
    }

    @Test fun deniedPermissionCannotStartLocationOrEnableWeather() = runBlocking {
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) {
            CareController(context, scope, NativeWeatherService { calls.incrementAndGet(); snapshot() },
                CareLocationSource { calls.incrementAndGet(); ApproximateLocation(30.0, 120.0) }, { false })
        }
        try {
            withContext(Dispatchers.Main) { controller.enable() }
            assertFalse(controller.state.enabled)
            assertEquals(0, calls.get())
            assertTrue(controller.error!!.contains("聊天仍可正常使用"))
        } finally { scope.cancel(); CarePreferences(context).save(CarePreferenceState()) }
    }

    @Test fun disablingWeatherCannotBeUndoneByALateNetworkResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<WeatherSnapshot>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) {
            CareController(context, scope, NativeWeatherService {
                started.complete(Unit)
                withContext(NonCancellable) { answer.await() }
            }, CareLocationSource { ApproximateLocation(30.0, 120.0) }, { true })
        }
        try {
            withContext(Dispatchers.Main) { controller.enable() }
            withTimeout(3000) { started.await() }
            withContext(Dispatchers.Main) { controller.disable() }
            answer.complete(snapshot())
            withTimeout(3000) {
                while (CarePreferences(context).load().state.enabled) delay(10)
            }
            assertFalse(controller.state.enabled)
            assertNull(controller.state.weather)
            assertNull(CarePreferences(context).load().state.weather)
            assertFalse(controller.busy)
        } finally { answer.complete(snapshot()); scope.cancel(); CarePreferences(context).save(CarePreferenceState()) }
    }

    @Test fun enteringBackgroundCancelsThePendingRefreshWithoutSavingItsLateResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<WeatherSnapshot>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) {
            CareController(context, scope, NativeWeatherService {
                started.complete(Unit)
                withContext(NonCancellable) { answer.await() }
            }, CareLocationSource { ApproximateLocation(30.0, 120.0) }, { true })
        }
        try {
            withContext(Dispatchers.Main) { controller.enable() }
            withTimeout(3000) { started.await() }
            withContext(Dispatchers.Main) { controller.cancelPendingLocation() }
            answer.complete(snapshot())
            delay(100)
            assertTrue(controller.state.enabled)
            assertFalse(controller.busy)
            assertNull(controller.state.weather)
            assertNull(CarePreferences(context).load().state.weather)
        } finally { answer.complete(snapshot()); scope.cancel(); CarePreferences(context).save(CarePreferenceState()) }
    }

    @Test fun revokedPermissionDisablesAndClearsThePreviouslyAcceptedWeather() = runBlocking {
        var allowed = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = withContext(Dispatchers.Main) {
            CareController(context, scope, NativeWeatherService { snapshot() },
                CareLocationSource { ApproximateLocation(30.0, 120.0) }, { allowed })
        }
        try {
            withContext(Dispatchers.Main) { controller.enable() }
            withTimeout(3000) { while (controller.state.weather == null) delay(10) }
            withContext(Dispatchers.Main) { allowed = false; controller.onResume() }
            withTimeout(3000) { while (CarePreferences(context).load().state.enabled) delay(10) }
            assertFalse(controller.state.enabled)
            assertNull(controller.state.weather)
            assertNull(CarePreferences(context).load().state.weather)
        } finally { scope.cancel(); CarePreferences(context).save(CarePreferenceState()) }
    }
}
