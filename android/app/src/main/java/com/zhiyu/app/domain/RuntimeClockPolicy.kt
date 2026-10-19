package com.zhiyu.app.domain

import java.time.Instant

object RuntimeClockPolicy {
    fun select(device: Instant, network: Instant?): Instant = network?.takeIf {
        kotlin.math.abs(java.time.Duration.between(device, it).seconds) <= 120
    } ?: device
}
