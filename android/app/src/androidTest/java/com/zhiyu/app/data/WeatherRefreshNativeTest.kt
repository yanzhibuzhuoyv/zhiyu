package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WeatherRefreshNativeTest {
    @Test fun publishingAndAnExplicitDisableShareOneAtomicBoundary(): Unit = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val store = CarePreferences(context); store.save(CarePreferenceState(true))
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val now = System.currentTimeMillis() / 1000
            val snapshot = WeatherSnapshot(now, now, 12.0, 10.0, 61, 0.2, 9.0)
            val publisher = async(Dispatchers.IO) { store.publishWeatherIfEnabled(snapshot, now) {
                entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); true
            } }
            try {
                withContext(Dispatchers.IO) { check(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)) }
                val disable = async(Dispatchers.IO) { CarePreferences(context).save(CarePreferenceState(false)) }
                delay(100)
                assertFalse("Disable must not interleave between permission check and publication", disable.isCompleted)
                release.countDown(); publisher.await(); disable.await()
                assertFalse(store.load().state.enabled); assertNull(store.load().state.weather)
            } finally { release.countDown(); publisher.await() }
        }
    }
    @Test fun anAuthorizedForegroundWeatherQuestionRefreshesRealFactsAndRechecksRevocation(): Unit = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val store = CarePreferences(context); store.save(CarePreferenceState(true))
            var allowed = true; var calls = 0
            val now = System.currentTimeMillis() / 1000
            val expected = WeatherSnapshot(now, now, 12.0, 10.0, 61, 0.2, 9.0)
            val refresh = NativeWeatherRefresh(context, CareLocationSource { ApproximateLocation(39.9, 116.4) },
                NativeWeatherService { calls++; expected }, { allowed }, { true }, { now })
            refresh.forQuestion("我这边天气怎么样")
            assertEquals(1, calls); assertEquals(expected, store.load().state.weather)
            refresh.forQuestion("今天多少度"); assertEquals(1, calls)
            store.save(CarePreferenceState(true))
            val revoked = NativeWeatherRefresh(context, CareLocationSource { allowed = false; ApproximateLocation(39.9, 116.4) },
                NativeWeatherService { calls++; expected }, { allowed }, { true }, { now })
            revoked.forQuestion("外面下雨了吗")
            assertEquals(1, calls); assertNull(store.load().state.weather)
        }
    }
    @Test fun noPermissionNoOptInBackgroundAndOrdinaryTextCannotRequestALocation(): Unit = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val store = CarePreferences(context)
            var allowed = true; var foreground = true; var locations = 0
            val refresh = NativeWeatherRefresh(context, CareLocationSource { locations++; error("No location expected") },
                NativeWeatherService { error("No weather expected") }, { allowed }, { foreground })
            store.save(CarePreferenceState(false)); refresh.forQuestion("我这边天气怎么样")
            store.save(CarePreferenceState(true)); allowed = false; refresh.forQuestion("我这边天气怎么样")
            allowed = true; foreground = false; refresh.forQuestion("我这边天气怎么样")
            foreground = true; refresh.forQuestion("我想买本书")
            assertEquals(0, locations)
        }
    }
    @Test fun failureDoesNotInventWeatherAndCancellationIsPreserved(): Unit = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val store = CarePreferences(context); store.save(CarePreferenceState(true))
            val refresh = NativeWeatherRefresh(context, CareLocationSource { throw IllegalStateException("synthetic unavailable") },
                NativeWeatherService { error("No weather") }, { true }, { true })
            refresh.forQuestion("我这边天气怎么样"); assertNull(store.load().state.weather)
            val cancelled = NativeWeatherRefresh(context, CareLocationSource { throw CancellationException("synthetic cancel") },
                NativeWeatherService { error("No weather") }, { true }, { true })
            val failure = runCatching { cancelled.forQuestion("我这边天气怎么样") }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        }
    }
}
