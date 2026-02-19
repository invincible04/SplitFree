package com.splitfree.domain.usecase.expense

import androidx.room.withTransaction
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.balance.Balance
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CreateSnapshotUseCaseTest {
    private val eventRepo = mockk<EventRepositoryContract>(relaxed = true)
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val computeBalances = mockk<ComputeBalancesUseCase>()
    private val encryption = mockk<GroupEncryption>()
    private val signer = mockk<EventSigner>()
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)

    private lateinit var useCase: CreateSnapshotUseCase
    private val groupId = "group-1"
    private val groupKey = "key1"
    private val fakeEvent = NostrEvent("evt1", "pub", 1000, 30078, emptyList(), "enc", "sig")

    @Before
    fun setup() {
        // Mock Room withTransaction to just execute the block
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { eventRepo.withTransaction(captureLambda<suspend () -> Boolean>()) } coAnswers {
            lambda<suspend () -> Boolean>().captured.invoke()
        }

        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { computeBalances(groupId) } returns listOf(Balance("pub1", 100), Balance("pub2", -100))
        coEvery { eventRepo.getEventIds(groupId) } returns listOf("e1", "e2")
        every { encryption.encrypt(any(), groupKey) } returns "encrypted"
        every { encryption.decrypt(any(), groupKey) } answers { firstArg() }
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns fakeEvent

        useCase = CreateSnapshotUseCase(eventRepo, groupRepo, computeBalances, encryption, signer, eventPublisher)
    }

    @After
    fun teardown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `creates snapshot when threshold exceeded`() = runBlocking {
        coEvery { eventRepo.getEventCount(groupId) } returns 150
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns null
        val result = useCase(groupId)
        assertTrue(result)
        coVerify { eventPublisher.saveAndQueue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `skips when below threshold`() = runBlocking {
        coEvery { eventRepo.getEventCount(groupId) } returns 50
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns null
        // daysSinceSnapshot = Long.MAX_VALUE (no snapshot), but eventsSinceSnapshot < 100
        // Actually Long.MAX_VALUE >= 30, so it should create. Let me set a recent snapshot.
        val recentSnapshot =
            EventSnapshot(
                eventId = "snap1",
                groupId = groupId,
                pubkey = "pub",
                createdAt = System.currentTimeMillis() / 1000 - 86400, // 1 day ago
                kind = 30078,
                contentEncrypted =
                """{"id":"s1","as_of_event_count":40,""" +
                    """"as_of_timestamp":1,"balances":[],"event_hashes":[]}""",
                eventType = "snapshot",
                sig = "sig",
                receivedAt = 1
            )
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns recentSnapshot
        val result = useCase(groupId)
        assertFalse(result) // 50-40=10 events, 1 day < 30 days
    }

    @Test
    fun `creates snapshot when 30 days elapsed`() = runBlocking {
        coEvery { eventRepo.getEventCount(groupId) } returns 50
        val oldSnapshot =
            EventSnapshot(
                eventId = "snap1",
                groupId = groupId,
                pubkey = "pub",
                createdAt = System.currentTimeMillis() / 1000 - 31 * 86400, // 31 days ago
                kind = 30078,
                contentEncrypted =
                """{"id":"s1","as_of_event_count":40,""" +
                    """"as_of_timestamp":1,"balances":[],"event_hashes":[]}""",
                eventType = "snapshot",
                sig = "sig",
                receivedAt = 1
            )
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns oldSnapshot
        val result = useCase(groupId)
        assertTrue(result)
    }

    @Test
    fun `returns false when no group key`() = runBlocking {
        coEvery { eventRepo.getEventCount(groupId) } returns 200
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns null
        coEvery { groupRepo.getGroupKey(groupId) } returns null
        val result = useCase(groupId)
        assertFalse(result)
    }
}
