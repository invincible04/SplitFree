package com.splitfree.sync.worker

import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.sync.FlushResult
import com.splitfree.domain.model.sync.PullResult
import com.splitfree.domain.repository.IdentityContract
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SyncWorkerTest {
    private val groupRepo = mockk<GroupRepository>()
    private val nostrClient = mockk<NostrClient>(relaxed = true)
    private val identity = mockk<IdentityContract> { every { hasIdentity() } returns true }
    private val relays = mockk<RelayConnectionManager> { coEvery { ensureConnected(any()) } returns emptyList() }
    private val syncEngine = mockk<SyncEngine> {
        coEvery { flushOutbox() } returns FlushResult(published = 0, failed = 0)
        coEvery { pullEvents(any(), any(), any(), any(), any()) } returns PullResult(stored = 0, complete = true)
    }
    private val params = mockk<WorkerParameters>(relaxed = true)

    private fun group(id: String, lastSync: Long = 0) =
        Group(id, id, createdBy = "alice", createdAt = 1, members = listOf("alice"), relays = emptyList()).also {
            coEvery { groupRepo.getGroupEntity(id) } returns
                GroupEntity(
                    id,
                    id,
                    createdBy = "alice",
                    createdAt = 1,
                    members = "[]",
                    relays = "[]",
                    lastSyncTimestamp = lastSync
                )
            coEvery { groupRepo.getGroupKey(id) } returns "key-$id"
        }

    private fun worker() = SyncWorker(
        RuntimeEnvironment.getApplication(),
        params,
        groupRepo,
        nostrClient,
        identity,
        relays,
        syncEngine
    )

    @Test
    fun `flush then incremental pull of every keyed group succeeds when all clean`() = runBlocking {
        val fresh = group("fresh")
        val synced = group("synced", lastSync = 10_000)
        val keyless = group("keyless")
        coEvery { groupRepo.getGroupKey("keyless") } returns null
        coEvery { groupRepo.getAll() } returns listOf(fresh, synced, keyless)

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { syncEngine.flushOutbox() }
        coVerify(exactly = 1) { syncEngine.pullEvents("fresh", 0, "key-fresh", false, any()) }
        coVerify(exactly = 1) { syncEngine.pullEvents("synced", 10_000 - 3600, "key-synced", false, any()) }
        coVerify(exactly = 0) { syncEngine.pullEvents("keyless", any(), any(), any(), any()) }
        verify(exactly = 1) { nostrClient.releaseConnection() }
    }

    @Test
    fun `a failed outbox row makes the run retry`() = runBlocking {
        coEvery { groupRepo.getAll() } returns listOf(group("g"))
        coEvery { syncEngine.flushOutbox() } returns FlushResult(published = 1, failed = 1)

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 1) { syncEngine.pullEvents("g", any(), any(), any(), any()) }
        verify(exactly = 1) { nostrClient.releaseConnection() }
    }

    @Test
    fun `an incomplete pull makes the run retry after the remaining groups were still pulled`() = runBlocking {
        val partial = group("partial")
        val other = group("other")
        coEvery { groupRepo.getAll() } returns listOf(partial, other)
        coEvery { syncEngine.pullEvents("partial", any(), any(), any(), any()) } returns
            PullResult(stored = 4, complete = false)

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 1) { syncEngine.pullEvents("other", any(), any(), any(), any()) }
    }

    @Test
    fun `retries are bounded and end in failure`() = runBlocking {
        coEvery { groupRepo.getAll() } returns listOf(group("g"))
        coEvery { syncEngine.flushOutbox() } returns FlushResult(published = 0, failed = 1)

        every { params.runAttemptCount } returns SyncWorker.RUN_RETRY_BUDGET - 1
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        every { params.runAttemptCount } returns SyncWorker.RUN_RETRY_BUDGET
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
    }

    @Test
    fun `a dependency failure follows the same retry rule`() = runBlocking {
        coEvery { groupRepo.getAll() } returns listOf(group("g"))
        coEvery { syncEngine.pullEvents(any(), any(), any(), any(), any()) } throws IllegalStateException("disk full")

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        every { params.runAttemptCount } returns SyncWorker.RUN_RETRY_BUDGET
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
        verify(exactly = 2) { nostrClient.releaseConnection() }
    }

    @Test
    fun `cancellation propagates instead of becoming a retry or failure`() = runBlocking {
        coEvery { groupRepo.getAll() } returns listOf(group("g"))
        coEvery { syncEngine.pullEvents(any(), any(), any(), any(), any()) } throws
            CancellationException("worker stopped")

        assertThrows(CancellationException::class.java) { runBlocking { worker().doWork() } }

        verify(exactly = 1) { nostrClient.releaseConnection() }
    }

    @Test
    fun `without identity nothing connects`() = runBlocking {
        every { identity.hasIdentity() } returns false

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        coVerify(exactly = 0) { relays.ensureConnected(any()) }
        verify(exactly = 0) { nostrClient.releaseConnection() }
    }
}
