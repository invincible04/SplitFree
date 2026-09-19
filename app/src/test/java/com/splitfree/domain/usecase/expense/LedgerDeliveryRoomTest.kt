package com.splitfree.domain.usecase.expense

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.ExpenseRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.money.ExpenseSplitCalculator
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.export.ExportGroupUseCase
import com.splitfree.domain.usecase.export.ImportGroupUseCase
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.sync.event.EventPostProcessor
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.EventPublisher
import com.splitfree.sync.event.IngestOutcome
import com.splitfree.sync.event.IngestionContext
import com.splitfree.sync.event.MembershipHistory
import com.splitfree.sync.nearby.TestIdentity
import com.splitfree.test.FakeSecureStorage
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Offline cross-component tests: production money use cases, repositories, publisher, durable outbox,
 * NIP-44/NIP-59, ingestion, SQLite projections and backup restoration. Separate databases represent peers.
 *
 * The test copies serialized outbox rows directly, not through a relay or Nearby SDK. Identity/key storage
 * and dispatch scheduling are fakes; this does not certify Android Keystore, radios, UI or process death.
 * Group membership is bootstrapped explicitly, so these tests do not cover the invite/join flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LedgerDeliveryRoomTest {
    private val peers = mutableListOf<Peer>()
    private val encryption = GroupEncryption(CompressionUtil)
    private val groupKey = encryption.generateGroupKey()
    private val createdAt = System.currentTimeMillis() / 1000 - 1_000
    private val creator = TestIdentity(1).pub
    private val groupId = GroupIdentity.derive(creator, createdAt)
    private val direct = Executor { it.run() }

    private inner class Peer(seed: Int, wrapped: Boolean = true) {
        val identity: TestIdentity = TestIdentity(seed).also { fixture ->
            every { fixture.contract.getPublicKeyBytes() } answers { fixture.pub.hexToBytes() }
        }
        val pub = identity.pub
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
            .allowMainThreadQueries()
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
            .build()
        val groups = GroupRepository(db.groupDao(), FakeSecureStorage())
        val events = EventRepository(db, db.eventDao())
        val signer = EventSigner(identity.contract)
        val settings = mockk<SettingsContract>().also { every { it.giftWrapEnabled } returns wrapped }
        val giftWrap = GiftWrapService(identity.contract, settings)
        val throttler = mockk<EventThrottler>().also {
            every { it.enqueue(any()) } throws IllegalStateException("Offline test transport")
        }
        val publisher = EventPublisher(
            db.eventDao(), db.outboxDao(), db.deliveryDao(), throttler, mockk(relaxed = true),
            giftWrap, groups, identity.contract, db, settings
        )
        val postProcessor = EventPostProcessor(
            groups,
            mockk(),
            mockk(),
            mockk(),
            publisher,
            identity.contract,
            scope
        )
        val processor = EventProcessor(
            db.eventDao(), groups, encryption, signer, identity.contract, giftWrap, EventValidator(),
            postProcessor, MembershipHistory(db.eventDao(), groups, identity.contract)
        )
        val expenses = ExpenseRepository(
            db.eventDao(),
            groups,
            encryption,
            identity.contract,
            signer,
            publisher,
            MembershipHistory(db.eventDao(), groups, identity.contract)
        )
        val balances = ComputeBalancesUseCase(events, groups, encryption)
        val ledger = GetExpensesUseCase(events, groups, encryption)
        val importer = ImportGroupUseCase(events, groups, encryption, EventValidator(), identity.contract)

        init {
            peers += this
        }

        suspend fun ingest(event: NostrEvent): IngestOutcome = processor.process(
            event,
            context = IngestionContext.RECONCILIATION,
            expectedGroupId = groupId
        ).outcome

        suspend fun net(): Map<Pair<String, String>, Long> =
            balances(groupId).associate { (it.pubkey to it.currency) to it.net }

        suspend fun outbox(): List<NostrEvent> = db.outboxDao().getAll().map {
            checkNotNull(NostrEvent.fromJson(it.eventJson))
        }
    }

    @After
    fun cleanup() {
        peers.forEach { it.scope.cancel() }
        peers.forEach { it.db.close() }
    }

    private suspend fun bootstrap(wrapped: Boolean): Pair<Peer, Peer> {
        val alice = Peer(1, wrapped)
        val bob = Peer(2, wrapped)
        val members = listOf(alice.pub, bob.pub)
        val group = Group(
            groupId,
            "Test trip",
            createdBy = creator,
            createdAt = createdAt,
            members = members,
            relays = emptyList()
        )
        val meta = GroupMeta(name = group.name, createdBy = creator, createdAt = createdAt, members = members)
        val content = encryption.encrypt(Json.encodeToString(GroupMeta.serializer(), meta), groupKey)
        val signed = alice.signer.createSignedEvent(groupId, "group_meta", content, createdAt = createdAt + 1)
        assertTrue(alice.publisher.publishCreatedGroup(signed, group, groupKey))
        bob.groups.save(group, groupKey)
        assertEquals(IngestOutcome.APPLIED, bob.ingest(signed))
        return alice to bob
    }

    /** Ingest each persisted outbox event twice; callers assert expected totals independently of peer agreement. */
    private suspend fun exchange(sender: Peer, receiver: Peer) {
        val queued = sender.outbox()
        assertTrue("This exchange must carry actual persisted rows", queued.isNotEmpty())
        queued.forEach { event ->
            assertTrue("Every wire envelope must verify", event.verify())
            assertTrue(receiver.ingest(event) in setOf(IngestOutcome.APPLIED, IngestOutcome.ALREADY_APPLIED))
            assertEquals(IngestOutcome.ALREADY_APPLIED, receiver.ingest(event))
        }
        assertEquals(sender.events.getEventIds(groupId).toSet(), receiver.events.getEventIds(groupId).toSet())
        assertEquals(sender.net(), receiver.net())
    }

    private suspend fun add(
        peer: Peer,
        other: Peer,
        id: String,
        amount: Long = 1_000,
        currency: String = "INR",
        type: SplitType = SplitType.EQUAL,
        splits: List<SplitEntry> = listOf(SplitEntry(peer.pub, amount / 2), SplitEntry(other.pub, amount - amount / 2))
    ) {
        AddExpenseUseCase(peer.expenses)(
            groupId, amount, currency, "Private dinner", peer.pub, type, splits,
            expenseId = id, createdAt = createdAt + 10, expectedAuthorPubkey = peer.pub
        )
    }

    private suspend fun lifecycle(wrapped: Boolean) {
        val (alice, bob) = bootstrap(wrapped)
        add(alice, bob, "dinner")
        assertEquals(mapOf((alice.pub to "INR") to 500L, (bob.pub to "INR") to -500L), alice.net())
        assertTrue("Receiver has not magically acquired the sender ledger", bob.net().isEmpty())
        val wire = alice.outbox().single { event ->
            if (wrapped) event.kind == 1059 else event.tags.any { it == listOf("t", "expense") }
        }
        assertFalse(wire.toJson().contains("Private dinner"))
        if (wrapped) assertEquals(bob.pub, wire.tags.single { it.first() == "p" }[1])
        val pending = alice.db.outboxDao().count()
        add(alice, bob, "dinner")
        assertEquals("Retrying a saved expense cannot enqueue a second delivery", pending, alice.db.outboxDao().count())
        exchange(alice, bob)

        val snapshot = CreateSnapshotUseCase(
            alice.events,
            alice.groups,
            alice.balances,
            encryption,
            alice.signer,
            alice.publisher,
            alice.identity.contract
        )
        assertTrue(snapshot(groupId))
        exchange(alice, bob)
        val original = checkNotNull(alice.expenses.getEditableExpense(groupId, "dinner", alice.pub)).expense
        CorrectExpenseUseCase(alice.expenses)(
            groupId, original.id, 1_400, "INR", "Corrected dinner", alice.pub, SplitType.EQUAL,
            listOf(SplitEntry(alice.pub, 700), SplitEntry(bob.pub, 700)), original.timestamp,
            expectedAuthorPubkey = alice.pub
        )
        exchange(alice, bob)
        assertEquals(mapOf((alice.pub to "INR") to 700L, (bob.pub to "INR") to -700L), bob.net())
        assertEquals(1_400L, bob.ledger.get(groupId, ExpenseIdentity(alice.pub, "dinner"))?.expense?.amount)
        assertEquals(
            listOf(DebtTransaction(bob.pub, alice.pub, 700, "INR")),
            SimplifyDebtsUseCase()(bob.balances(groupId))
        )

        val settlement = Settlement("repayment", bob.pub, alice.pub, 200, "INR", timestamp = createdAt + 20)
        bob.expenses.addSettlement(settlement, groupId)
        val settlementRows = bob.db.outboxDao().count()
        bob.expenses.addSettlement(settlement, groupId)
        assertEquals(settlementRows, bob.db.outboxDao().count())
        exchange(bob, alice)
        assertEquals(mapOf((alice.pub to "INR") to 500L, (bob.pub to "INR") to -500L), alice.net())

        add(alice, bob, "usd", amount = 600, currency = "USD")
        DeleteExpenseUseCase(alice.expenses)(groupId, "dinner", expectedAuthorPubkey = alice.pub)
        exchange(alice, bob)
        val expected = mapOf(
            (alice.pub to "INR") to -200L,
            (bob.pub to "INR") to 200L,
            (alice.pub to "USD") to 300L,
            (bob.pub to "USD") to -300L
        )
        assertEquals("Deleting an expense must not erase its independently recorded payment", expected, bob.net())
        assertNull(bob.ledger.get(groupId, ExpenseIdentity(alice.pub, "dinner")))
        assertNotNull(bob.ledger.get(groupId, ExpenseIdentity(alice.pub, "usd")))

        for (source in listOf(alice, bob)) {
            val backup = ExportGroupUseCase(source.events, source.groups, source.identity.contract)(groupId)
            assertFalse(backup.contains("Private dinner"))
            val restored = Peer(if (source === alice) 1 else 2, wrapped)
            assertNull(restored.groups.getById(groupId))
            assertEquals(source.events.getEventIds(groupId).size, restored.importer(backup))
            assertEquals(source.events.getEventIds(groupId).toSet(), restored.events.getEventIds(groupId).toSet())
            assertEquals(expected, restored.net())
            assertEquals(setOf(alice.pub, bob.pub), restored.groups.getById(groupId)?.members?.toSet())
            assertEquals(groupKey, restored.groups.getGroupKeyForEpoch(groupId, 0))
            assertEquals(0, restored.importer(backup))
            assertEquals("Restoration is not an outbox backup", 0, restored.db.outboxDao().count())
        }
    }

    @Test
    fun `direct delivery integrates edits settlements snapshots deletion and fresh database restore`() = runBlocking {
        lifecycle(wrapped = false)
    }

    @Test
    fun `default gift wrapping integrates edits settlements snapshots deletion and fresh database restore`() =
        runBlocking {
            lifecycle(wrapped = true)
        }

    @Test
    fun `all four split modes keep exact minor units through encrypted delivery and SQLite projections`() =
        runBlocking {
            val (alice, bob) = bootstrap(wrapped = true)
            val calculator = ExpenseSplitCalculator()
            var expectedCredit = 0L
            val cases = listOf(
                Triple(SplitType.EQUAL, emptyMap<String, String>(), 600L),
                Triple(SplitType.EXACT, mapOf(alice.pub to "3.00", bob.pub to "9.00"), 900L),
                Triple(SplitType.PERCENTAGE, mapOf(alice.pub to "25", bob.pub to "75"), 900L),
                Triple(SplitType.SHARES, mapOf(alice.pub to "1", bob.pub to "3"), 900L)
            )
            for ((type, inputs, expectedBobShare) in cases) {
                val result = calculator.calculate(1_200, "INR", type, setOf(alice.pub, bob.pub), inputs)
                assertNull(result.error)
                assertEquals(1_200L, result.splits.sumOf { it.share })
                assertEquals(expectedBobShare, result.splits.single { it.pubkey == bob.pub }.share)
                add(alice, bob, type.name, 1_200, type = type, splits = result.splits)
                exchange(alice, bob)
                expectedCredit += expectedBobShare
                assertEquals(
                    mapOf((alice.pub to "INR") to expectedCredit, (bob.pub to "INR") to -expectedCredit),
                    bob.net()
                )
                assertEquals(type, bob.ledger.get(groupId, ExpenseIdentity(alice.pub, type.name))?.expense?.splitType)
            }
        }

    @Test
    fun `same-identity backup with modified authenticated metadata is rejected before storage`() = runBlocking {
        val (alice, bob) = bootstrap(wrapped = true)
        add(alice, bob, "dinner")
        val backup = ExportGroupUseCase(alice.events, alice.groups, alice.identity.contract)(groupId)
        val modified = Json.decodeFromString<SplitFreeExport>(backup).copy(groupName = "Tampered name")
        val restored = Peer(1)

        assertThrows(IllegalArgumentException::class.java) { runBlocking { restored.importer(modified) } }
        assertNull(restored.groups.getById(groupId))
        assertNull(restored.groups.getGroupKeyForEpoch(groupId, 0))
        assertTrue(restored.events.getEventIds(groupId).isEmpty())
        assertEquals(0, restored.db.outboxDao().count())
    }

    @Test
    fun `tampered envelopes and wrong-identity backups leave receiver ledger unchanged`() = runBlocking {
        val (alice, bob) = bootstrap(wrapped = true)
        add(alice, bob, "dinner")
        val envelope = alice.outbox().single { it.kind == 1059 }
        val before = bob.events.getEventIds(groupId).toSet()
        assertEquals(IngestOutcome.REJECTED, bob.ingest(envelope.copy(content = "tampered")))
        assertEquals(before, bob.events.getEventIds(groupId).toSet())
        assertTrue(bob.net().isEmpty())
        exchange(alice, bob)

        val backup = ExportGroupUseCase(alice.events, alice.groups, alice.identity.contract)(groupId)
        val stranger = Peer(3)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { stranger.importer(backup) } }
        assertNull(stranger.groups.getById(groupId))
        assertTrue(stranger.events.getEventIds(groupId).isEmpty())
        assertEquals(0, stranger.db.outboxDao().count())
    }
}
