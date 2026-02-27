package com.splitfree.sync.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Restarts sync service on device boot if the user has an active identity.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Only start sync service if user has set up identity
            val hasIdentity =
                try {
                    context.getSharedPreferences("splitfree_boot", Context.MODE_PRIVATE)
                        .getBoolean("identity_created", false)
                } catch (_: Exception) {
                    false
                }
            if (!hasIdentity) return

            val serviceIntent = Intent(context, ForegroundSyncService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException on Android 12+
            }
        }
    }
}
