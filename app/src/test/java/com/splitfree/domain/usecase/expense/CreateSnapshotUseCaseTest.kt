package com.splitfree.domain.usecase.expense

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.balance.Balance
import com.splitfree.domain.model.balance.BalanceSnapshot
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.HashUtil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val identity = mockk<IdentityContract>()

    private lateinit var useCase: CreateSnapshotUseCase
    private val groupId = "group-1"
    private val groupKey = "key1"
    private val myPubkey = "pub"
    private val fakeEvent = NostrEvent("evt1", "pub", 1000, 30078, emptyList(), "enc", "sig")
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0

        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { groupRepo.getById(groupId) } returns Group(
            id = groupId,
            name = "Test",
            createdBy = myPubkey,
            createdAt = 1000,
            members = listOf(myPubkey),
            relays = emptyList()
        )
        every { identity.getPublicKeyHex() } returns myPubkey
        coEvery { computeBalances(groupId) } returns listOf(Balance("pub1", 100, "INR"), Balance("pub2", -100, "INR"))
        coEvery { eventRepo.getEventIds(groupId) } returns listOf("e1", "e2")
        every { encryption.encrypt(any(), groupKey) } returns "encrypted"
        every { encryption.decrypt(any(), groupKey) } answers { firstArg() }
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns fakeEvent

        useCase =
            CreateSnapshotUseCase(eventRepo, groupRepo, computeBalances, encryption, signer, eventPublisher, identity)
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    private fun snapshotEvent(eventId: String, asOfCount: Int, createdAt: Long) = EventSnapshot(
        eventId = eventId,
        groupId = groupId,
        pubkey = "pub",
        createdAt = createdAt,
        kind = 30078,
        contentEncrypted =
        """{"id":"s1","as_of_event_count":$asOfCount,""" +
            """"as_of_timestamp":1,"balances":[],"event_hashes":[]}""",
        eventType = "snapshot",
        sig = "sig",
        receivedAt = 1
    )

    @Test
    fun `creates snapshot when threshold exceeded`() = runBlocking {
        coEvery { eventRepo.getEventCount(groupId) } returns 150
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns null
        val result = useCase(groupId)
        assertTrue(result)
        coVerify { eventPublisher.saveAndQueue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `does not wrap the work in a repository transaction`() = runBlocking {
        coEvery { eventRepo.getEventCount(groupId) } returns 150
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns null

        assertTrue(useCase(groupId))

        coVerify(exactly = 0) { eventRepo.withTransaction<Any?>(any()) }
        coVerify(exactly = 1) { eventPublisher.saveAndQueue(any(), groupId, "encrypted", "snapshot", any()) }
    }

    @Test
    fun `skips when below threshold`() = runBlocking {
        coEvery { eventRepo.getEventCount(groupId) } returns 50
        // 1 day old snapshot at 40 events: 50-40=10 events, 1 day < 30 days
        val recentSnapshot = snapshotEvent(
            "snap1",
            asOfCount = 40,
            createdAt =
            System.currentTimeMillis() / 1000 - 86400
        )
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns recentSnapshot
        val result = useCase(groupId)
        assertFalse(result)
        coVerify(exactly = 0) { eventPublisher.saveAndQueue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `creates snapshot when 30 days elapsed`() = runBlocking {
        coEvery { eventRepo.getEventCount(groupId) } returns 50
        val oldSnapshot =
            snapshotEvent("snap1", asOfCount = 40, createdAt = System.currentTimeMillis() / 1000 - 31 * 86400)
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns oldSnapshot
        val result = useCase(groupId)
        assertTrue(result)
    }

    @Test
    fun `returns false when no group key`() = runBlocking {
        coEvery { eventRepo.getEventCount(groupId) } returns 200
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns null
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns null
        val result = useCase(groupId)
        assertFalse(result)
    }

    @Test
    fun `does not create a snapshot when I am not the creator`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns Group(
            id = groupId,
            name = "Test",
            createdBy = "someone-else",
            createdAt = 1000,
            members = listOf("someone-else", myPubkey),
            relays = emptyList()
        )
        coEvery { eventRepo.getEventCount(groupId) } returns 200
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns null

        assertFalse(useCase(groupId))
        coVerify(exactly = 0) { eventPublisher.saveAndQueue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `does not create a snapshot when the group has no known creator`() = runBlocking {
        // With createdBy empty there is no trusted snapshot author, so nobody (not even a
        // member) may publish one.
        coEvery { groupRepo.getById(groupId) } returns Group(
            id = groupId,
            name = "Test",
            createdBy = "",
            createdAt = 1000,
            members = listOf(myPubkey),
            relays = emptyList()
        )
        coEvery { eventRepo.getEventCount(groupId) } returns 200
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns null

        assertFalse(useCase(groupId))
        coVerify(exactly = 0) { eventPublisher.saveAndQueue(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.getGroupKeyForEpoch(any(), any()) }
    }

    @Test
    fun `encrypts snapshot with the loaded group's epoch key and never calls getGroupKey`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns Group(
            id = groupId,
            name = "Test",
            createdBy = myPubkey,
            createdAt = 1000,
            members = listOf(myPubkey),
            relays = emptyList(),
            keyEpoch = 2
        )
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 2) } returns "epoch2"
        every { encryption.encrypt(any(), "epoch2") } returns "encrypted2"
        coEvery { eventRepo.getEventCount(groupId) } returns 150
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns null

        assertTrue(useCase(groupId))
        coVerify { groupRepo.getGroupKeyForEpoch(groupId, 2) }
        coVerify(exactly = 0) { groupRepo.getGroupKey(any()) }
        coVerify { eventPublisher.saveAndQueue(any(), groupId, "encrypted2", "snapshot", any()) }
    }

    // --- Snapshot size (S3) ---

    @Test
    fun `event hashes are 24-char prefixes of sha256 of each event id`() = runBlocking {
        val ids = (1..12).map { "event-$it" }
        coEvery { eventRepo.getEventIds(groupId) } returns ids
        coEvery { eventRepo.getEventCount(groupId) } returns 150
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns null
        val plaintext = slot<String>()
        every { encryption.encrypt(capture(plaintext), groupKey) } returns "encrypted"

        assertTrue(useCase(groupId))

        val snapshot = json.decodeFromString<BalanceSnapshot>(plaintext.captured)
        assertEquals(ids.size, snapshot.event_hashes.size)
        assertEquals(ids.size, snapshot.as_of_event_count)
        assertTrue(snapshot.event_hashes.all { it.length == 24 })
        assertEquals(ids.map { HashUtil.eventHashPrefix(it) }, snapshot.event_hashes)
        snapshot.event_hashes.zip(ids).forEach { (hash, id) ->
            assertTrue(HashUtil.sha256Hex(id).startsWith(hash))
        }
    }

    @Test
    fun `oversized snapshot is not created`() = runBlocking {
        coEvery { eventRepo.getEventIds(groupId) } returns (1..3000).map { "event-$it" }
        coEvery { eventRepo.getEventCount(groupId) } returns 3000
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns null

        assertFalse(useCase(groupId))

        coVerify(exactly = 0) { eventPublisher.saveAndQueue(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { encryption.encrypt(any(), any()) }
        verify(exactly = 0) { signer.createSignedEvent(any(), any(), any(), any()) }
        verify(exactly = 1) { android.util.Log.w("CreateSnapshot", any<String>()) }
    }

    @Test
    fun `snapshot just under the cap is still created`() = runBlocking {
        // ~27 bytes per hash entry: 2000 events stays under MAX_SNAPSHOT_PLAINTEXT, 3000 does not.
        coEvery { eventRepo.getEventIds(groupId) } returns (1..2000).map { "event-$it" }
        coEvery { eventRepo.getEventCount(groupId) } returns 2000
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns null
        val plaintext = slot<String>()
        every { encryption.encrypt(capture(plaintext), groupKey) } returns "encrypted"

        assertTrue(useCase(groupId))
        assertTrue(plaintext.captured.toByteArray(Charsets.UTF_8).size <= CreateSnapshotUseCase.MAX_SNAPSHOT_PLAINTEXT)
    }

    // --- Concurrency guard (S4) ---

    @Test
    fun `aborts when a new snapshot appeared between the initial read and save`() = runBlocking {
        coEvery { eventRepo.getEventCount(groupId) } returns 150
        val concurrent = snapshotEvent("snap-other", asOfCount = 150, createdAt = System.currentTimeMillis() / 1000)
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returnsMany listOf(null, concurrent)

        assertFalse(useCase(groupId))

        coVerify(exactly = 2) { eventRepo.getLatestEventByType(groupId, "snapshot") }
        coVerify(exactly = 0) { eventPublisher.saveAndQueue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `aborts when the latest snapshot changed identity between the initial read and save`() = runBlocking {
        coEvery { eventRepo.getEventCount(groupId) } returns 200
        val old = snapshotEvent("snap-old", asOfCount = 40, createdAt = System.currentTimeMillis() / 1000 - 31 * 86400)
        val newer = snapshotEvent("snap-new", asOfCount = 200, createdAt = System.currentTimeMillis() / 1000)
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returnsMany listOf(old, newer)

        assertFalse(useCase(groupId))

        coVerify(exactly = 0) { eventPublisher.saveAndQueue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `proceeds when the latest snapshot is unchanged between the initial read and save`() = runBlocking {
        coEvery { eventRepo.getEventCount(groupId) } returns 200
        val old = snapshotEvent("snap-old", asOfCount = 40, createdAt = System.currentTimeMillis() / 1000 - 31 * 86400)
        coEvery { eventRepo.getLatestEventByType(groupId, "snapshot") } returns old

        assertTrue(useCase(groupId))

        coVerify(exactly = 1) { eventPublisher.saveAndQueue(any(), any(), any(), any(), any()) }
    }
}
