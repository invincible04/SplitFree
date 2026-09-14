package com.splitfree.sync.worker

import android.app.Application
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SyncSchedulerTest {
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun mockWorkManager(): WorkManager {
        val manager = mockk<WorkManager>(relaxed = true)
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(context) } returns manager
        return manager
    }

    @Test
    fun `connectivity and boot sync is coalesced network constrained work`() {
        val manager = mockWorkManager()
        val request = slot<OneTimeWorkRequest>()
        every {
            manager.enqueueUniqueWork("splitfree_immediate_sync", ExistingWorkPolicy.KEEP, capture(request))
        } returns mockk(relaxed = true)

        SyncScheduler.scheduleImmediateSync(context)
        SyncScheduler.scheduleImmediateSync(context)

        verify(exactly = 2) {
            manager.enqueueUniqueWork("splitfree_immediate_sync", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>())
        }
        assertEquals(SyncWorker::class.java.name, request.captured.workSpec.workerClassName)
        assertEquals(NetworkType.CONNECTED, request.captured.workSpec.constraints.requiredNetworkType)
        assertFalse(request.captured.workSpec.expedited)
    }

    @Test
    fun `outbox drain is appended network constrained non-expedited work with exponential back-off`() {
        val manager = mockWorkManager()
        val request = slot<OneTimeWorkRequest>()
        every {
            manager.enqueueUniqueWork(
                SyncScheduler.OUTBOX_DRAIN_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                capture(request)
            )
        } returns mockk(relaxed = true)

        SyncScheduler.scheduleOutboxDrain(context)

        assertEquals("splitfree_outbox_drain", SyncScheduler.OUTBOX_DRAIN_WORK)
        assertEquals(OutboxWorker::class.java.name, request.captured.workSpec.workerClassName)
        assertEquals(NetworkType.CONNECTED, request.captured.workSpec.constraints.requiredNetworkType)
        assertFalse(request.captured.workSpec.expedited)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.captured.workSpec.backoffPolicy)
        assertEquals(30_000L, request.captured.workSpec.backoffDelayDuration)
    }

    @Test
    fun `periodic sync runs at the given interval and updates the existing schedule in place`() {
        val manager = mockWorkManager()
        val request = slot<PeriodicWorkRequest>()
        every {
            manager.enqueueUniquePeriodicWork(
                "splitfree_periodic_sync",
                ExistingPeriodicWorkPolicy.UPDATE,
                capture(request)
            )
        } returns mockk(relaxed = true)

        SyncScheduler.schedulePeriodicSync(context, Duration.ofMinutes(15))

        assertEquals(SyncWorker::class.java.name, request.captured.workSpec.workerClassName)
        assertEquals(Duration.ofMinutes(15).toMillis(), request.captured.workSpec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, request.captured.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `daily sync is a kept 24h periodic job whose first run is within the next day`() {
        val manager = mockWorkManager()
        val request = slot<PeriodicWorkRequest>()
        every {
            manager.enqueueUniquePeriodicWork(
                SyncScheduler.DAILY_SYNC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                capture(request)
            )
        } returns mockk(relaxed = true)

        SyncScheduler.scheduleDailySync(context)

        assertEquals("splitfree_daily_sync", SyncScheduler.DAILY_SYNC_WORK)
        assertEquals(DailySyncWorker::class.java.name, request.captured.workSpec.workerClassName)
        assertEquals(Duration.ofHours(24).toMillis(), request.captured.workSpec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, request.captured.workSpec.constraints.requiredNetworkType)
        val initialDelay = request.captured.workSpec.initialDelay
        assertTrue("initial delay $initialDelay", initialDelay > 0 && initialDelay <= Duration.ofHours(24).toMillis())
    }

    @Test
    fun `untilNextLocalMidnight follows the local clock across DST changes`() {
        val berlin = ZoneId.of("Europe/Berlin")

        assertEquals(
            Duration.ofHours(2).plusMinutes(30),
            SyncScheduler.untilNextLocalMidnight(ZonedDateTime.of(2026, 6, 10, 21, 30, 0, 0, berlin))
        )
        // Spring forward (Sun 29 Mar 2026, 02:00 -> 03:00): that day is 23 real hours long.
        assertEquals(
            Duration.ofHours(23),
            SyncScheduler.untilNextLocalMidnight(ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, berlin))
        )
        // Fall back (Sun 25 Oct 2026, 03:00 -> 02:00): that day is 25 real hours long.
        assertEquals(
            Duration.ofHours(25),
            SyncScheduler.untilNextLocalMidnight(ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, berlin))
        )
        assertEquals(
            Duration.ofHours(24),
            SyncScheduler.untilNextLocalMidnight(ZonedDateTime.of(2026, 6, 10, 0, 0, 0, 0, berlin))
        )
    }
}
