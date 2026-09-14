package com.splitfree.sync.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.splitfree.util.DebugLog as Log

/**
 * Schedules sync on device boot if the user has an active identity.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Only schedule sync if the user has set up an identity
            val hasIdentity =
                try {
                    context.getSharedPreferences("splitfree_boot", Context.MODE_PRIVATE)
                        .getBoolean("identity_created", false)
                } catch (_: Exception) {
                    false
                }
            if (!hasIdentity) return

            try {
                // Nothing is visible after boot, so the live session cannot run; a one-time worker catches up.
                SyncScheduler.scheduleImmediateSync(context)
            } catch (e: Exception) {
                Log.w("BootReceiver", "Could not schedule boot sync: ${e.message}")
            }
        }
    }
}
