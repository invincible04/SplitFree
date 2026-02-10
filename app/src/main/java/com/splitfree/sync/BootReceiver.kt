package com.splitfree.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // WorkManager periodic sync survives reboot automatically.
            // Optionally start ForegroundSyncService here if user enabled it.
        }
    }
}
