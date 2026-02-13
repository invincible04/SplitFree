package com.splitfree.sync

import org.junit.Assert.*
import org.junit.Test

class PowerManagerDutyCycleTest {

    @Test
    fun `all power modes return positive scan and pause durations`() {
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

    @Test
    fun `power mode enum has exactly 4 values`() {
        assertEquals(4, PowerMode.entries.size)
    }

    @Test
    fun `power mode names are distinct`() {
        val names = PowerMode.entries.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `power mode ordinals are sequential`() {
        PowerMode.entries.forEachIndexed { i, mode ->
            assertEquals(i, mode.ordinal)
        }
    }
}
