package com.splitfree.domain.usecase

import androidx.room.withTransaction
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.OutboxDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.HashUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.Balance
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CreateSnapshotUseCaseTest {
    private val db = mockk<AppDatabase>()
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val outboxDao = mockk<OutboxDao>(relaxed = true)
    private val groupRepo = mockk<GroupRepository>()
    private val computeBalances = mockk<ComputeBalancesUseCase>()
    private val encryption = mockk<GroupEncryption>()
    private val signer = mockk<EventSigner>()

    private lateinit var useCase: CreateSnapshotUseCase
    private val groupId = "group-1"
    private val groupKey = "key1"
    private val fakeEvent = NostrEvent("evt1", "pub", 1000, 30078, emptyList(), "enc", "sig")

    @Before
    fun setup() {
        // Mock Room withTransaction to just execute the block
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { db.withTransaction(captureLambda<suspend () -> Boolean>()) } coAnswers {
            lambda<suspend () -> Boolean>().captured.invoke()
        }

        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { computeBalances(groupId) } returns listOf(Balance("pub1", 100), Balance("pub2", -100))
        coEvery { eventDao.getEventIds(groupId) } returns listOf("e1", "e2")
        every { encryption.encrypt(any(), groupKey) } returns "encrypted"
        every { encryption.decrypt(any(), groupKey) } answers { firstArg() }
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns fakeEvent

        useCase = CreateSnapshotUseCase(db, eventDao, outboxDao, groupRepo, computeBalances, encryption, signer)
    }

    @After
    fun teardown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `creates snapshot when threshold exceeded`() =
        runBlocking {
            coEvery { eventDao.getEventCount(groupId) } returns 150
            coEvery { eventDao.getLatestEventByType(groupId, "snapshot") } returns null
            val result = useCase(groupId)
            assertTrue(result)
            coVerify { eventDao.insert(any<EventEntity>()) }
            coVerify { outboxDao.insert(any()) }
        }

    @Test
    fun `skips when below threshold`() =
        runBlocking {
            coEvery { eventDao.getEventCount(groupId) } returns 50
            coEvery { eventDao.getLatestEventByType(groupId, "snapshot") } returns null
            // daysSinceSnapshot = Long.MAX_VALUE (no snapshot), but eventsSinceSnapshot < 100
            // Actually Long.MAX_VALUE >= 30, so it should create. Let me set a recent snapshot.
            val recentSnapshot =
                EventEntity(
                    eventId = "snap1",
                    groupId = groupId,
                    pubkey = "pub",
                    createdAt = System.currentTimeMillis() / 1000 - 86400, // 1 day ago
                    kind = 30078,
                    contentEncrypted = """{"id":"s1","as_of_event_count":40,"as_of_timestamp":1,"balances":[],"event_hashes":[]}""",
                    eventType = "snapshot",
                    sig = "sig",
                    receivedAt = 1,
                )
            coEvery { eventDao.getLatestEventByType(groupId, "snapshot") } returns recentSnapshot
            val result = useCase(groupId)
            assertFalse(result) // 50-40=10 events, 1 day < 30 days
        }

    @Test
    fun `creates snapshot when 30 days elapsed`() =
        runBlocking {
            coEvery { eventDao.getEventCount(groupId) } returns 50
            val oldSnapshot =
                EventEntity(
                    eventId = "snap1",
                    groupId = groupId,
                    pubkey = "pub",
                    createdAt = System.currentTimeMillis() / 1000 - 31 * 86400, // 31 days ago
                    kind = 30078,
                    contentEncrypted = """{"id":"s1","as_of_event_count":40,"as_of_timestamp":1,"balances":[],"event_hashes":[]}""",
                    eventType = "snapshot",
                    sig = "sig",
                    receivedAt = 1,
                )
            coEvery { eventDao.getLatestEventByType(groupId, "snapshot") } returns oldSnapshot
            val result = useCase(groupId)
            assertTrue(result)
        }

    @Test
    fun `returns false when no group key`() =
        runBlocking {
            coEvery { eventDao.getEventCount(groupId) } returns 200
            coEvery { eventDao.getLatestEventByType(groupId, "snapshot") } returns null
            coEvery { groupRepo.getGroupKey(groupId) } returns null
            val result = useCase(groupId)
            assertFalse(result)
        }
}
