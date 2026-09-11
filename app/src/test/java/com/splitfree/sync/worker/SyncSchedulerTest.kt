package com.splitfree.sync.worker

import android.app.Application
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SyncSchedulerTest {
    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `connectivity and boot sync is coalesced network constrained work`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = mockk<WorkManager>(relaxed = true)
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(context) } returns manager
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
}
