package com.splitfree.sync.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.domain.model.sync.FlushResult
import com.splitfree.domain.repository.IdentityContract
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class OutboxWorkerTest {
    private val identity = mockk<IdentityContract> { every { hasIdentity() } returns true }
    private val nostrClient = mockk<NostrClient>(relaxed = true)
    private val relays = mockk<RelayConnectionManager> { coEvery { ensureConnected(any()) } returns emptyList() }
    private val syncEngine = mockk<SyncEngine> {
        coEvery { hasDueOutbox() } returns true
        coEvery { flushOutbox() } returns FlushResult(published = 1, failed = 0)
    }
    private val params = mockk<WorkerParameters>(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() = unmockkStatic(android.util.Log::class)

    private fun worker() = OutboxWorker(mockk<Context>(), params, identity, nostrClient, relays, syncEngine)

    @Test
    fun `without identity nothing is read or connected`() = runBlocking {
        every { identity.hasIdentity() } returns false

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        coVerify(exactly = 0) { syncEngine.hasDueOutbox() }
        coVerify(exactly = 0) { relays.ensureConnected(any()) }
    }

    @Test
    fun `with no due row it succeeds without connecting`() = runBlocking {
        coEvery { syncEngine.hasDueOutbox() } returns false

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        coVerify(exactly = 0) { relays.ensureConnected(any()) }
        coVerify(exactly = 0) { syncEngine.flushOutbox() }
        verify(exactly = 0) { nostrClient.releaseConnection() }
    }

    @Test
    fun `a clean flush succeeds and releases the connection`() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        coVerify(exactly = 1) { relays.ensureConnected() }
        coVerify(exactly = 1) { syncEngine.flushOutbox() }
        verify(exactly = 1) { nostrClient.releaseConnection() }
    }

    @Test
    fun `a failed row retries until the run budget is spent`() = runBlocking {
        coEvery { syncEngine.flushOutbox() } returns FlushResult(published = 2, failed = 1)

        every { params.runAttemptCount } returns 0
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        every { params.runAttemptCount } returns OutboxWorker.RUN_RETRY_BUDGET - 1
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        every { params.runAttemptCount } returns OutboxWorker.RUN_RETRY_BUDGET
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())

        verify(exactly = 3) { nostrClient.releaseConnection() }
    }

    @Test
    fun `a connection failure retries without releasing a lease it never took`() = runBlocking {
        coEvery { relays.ensureConnected(any()) } throws java.io.IOException("offline")

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())

        verify(exactly = 0) { nostrClient.releaseConnection() }
    }

    @Test
    fun `cancellation propagates instead of becoming a retry`() = runBlocking {
        coEvery { syncEngine.flushOutbox() } throws CancellationException("worker stopped")

        assertThrows(CancellationException::class.java) { runBlocking { worker().doWork() } }

        verify(exactly = 1) { nostrClient.releaseConnection() }
    }
}
