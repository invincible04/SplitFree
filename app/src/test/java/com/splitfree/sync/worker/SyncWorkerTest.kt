package com.splitfree.sync.worker

import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.usecase.expense.BalanceUnavailableException
import com.splitfree.domain.usecase.expense.CreateSnapshotUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A snapshot is an optimisation layered on sync. A group whose money this device cannot read yet must not
 * fail the run or starve the groups after it of their pull, self-heal and snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SyncWorkerTest {
    private val groupRepo = mockk<GroupRepository>()
    private val nostrClient = mockk<NostrClient>(relaxed = true)
    private val identity = mockk<IdentityManager> { every { hasIdentity() } returns true }
    private val createSnapshot = mockk<CreateSnapshotUseCase>()
    private val selfHeal = mockk<SelfHealUseCase> { coEvery { this@mockk.invoke(any()) } returns 0 }
    private val relays = mockk<RelayConnectionManager> { coEvery { ensureConnected(any()) } returns emptyList() }
    private val syncEngine = mockk<SyncEngine> {
        coEvery { flushOutbox() } returns 0
        coEvery { pullEvents(any(), any(), any(), any(), any()) } returns 0
    }

    private fun group(id: String) =
        Group(id, id, createdBy = "alice", createdAt = 1, members = listOf("alice"), relays = emptyList())

    private fun worker() = SyncWorker(
        RuntimeEnvironment.getApplication(),
        mockk<WorkerParameters>(relaxed = true),
        groupRepo,
        nostrClient,
        identity,
        createSnapshot,
        selfHeal,
        relays,
        syncEngine
    )

    @Test
    fun `unavailable balances skip that group's snapshot and the run still succeeds`() = runBlocking {
        val sealed = group("sealed")
        val readable = group("readable")
        coEvery { groupRepo.getAll() } returns listOf(sealed, readable)
        for (g in listOf(sealed, readable)) {
            coEvery { groupRepo.getGroupEntity(g.id) } returns
                GroupEntity(g.id, g.name, createdBy = "alice", createdAt = 1, members = "[]", relays = "[]")
            coEvery { groupRepo.getGroupKey(g.id) } returns "key-${g.id}"
        }
        coEvery { createSnapshot("sealed") } throws BalanceUnavailableException("Missing key for epoch 1")
        coEvery { createSnapshot("readable") } returns true

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { createSnapshot("sealed") }
        coVerify(exactly = 1) { createSnapshot("readable") }
        coVerify(exactly = 1) { selfHeal("readable") }
        verify(exactly = 1) { nostrClient.releaseConnection() }
    }

    @Test
    fun `other snapshot failures still fail the run`() = runBlocking {
        val g = group("g")
        coEvery { groupRepo.getAll() } returns listOf(g)
        coEvery { groupRepo.getGroupEntity(g.id) } returns
            GroupEntity(g.id, g.name, createdBy = "alice", createdAt = 1, members = "[]", relays = "[]")
        coEvery { groupRepo.getGroupKey(g.id) } returns "key"
        coEvery { createSnapshot("g") } throws IllegalStateException("disk full")

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
