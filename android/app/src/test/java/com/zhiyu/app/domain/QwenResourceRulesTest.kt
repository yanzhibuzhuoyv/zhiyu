package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class QwenResourceRulesTest {
    private val healthy = QwenResources(350, 0, 6000 * QwenResourceRules.MIB, false, 1000 * QwenResourceRules.MIB)
    @Test fun eachStageRequiresItsOwnMeasuredMemoryBudget() {
        assertTrue(QwenResourceRules.canStart("sample", healthy))
        val limited = healthy.copy(availableMemory = 3500 * QwenResourceRules.MIB)
        assertFalse(QwenResourceRules.canStart("sample", limited))
        assertTrue(QwenResourceRules.canStart("decode", limited))
    }
    @Test fun unknownThermalOrBatteryNeverCountsAsHealthy() {
        listOf(healthy.copy(thermalStatus = null), healthy.copy(temperatureDeciC = null),
            healthy.copy(temperatureDeciC = 0)).forEach {
            assertFalse(QwenResourceRules.canStart("text", it)); assertTrue(QwenResourceRules.mustStop(it))
        }
    }
    @Test fun runningJobsStopForHeatPressureOrStorageExhaustion() {
        listOf(healthy.copy(temperatureDeciC = 431), healthy.copy(thermalStatus = 2),
            healthy.copy(lowMemory = true), healthy.copy(freeStorage = 63 * QwenResourceRules.MIB)).forEach {
            assertTrue(QwenResourceRules.mustStop(it))
        }
        assertFalse(QwenResourceRules.mustStop(healthy))
    }
}
