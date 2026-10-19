package com.zhiyu.app.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RuntimeClockPolicyTest {
    private val device = Instant.parse("2026-10-19T01:00:00Z")
    @Test fun aNetworkClockEightHoursAwayCannotOverrideTheUsersVisiblePhoneClock() {
        assertEquals(device, RuntimeClockPolicy.select(device, device.minusSeconds(28800)))
        assertEquals(device, RuntimeClockPolicy.select(device, device.plusSeconds(28800)))
    }
    @Test fun aCloseNetworkFixAndNoFixAreBothSupported() {
        assertEquals(device.plusSeconds(1), RuntimeClockPolicy.select(device, device.plusSeconds(1)))
        assertEquals(device, RuntimeClockPolicy.select(device, null))
    }
}
