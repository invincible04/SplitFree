package com.splitfree.sync.worker

import android.content.Context
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Battery state tiers for adaptive sync intervals and resource usage.
 */
enum class PowerMode { PERFORMANCE, BALANCED, POWER_SAVER, ULTRA_LOW_POWER }

/**
 * Supplies battery-based policy values; callers apply the sync interval through WorkManager.
 *
 * - Nearby discovery is controlled by the screen-owned run, not by this battery policy.
 */
@Singleton
class PowerManager
@Inject
constructor(@ApplicationContext private val context: Context) {
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    /**
     * @return current power mode based on battery level and charging state
     */
    fun currentMode(): PowerMode {
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = batteryManager.isCharging
        return when {
            charging -> PowerMode.PERFORMANCE
            level <= 10 -> PowerMode.ULTRA_LOW_POWER
            level <= 30 -> PowerMode.POWER_SAVER
            else -> PowerMode.BALANCED
        }
    }

    /**
     * @return periodic background sync interval for the current power mode. 15 minutes is
     *   WorkManager's minimum period; Android may still defer or batch the run.
     */
    fun syncInterval(): Duration = when (currentMode()) {
        PowerMode.PERFORMANCE, PowerMode.BALANCED -> Duration.ofMinutes(15)
        PowerMode.POWER_SAVER -> Duration.ofMinutes(60)
        PowerMode.ULTRA_LOW_POWER -> Duration.ofHours(6)
    }

    /** Suggested relay connection cap; no production caller currently applies it to WebSocket connections. */
    fun maxRelayConnections(): Int = when (currentMode()) {
        PowerMode.PERFORMANCE, PowerMode.BALANCED -> 8
        PowerMode.POWER_SAVER -> 2
        PowerMode.ULTRA_LOW_POWER -> 1
    }
}
