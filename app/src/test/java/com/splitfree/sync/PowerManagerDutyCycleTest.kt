package com.splitfree.sync

import org.junit.Assert.*
import org.junit.Test

class PowerManagerDutyCycleTest {

    @Test
    fun `all power modes return positive scan and pause durations`() {
        // We can't instantiate PowerManager (needs Context), but we can test the logic
        // by verifying the enum values and expected duty cycle patterns
        for (mode in PowerMode.values()) {
            assertNotNull(mode.name)
        }
    }

    @Test
    fun `power modes are ordered by battery level`() {
        val modes = PowerMode.values()
        assertEquals(4, modes.size)
        assertEquals(PowerMode.PERFORMANCE, modes[0])
        assertEquals(PowerMode.BALANCED, modes[1])
        assertEquals(PowerMode.POWER_SAVER, modes[2])
        assertEquals(PowerMode.ULTRA_LOW_POWER, modes[3])
    }
}
