package com.zhiyu.app.data

import android.os.Build
import android.os.SystemClock
import java.time.DateTimeException
import java.time.Instant

/** Reads Android's cached network clock without making a request to an IP-location vendor. */
internal object PhoneClock {
    fun now(): Instant {
        if (Build.VERSION.SDK_INT >= 33) {
            try { return SystemClock.currentNetworkTimeClock().instant() }
            catch (_: DateTimeException) { /* Android has no network fix yet; use the current device clock. */ }
        }
        return Instant.now()
    }
}
