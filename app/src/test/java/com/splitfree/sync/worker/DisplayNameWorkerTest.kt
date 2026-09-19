package com.splitfree.sync.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.splitfree.domain.repository.DisplayNameDelivery
import com.splitfree.domain.repository.DisplayNamePublishResult
import com.splitfree.domain.usecase.group.DisplayNamePublisher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DisplayNameWorkerTest {
    private val publisher = mockk<DisplayNamePublisher>()
    private val params = mockk<WorkerParameters>(relaxed = true)
    private fun worker() = DisplayNameWorker(mockk<Context>(), params, publisher)

    @Test fun `durably queued publication completes worker without claiming relay acknowledgment`() = runBlocking {
        coEvery { publisher.drain() } returns DisplayNamePublishResult(mapOf("g" to DisplayNameDelivery.DURABLY_QUEUED))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }

    @Test fun `partial outcome retries and exhausted request leaves periodic recovery responsible`() = runBlocking {
        coEvery { publisher.drain() } returns DisplayNamePublishResult(deferredGroups = setOf("g"))
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        every { params.runAttemptCount } returns 10
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
    }

    @Test fun `storage or identity transition failure retries without dropping intent`() = runBlocking {
        coEvery { publisher.drain() } throws IllegalStateException("identity transition")
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
    }

    @Test fun `cancellation propagates and does not acknowledge the intent`() {
        coEvery { publisher.drain() } throws CancellationException("worker stopped")
        assertThrows(CancellationException::class.java) { runBlocking { worker().doWork() } }
    }
}
