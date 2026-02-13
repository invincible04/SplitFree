package com.splitfree.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Only start sync service if user has set up identity
            val hasIdentity = try {
                java.io.File(context.filesDir.parent, "shared_prefs/splitfree_identity.xml").exists()
            } catch (_: Exception) { false }
            if (!hasIdentity) return

            val serviceIntent = Intent(context, ForegroundSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
