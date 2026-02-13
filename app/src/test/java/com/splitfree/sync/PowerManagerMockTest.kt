package com.splitfree.sync

import android.content.Context
import android.os.BatteryManager
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PowerManagerTest {

    private val context = mockk<Context>()
    private val batteryManager = mockk<BatteryManager>()
    private lateinit var pm: PowerManager

    @Before
    fun setup() {
        every { context.getSystemService(Context.BATTERY_SERVICE) } returns batteryManager
        pm = PowerManager(context)
    }

    @Test
    fun `PERFORMANCE when charging`() {
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 50
        every { batteryManager.isCharging } returns true
        assertEquals(PowerMode.PERFORMANCE, pm.currentMode())
    }

    @Test
    fun `ULTRA_LOW_POWER at 10 percent`() {
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 10
        every { batteryManager.isCharging } returns false
        assertEquals(PowerMode.ULTRA_LOW_POWER, pm.currentMode())
    }

    @Test
    fun `ULTRA_LOW_POWER at 5 percent`() {
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 5
        every { batteryManager.isCharging } returns false
        assertEquals(PowerMode.ULTRA_LOW_POWER, pm.currentMode())
    }

    @Test
    fun `POWER_SAVER at 30 percent`() {
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 30
        every { batteryManager.isCharging } returns false
        assertEquals(PowerMode.POWER_SAVER, pm.currentMode())
    }

    @Test
    fun `POWER_SAVER at 15 percent`() {
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 15
        every { batteryManager.isCharging } returns false
        assertEquals(PowerMode.POWER_SAVER, pm.currentMode())
    }

    @Test
    fun `BALANCED at 50 percent`() {
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 50
        every { batteryManager.isCharging } returns false
        assertEquals(PowerMode.BALANCED, pm.currentMode())
    }

    @Test
    fun `BALANCED at 100 percent not charging`() {
        every { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 100
        every { batteryManager.isCharging } returns false
        assertEquals(PowerMode.BALANCED, pm.currentMode())
    }

    @Test
    fun `syncIntervalHours varies by mode`() {
        every { batteryManager.isCharging } returns true
        every { batteryManager.getIntProperty(any()) } returns 100
        assertEquals(1L, pm.syncIntervalHours())

        every { batteryManager.isCharging } returns false
        every { batteryManager.getIntProperty(any()) } returns 50
        assertEquals(6L, pm.syncIntervalHours())

        every { batteryManager.getIntProperty(any()) } returns 20
        assertEquals(12L, pm.syncIntervalHours())

        every { batteryManager.getIntProperty(any()) } returns 5
        assertEquals(24L, pm.syncIntervalHours())
    }

    @Test
    fun `maxRelayConnections varies by mode`() {
        every { batteryManager.isCharging } returns true
        every { batteryManager.getIntProperty(any()) } returns 100
        assertEquals(8, pm.maxRelayConnections())

        every { batteryManager.isCharging } returns false
        every { batteryManager.getIntProperty(any()) } returns 20
        assertEquals(2, pm.maxRelayConnections())

        every { batteryManager.getIntProperty(any()) } returns 5
        assertEquals(1, pm.maxRelayConnections())
    }

    @Test
    fun `bleScanDuty varies by mode`() {
        every { batteryManager.isCharging } returns true
        every { batteryManager.getIntProperty(any()) } returns 100
        assertEquals(10_000L to 2_000L, pm.bleScanDuty())

        every { batteryManager.isCharging } returns false
        every { batteryManager.getIntProperty(any()) } returns 50
        assertEquals(5_000L to 5_000L, pm.bleScanDuty())

        every { batteryManager.getIntProperty(any()) } returns 20
        assertEquals(3_000L to 10_000L, pm.bleScanDuty())

        every { batteryManager.getIntProperty(any()) } returns 5
        assertEquals(2_000L to 15_000L, pm.bleScanDuty())
    }

    @Test
    fun `PowerMode enum has 4 values`() {
        assertEquals(4, PowerMode.entries.size)
    }
}
