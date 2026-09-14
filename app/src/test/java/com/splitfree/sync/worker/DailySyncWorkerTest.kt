package com.splitfree.sync.worker

import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.sync.FlushResult
import com.splitfree.domain.model.sync.PullResult
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.BalanceUnavailableException
import com.splitfree.domain.usecase.expense.CreateSnapshotUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
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
class DailySyncWorkerTest {
    private val outboxDao = mockk<OutboxDao>(relaxed = true)
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val nostrClient = mockk<NostrClient>(relaxed = true)
    private val identity = mockk<IdentityContract> { every { hasIdentity() } returns true }
    private val createSnapshot = mockk<CreateSnapshotUseCase> { coEvery { this@mockk.invoke(any()) } returns true }
    private val selfHeal = mockk<SelfHealUseCase> { coEvery { this@mockk.invoke(any()) } returns 0 }
    private val relays = mockk<RelayConnectionManager> { coEvery { ensureConnected(any()) } returns emptyList() }
    private val syncEngine = mockk<SyncEngine> {
        coEvery { flushOutbox() } returns FlushResult(published = 0, failed = 0)
        coEvery { pullEvents(any(), any(), any(), any()) } returns PullResult(stored = 0, complete = true)
    }
    private val params = mockk<WorkerParameters>(relaxed = true)

    private fun group(id: String) =
        Group(id, id, createdBy = "alice", createdAt = 1, members = listOf("alice"), relays = emptyList()).also {
            coEvery { groupRepo.getGroupKey(id) } returns "key-$id"
        }

    private fun worker() = DailySyncWorker(
        RuntimeEnvironment.getApplication(),
        params,
        outboxDao,
        groupRepo,
        nostrClient,
        identity,
        createSnapshot,
        selfHeal,
        relays,
        syncEngine
    )

    @Test
    fun `a full run reconnects, pulls all history, heals, snapshots and trims the outbox`() = runBlocking {
        coEvery { groupRepo.getAll() } returns listOf(group("a"), group("b"))

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { relays.ensureConnected(forceReconnect = true) }
        coVerify(exactly = 1) { syncEngine.flushOutbox() }
        coVerify(exactly = 1) { syncEngine.pullEvents("a", 0, "key-a", lenientTimestamp = true) }
        coVerify(exactly = 1) { syncEngine.pullEvents("b", 0, "key-b", lenientTimestamp = true) }
        coVerify(exactly = 1) { selfHeal("a") }
        coVerify(exactly = 1) { selfHeal("b") }
        coVerify(exactly = 1) { createSnapshot("a") }
        coVerify(exactly = 1) { createSnapshot("b") }
        coVerify(exactly = 1) { outboxDao.deleteOlderThan(any()) }
        verify(exactly = 1) { nostrClient.releaseConnection() }
    }

    @Test
    fun `unreadable balances skip that group's snapshot and the run still succeeds`() = runBlocking {
        coEvery { groupRepo.getAll() } returns listOf(group("sealed"), group("readable"))
        coEvery { createSnapshot("sealed") } throws BalanceUnavailableException("Missing key for epoch 1")

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { createSnapshot("readable") }
        coVerify(exactly = 1) { selfHeal("readable") }
        coVerify(exactly = 1) { outboxDao.deleteOlderThan(any()) }
    }

    @Test
    fun `an incomplete pull or failed flush makes the run retry after healing, snapshotting and trimming`() =
        runBlocking {
            coEvery { groupRepo.getAll() } returns listOf(group("partial"), group("other"))
            coEvery { syncEngine.pullEvents("partial", 0, "key-partial", lenientTimestamp = true) } returns
                PullResult(stored = 2, complete = false)

            assertEquals(ListenableWorker.Result.retry(), worker().doWork())

            coVerify(exactly = 1) { selfHeal("partial") }
            coVerify(exactly = 1) { selfHeal("other") }
            coVerify(exactly = 1) { createSnapshot("partial") }
            coVerify(exactly = 1) { createSnapshot("other") }
            coVerify(exactly = 1) { outboxDao.deleteOlderThan(any()) }

            coEvery { syncEngine.pullEvents(any(), any(), any(), any()) } returns
                PullResult(stored = 0, complete = true)
            coEvery { syncEngine.flushOutbox() } returns FlushResult(published = 0, failed = 1)
            every { params.runAttemptCount } returns DailySyncWorker.RUN_RETRY_BUDGET - 1
            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
            every { params.runAttemptCount } returns DailySyncWorker.RUN_RETRY_BUDGET
            assertEquals(ListenableWorker.Result.failure(), worker().doWork())
            verify(exactly = 3) { nostrClient.releaseConnection() }
        }

    @Test
    fun `other failures retry then fail so the next period still runs`() = runBlocking {
        coEvery { groupRepo.getAll() } returns listOf(group("g"))
        coEvery { createSnapshot("g") } throws IllegalStateException("disk full")

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        every { params.runAttemptCount } returns DailySyncWorker.RUN_RETRY_BUDGET
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
        verify(exactly = 2) { nostrClient.releaseConnection() }
    }

    @Test
    fun `cancellation propagates instead of becoming a retry or failure`() = runBlocking {
        coEvery { groupRepo.getAll() } returns listOf(group("g"))
        coEvery { selfHeal("g") } throws CancellationException("worker stopped")

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
