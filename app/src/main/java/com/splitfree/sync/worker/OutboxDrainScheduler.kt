package com.splitfree.sync.worker

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Seam through which [com.splitfree.sync.event.EventPublisher] asks for an [OutboxWorker] run after
 * a commit, so the publisher can be tested without WorkManager. Requesting is best-effort: a run is
 * scheduled, not a publish; the caller must already have committed the rows.
 */
fun interface OutboxDrainScheduler {
    fun requestDrain()
}

class WorkManagerOutboxDrainScheduler
@Inject
constructor(@ApplicationContext private val context: Context) :
    OutboxDrainScheduler {
    override fun requestDrain() = SyncScheduler.scheduleOutboxDrain(context)
}
