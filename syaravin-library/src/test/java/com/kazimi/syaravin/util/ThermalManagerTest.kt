package com.kazimi.syaravin.util

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalManagerTest {
    @Test
    fun `inference interval increases with thermal pressure`() {
        assertEquals(500L, ThermalManager.inferenceIntervalMs(500L, PowerManager.THERMAL_STATUS_NONE))
        assertEquals(750L, ThermalManager.inferenceIntervalMs(500L, PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(1_000L, ThermalManager.inferenceIntervalMs(500L, PowerManager.THERMAL_STATUS_MODERATE))
    }

    @Test
    fun `severe thermal status throttles without blocking scanner`() {
        assertEquals(2_000L, ThermalManager.inferenceIntervalMs(500L, PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(3_000L, ThermalManager.inferenceIntervalMs(500L, PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals(5_000L, ThermalManager.inferenceIntervalMs(500L, PowerManager.THERMAL_STATUS_EMERGENCY))
    }
}
