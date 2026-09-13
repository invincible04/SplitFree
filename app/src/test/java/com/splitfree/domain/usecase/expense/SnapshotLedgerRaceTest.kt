package com.splitfree.domain.usecase.expense

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.balance.BalanceSnapshot
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.CompressionProvider
import com.splitfree.domain.util.HashUtil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A snapshot must describe exactly the ledger it was computed from. An event that lands while the snapshot is
 * being computed stays outside its coverage list, so the next balance computation replays it on top instead of
 * treating it as already counted. Real NIP-44 encryption; the repository is a scripted stand-in whose ledger
 * grows at a deterministic point inside the computation.
 */
class SnapshotLedgerRaceTest {
    private val groupId = "snapshot-race"
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val encryption = spyk(
        GroupEncryption(
            mockk<CompressionProvider> {
                every { shouldCompress(any()) } returns false
            }
        )
    )
    private val repo = mockk<EventRepositoryContract>(relaxed = true)
    private val groups = mockk<GroupRepositoryContract> {
        coEvery { getById(groupId) } returns
            Group(
                id = groupId,
                name = "Race",
                createdBy = "alice",
                createdAt = 1,
                members = listOf("alice", "bob"),
                relays = emptyList()
            )
        coEvery { getGroupKeyForEpoch(groupId, 0) } returns key
    }
    private val identity = mockk<IdentityContract> { every { getPublicKeyHex() } returns "alice" }
    private val signer = mockk<EventSigner> {
        every { createSignedEvent(any(), any(), any(), any()) } answers {
            NostrEvent("snapshot", "alice", 1000, 30078, emptyList(), thirdArg(), "sig")
        }
    }
    private val publisher = mockk<EventPublisherContract>()
    private val compute = ComputeBalancesUseCase(repo, groups, encryption)
    private val create = CreateSnapshotUseCase(repo, groups, compute, encryption, signer, publisher, identity)
    private val ledger = (1..10).map { expense(it) }.toMutableList()

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() = unmockkStatic(android.util.Log::class)

    private fun expense(number: Int, amount: Long = 100, type: String = "expense", uuid: String = "u$number") =
        EventSnapshot(
            "event-$number-$type",
            groupId,
            "alice",
            number.toLong(),
            contentEncrypted = encryption.encrypt(
                """{"id":"$uuid","amount":$amount,"currency":"INR","description":"test","paid_by":"alice",""" +
                    """"split_type":"equal","split_among":[{"pubkey":"alice","share":${amount / 2}},""" +
                    """{"pubkey":"bob","share":${amount / 2}}],"timestamp":$number}""",
                key
            ),
            eventType = type,
            expenseUuid = uuid
        )

    /**
     * Snapshots the ten-expense ledger while [arrival] lands mid-computation (during the decryption of the
     * last covered event), then checks that the snapshot covers only the initial ten and that the incremental
     * balance equals [expected] and equals a full replay of the grown ledger.
     */
    private suspend fun assertArrival(arrival: EventSnapshot, expected: Long) {
        val initial = ledger.toList()
        var injected = false
        every { encryption.decrypt(initial.last().contentEncrypted, key) } answers {
            if (!injected) {
                ledger += arrival
                injected = true
            }
            callOriginal()
        }
        coEvery { repo.getEventsByGroup(groupId) } answers { ledger.toList() }
        coEvery { repo.getEventIds(groupId) } answers { ledger.map { it.eventId } }
        coEvery { repo.getLatestEventByType(groupId, "snapshot") } answers { ledger.latestSnapshot() }
        coEvery { repo.withTransaction(captureLambda<suspend () -> Boolean>()) } coAnswers {
            lambda<suspend () -> Boolean>().captured.invoke()
        }
        coEvery { publisher.saveAndQueue(any(), groupId, any(), "snapshot", any()) } answers {
            val event = firstArg<NostrEvent>()
            ledger += EventSnapshot(
                event.id,
                groupId,
                event.pubkey,
                event.createdAt,
                contentEncrypted = event.content,
                eventType = "snapshot"
            )
        }

        assertTrue(create(groupId))
        assertTrue("the arrival must have landed during computation", injected)
        val snapshot = Json.decodeFromString<BalanceSnapshot>(encryption.decrypt(ledger.last().contentEncrypted, key))
        assertEquals(500L, snapshot.balances.single { it.pubkey == "alice" }.net)
        assertEquals(10, snapshot.as_of_event_count)
        assertEquals(initial.map { HashUtil.eventHashPrefix(it.eventId) }, snapshot.event_hashes)
        assertFalse(HashUtil.eventHashPrefix(arrival.eventId) in snapshot.event_hashes)
        val incremental = compute(groupId).associate { it.pubkey to it.net }
        assertEquals(expected, incremental["alice"])
        assertEquals(-expected, incremental["bob"])
        assertEquals(
            incremental,
            compute.computeWithExclusions(groupId, ledger.toList(), useSnapshots = false)
                .balances.associate { it.pubkey to it.net }
        )
        coVerify(exactly = 0) { repo.getEventIds(any()) }
    }

    @Test
    fun `arriving eleventh expense stays outside the 500 snapshot and replays to 550`() = runBlocking {
        assertArrival(expense(11), 550)
    }

    @Test
    fun `arriving correction reverses the original covered by the immutable snapshot`() = runBlocking {
        assertArrival(expense(11, 200, "expense_correction", "u1"), 550)
    }

    @Test
    fun `arriving deletion reverses the original covered by the immutable snapshot`() = runBlocking {
        assertArrival(
            EventSnapshot(
                "delete",
                groupId,
                "alice",
                11,
                contentEncrypted = encryption.encrypt("{}", key),
                eventType = "expense_delete",
                expenseUuid = "u1"
            ),
            450
        )
    }

    @Test
    fun `arriving settlement is replayed after the immutable snapshot`() = runBlocking {
        assertArrival(
            EventSnapshot(
                "settlement",
                groupId,
                "bob",
                11,
                contentEncrypted = encryption.encrypt(
                    """{"id":"s1","from":"bob","to":"alice","amount":100,"currency":"INR","timestamp":11}""",
                    key
                ),
                eventType = "settlement",
                expenseUuid = "s1"
            ),
            400
        )
    }

    @Test
    fun `balance computation ignores a snapshot and ids outside its ledger read`() = runBlocking {
        coEvery { repo.getEventsByGroup(groupId) } answers {
            val captured = ledger.toList()
            ledger += expense(11)
            captured
        }
        coEvery { repo.getLatestEventByType(groupId, "snapshot") } throws AssertionError("Racing snapshot read")
        coEvery { repo.getEventIds(groupId) } throws AssertionError("Racing coverage read")

        assertEquals(500L, compute(groupId).single { it.pubkey == "alice" }.net)
        coVerify(exactly = 1) { repo.getEventsByGroup(groupId) }
    }
}
