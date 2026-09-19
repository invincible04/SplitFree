package com.splitfree.domain.usecase.expense

import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.balance.BalanceSnapshot
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.RetiredIdentities
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.HashUtil
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Money invariants at the snapshot and balance boundary, with real signing and NIP-44 encryption over a
 * scripted repository:
 * - a snapshot never covers an event whose amount it did not include, so a later replay still counts it;
 * - an unreadable money event makes the balance unavailable instead of silently smaller.
 */
class MoneyLedgerInvariantsTest {
    private val secret = ByteArray(32) { 1 }
    private val author = NostrEvent.pubkeyFromPrivkey(secret)
    private val peer = NostrEvent.pubkeyFromPrivkey(ByteArray(32) { 2 })
    private val groupId = "money-ledger-invariants"
    private val encryption = GroupEncryption(CompressionUtil)
    private val key = encryption.generateGroupKey()
    private val identity = mockk<IdentityContract>()
    private val groups = mockk<GroupRepositoryContract>(relaxed = true)
    private val events = mockk<EventRepositoryContract>(relaxed = true)
    private val ledger = mutableListOf<EventSnapshot>()
    private lateinit var signer: EventSigner

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { identity.getPublicKeyHex() } returns author
        every { identity.getPrivateKeyBytes() } answers { secret.copyOf() }
        coEvery { groups.getById(groupId) } returns Group(
            groupId,
            "Invariants",
            createdBy = author,
            createdAt = 1,
            members = listOf(author, peer),
            relays = emptyList()
        )
        coEvery { groups.getGroupKeyForEpoch(groupId, 0) } returns key
        coEvery { groups.retiredIdentities(groupId) } returns RetiredIdentities.NONE
        coEvery { events.getEventsByGroup(groupId) } answers { ledger.toList() }
        coEvery { events.getEventCount(groupId) } answers { ledger.size }
        coEvery { events.getEventIds(groupId) } answers { ledger.map { it.eventId } }
        coEvery { events.getLatestEventByType(groupId, "snapshot") } answers { ledger.latestSnapshot() }
        coEvery { events.withTransaction(captureLambda<suspend () -> Boolean>()) } coAnswers {
            lambda<suspend () -> Boolean>().captured.invoke()
        }
        signer = EventSigner(identity)
    }

    @After
    fun teardown() = unmockkAll()

    private fun row(event: NostrEvent, type: String) = EventSnapshot(
        event.id,
        groupId,
        event.pubkey,
        event.createdAt,
        contentEncrypted = event.content,
        eventType = type,
        expenseUuid = event.tags.firstOrNull { it[0] == "x" }?.get(1),
        sig = event.sig,
        originalEventJson = event.toJson()
    )

    /** A signed 100 INR expense paid by [author], split evenly with [peer]: +50 for the author. */
    private fun expense(index: Int): EventSnapshot {
        val id = "expense-$index"
        val payload = """{"id":"$id","amount":100,"currency":"INR","description":"t","paid_by":"$author",""" +
            """"split_type":"equal","split_among":[{"pubkey":"$author","share":50},{"pubkey":"$peer","share":50}],""" +
            """"timestamp":100}"""
        val event = NostrEvent(
            pubkey = author,
            createdAt = 100,
            kind = 30078,
            tags = listOf(listOf("g", groupId), listOf("t", "expense"), listOf("x", id)),
            content = encryption.encrypt(payload, key)
        ).sign(secret)
        return row(event, "expense")
    }

    private fun compute() = ComputeBalancesUseCase(events, groups, encryption)

    private fun authorNet(): Long = runBlocking { compute()(groupId).single { it.pubkey == author }.net }

    @Test
    fun `snapshot never covers an event whose amount it did not include`() = runBlocking {
        ledger += (1..10).map { expense(it) }
        val arriving = expense(11)
        val publisher = mockk<EventPublisherContract>()
        coEvery { publisher.saveAndQueue(any(), groupId, any(), "snapshot", any()) } answers {
            // The scripted ledger appends the eleventh expense immediately before the snapshot row.
            ledger += arriving
            ledger += row(firstArg(), "snapshot")
        }

        assertTrue(CreateSnapshotUseCase(events, groups, compute(), encryption, signer, publisher, identity)(groupId))

        val snapshotRow = checkNotNull(ledger.latestSnapshot())
        val saved = Json.decodeFromString<BalanceSnapshot>(encryption.decrypt(snapshotRow.contentEncrypted, key))
        assertEquals(500L, saved.balances.single { it.pubkey == author }.net)
        assertEquals(10, saved.event_hashes.size)
        assertFalse(HashUtil.eventHashPrefix(arriving.eventId) in saved.event_hashes)
        assertEquals("Snapshot-seeded balance replays the eleventh expense", 550L, authorNet())

        ledger.remove(snapshotRow)
        assertEquals("Full replay agrees with the snapshot-seeded balance", 550L, authorNet())
    }

    @Test
    fun `missing epoch key makes the balance unavailable instead of partial`() = runBlocking {
        val readable = expense(1)
        val sealed = expense(2).copy(keyEpoch = 1)
        ledger += listOf(readable, sealed)
        coEvery { groups.getGroupKeyForEpoch(groupId, 1) } returns null

        val failure = assertThrows(BalanceUnavailableException::class.java) { authorNet() }
        assertTrue(failure.message.orEmpty().contains("epoch 1"))

        coEvery { groups.getGroupKeyForEpoch(groupId, 1) } returns key
        assertEquals("Both expenses count once the key is present", 100L, authorNet())
    }

    @Test
    fun `undecryptable money event makes the balance unavailable instead of partial`() = runBlocking {
        val readable = expense(1)
        val corrupt = expense(2).copy(contentEncrypted = "not-nip44")
        ledger += listOf(readable, corrupt)

        assertThrows(BalanceUnavailableException::class.java) { authorNet() }

        ledger.remove(corrupt)
        assertEquals(50L, authorNet())
    }
}
