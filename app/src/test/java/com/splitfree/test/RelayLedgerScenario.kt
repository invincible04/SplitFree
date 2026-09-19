package com.splitfree.test

import androidx.room.Room
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.ExpenseRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.repository.RelaySyncCursors
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.sync.FlushResult
import com.splitfree.domain.model.sync.PullResult
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.expense.AddExpenseUseCase
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import com.splitfree.domain.usecase.expense.CorrectExpenseUseCase
import com.splitfree.domain.usecase.expense.DeleteExpenseUseCase
import com.splitfree.domain.usecase.expense.GetExpensesUseCase
import com.splitfree.domain.usecase.group.ControlOperationLock
import com.splitfree.domain.usecase.group.CreateGroupUseCase
import com.splitfree.domain.usecase.group.CreateInviteLinkUseCase
import com.splitfree.domain.usecase.group.JoinGroupUseCase
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.domain.validation.EventValidator
import com.splitfree.sync.event.EventPostProcessor
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.EventPublisher
import com.splitfree.sync.event.MembershipHistory
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment

/**
 * Shared live/offline scenario. Real identities (with fake secure storage), separate Room stores, group
 * creation/invites/join, money use cases, publisher/outbox, SyncEngine, ingestion and balance projection.
 * The immediate throttler and Android drain scheduler are disabled so the real engine owns each flush.
 * The caller supplies either real NostrClients or an explicitly simulated network boundary. No UI,
 * Android Keystore, physical devices, background timing or disk/process-restart behavior is certified.
 */
class RelayLedgerScenario(
    private val wrapped: Boolean,
    private val relays: List<String>,
    clientFactory: (CoroutineScope) -> NostrClient
) : AutoCloseable {
    val alice = Peer(wrapped, "Alice", clientFactory)
    val bob = Peer(wrapped, "Bob", clientFactory)
    lateinit var groupId: String
        private set

    class Peer(wrapped: Boolean, name: String, clientFactory: (CoroutineScope) -> NostrClient) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = clientFactory(scope)
        val identity = IdentityManager(RuntimeEnvironment.getApplication(), FakeSecureStorage()).apply {
            generateKeyPair()
        }
        val pub = identity.getPublicKeyHex()
        private val direct = Executor { it.run() }
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
            .allowMainThreadQueries()
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
            .build()
        val groups = GroupRepository(db.groupDao(), FakeSecureStorage())
        val events = EventRepository(db, db.eventDao())
        val encryption = GroupEncryption(CompressionUtil)
        val signer = EventSigner(identity)
        val settings = object : SettingsContract {
            override var giftWrapEnabled = wrapped
            override var displayName = name
            override fun transferDisplayNameIntent(oldPubkey: String, newPubkey: String) =
                com.splitfree.domain.repository.DisplayNameIntent(newPubkey, "test", displayName, 0)
        }
        val giftWrap = GiftWrapService(identity, settings)
        private val throttler = mockk<EventThrottler> {
            every { enqueue(any()) } throws IllegalStateException("Explicit test outbox flush owns delivery")
        }
        val publisher = EventPublisher(
            db.eventDao(), db.outboxDao(), db.deliveryDao(), throttler, mockk(relaxed = true),
            giftWrap, groups, identity, db, settings
        )
        val selfHeal = SelfHealUseCase(events, client, giftWrap, identity, groups)
        private val journal = ControlOperationJournal(db.controlOperationDao())
        private val lock = ControlOperationLock()
        private val rotation =
            RotateGroupKeyUseCase(groups, encryption, identity, signer, publisher, events, journal, lock)
        private val revocation = RevokeKeyUseCase(
            identity, groups, encryption, signer, publisher, journal, lock, mockk(relaxed = true), mockk(relaxed = true)
        )
        private val post = EventPostProcessor(groups, rotation, revocation, selfHeal, publisher, identity, scope)
        val processor = EventProcessor(
            db.eventDao(), groups, encryption, signer, identity, giftWrap, EventValidator(), post,
            MembershipHistory(db.eventDao(), groups, identity)
        )
        val engine = com.splitfree.sync.worker.SyncEngine(
            db.eventDao(),
            db.outboxDao(),
            groups,
            client,
            identity,
            processor,
            RelaySyncCursors(db)
        )
        val expenses = ExpenseRepository(
            db.eventDao(),
            groups,
            encryption,
            identity,
            signer,
            publisher,
            MembershipHistory(db.eventDao(), groups, identity)
        )
        val compute = ComputeBalancesUseCase(events, groups, encryption)
        val ledger = GetExpensesUseCase(events, groups, encryption)

        suspend fun connect(relays: List<String>) {
            client.authSigner = signer::createAuthEvent
            client.connect(relays)
            withTimeout(15_000) { client.connectionState.first { it } }
        }

        suspend fun pending(): List<NostrEvent> = db.outboxDao().getAll().map {
            checkNotNull(NostrEvent.fromJson(it.eventJson))
        }

        suspend fun flush(): List<NostrEvent> {
            val queued = pending()
            assertTrue("A delivery assertion must have queued events", queued.isNotEmpty())
            assertTrue(queued.all { it.verify() })
            assertEquals("Every queued event must be accepted", FlushResult(queued.size, 0), engine.flushOutbox())
            assertEquals("Accepted outbox rows must be removed", 0, db.outboxDao().count())
            return queued
        }

        suspend fun pull(groupId: String) {
            val key = checkNotNull(groups.getGroupKey(groupId))
            assertTrue("Every requested relay must complete history", engine.pullEvents(groupId, 0, key).complete)
        }

        suspend fun ids(groupId: String): Set<String> = events.getEventIds(groupId).toSet()

        suspend fun net(groupId: String): Map<Pair<String, String>, Long> =
            compute(groupId).associate { (it.pubkey to it.currency) to it.net }

        override fun close() {
            client.disconnect()
            scope.cancel()
            db.close()
        }
    }

    suspend fun createAndJoin() {
        val connections = (relays + RelayDefaults.FALLBACK_RELAYS).distinct()
        alice.connect(connections)
        bob.connect(connections)
        val group = CreateGroupUseCase(
            alice.groups,
            alice.encryption,
            alice.identity,
            alice.signer,
            alice.publisher,
            alice.settings
        )("Disposable relay test", relays, createdAt = System.currentTimeMillis() / 1000 - 10)
        groupId = group.id
        val creation = alice.flush().single()
        val invite = CreateInviteLinkUseCase(alice.groups)(groupId)
        val strictInitialSync = object : SyncEngineContract by bob.engine {
            override suspend fun pullEvents(
                groupId: String,
                since: Long,
                groupKey: String,
                lenientTimestamp: Boolean
            ): PullResult = bob.engine.pullEvents(groupId, since, groupKey, lenientTimestamp).also {
                assertTrue("The join's initial history must complete", it.complete)
            }
        }
        JoinGroupUseCase(
            bob.groups, bob.identity, bob.client, bob.signer, bob.encryption, bob.publisher,
            bob.selfHeal, strictInitialSync, bob.settings, bob.events
        )(invite)
        assertTrue("Join must actually ingest the creator's relay event", creation.id in bob.ids(groupId))
        val announcement = bob.flush().single()
        alice.pull(groupId)
        val expectedIds = setOf(creation.id, announcement.id)
        assertEquals(expectedIds, alice.ids(groupId))
        assertEquals(expectedIds, bob.ids(groupId))
        for (peer in listOf(alice, bob)) {
            val joined = checkNotNull(peer.groups.getById(groupId))
            assertEquals(setOf(alice.pub, bob.pub), joined.members.toSet())
            assertEquals(alice.pub, joined.createdBy)
            assertEquals("Disposable relay test", joined.name)
        }
        assertEquals(alice.groups.getGroupKey(groupId), bob.groups.getGroupKey(groupId))
    }

    suspend fun add(peer: Peer, id: String, amount: Long, aliceShare: Long, type: SplitType = SplitType.EQUAL) {
        AddExpenseUseCase(peer.expenses)(
            groupId, amount, "INR", "Disposable $id", peer.pub, type,
            listOf(SplitEntry(alice.pub, aliceShare), SplitEntry(bob.pub, amount - aliceShare)),
            expenseId = id, expectedAuthorPubkey = peer.pub
        )
    }

    suspend fun exchange(sender: Peer, receiver: Peer, aliceNet: Long) {
        val before = receiver.ids(groupId)
        val expected = sender.ids(groupId)
        assertTrue("Receiver must lack an authored update before transport", (expected - before).isNotEmpty())
        val queued = sender.pending()
        assertEquals(
            "One recipient envelope per new money event",
            expected - before,
            queued.map { envelope ->
                assertEquals(
                    "Actual wire kind must match the tested privacy mode",
                    if (wrapped) 1059 else 30078,
                    envelope.kind
                )
                assertTrue(listOf("g", groupId) in envelope.tags)
                val inner = if (wrapped) {
                    assertEquals(receiver.pub, envelope.tags.single { it.firstOrNull() == "p" }[1])
                    val unwrapped = checkNotNull(receiver.giftWrap.tryUnwrap(envelope))
                    assertEquals(sender.pub, unwrapped.senderPubkey)
                    unwrapped.rumor
                } else {
                    envelope
                }
                val original = checkNotNull(sender.db.eventDao().getEvent(inner.id)?.originalEventJson)
                val signed = checkNotNull(NostrEvent.fromJson(original))
                assertEquals(if (wrapped) signed.copy(sig = "") else signed, inner)
                inner.id
            }.toSet()
        )
        assertEquals(expected.size - before.size, queued.size)
        sender.flush()
        receiver.pull(groupId)
        assertEquals("Receiver must store every exact authored event", expected, receiver.ids(groupId))
        val expectedBalances = mapOf((alice.pub to "INR") to aliceNet, (bob.pub to "INR") to -aliceNet)
        assertEquals(expectedBalances, sender.net(groupId))
        assertEquals(expectedBalances, receiver.net(groupId))
        receiver.pull(groupId)
        assertEquals("Repeated history must not duplicate ledger rows", expected, receiver.ids(groupId))
        assertEquals(expectedBalances, receiver.net(groupId))
    }

    /** Both peers independently project the ledger after each direction of expense delivery. */
    suspend fun bidirectionalExpenses() {
        createAndJoin()
        add(alice, "dinner", 150_000, 75_000)
        exchange(alice, bob, 75_000)
        add(bob, "cab", 40_000, 25_000, SplitType.EXACT)
        exchange(bob, alice, 50_000)
    }

    /** Real correction/deletion commands, not test-side selection of which expenses should still count. */
    suspend fun mutations() {
        bidirectionalExpenses()
        add(alice, "groceries", 80_000, 40_000)
        exchange(alice, bob, 90_000)
        val original = checkNotNull(bob.expenses.getEditableExpense(groupId, "cab", bob.pub)).expense
        CorrectExpenseUseCase(bob.expenses)(
            groupId, "cab", 35_000, "INR", "Corrected cab", bob.pub, SplitType.EQUAL,
            listOf(SplitEntry(alice.pub, 17_500), SplitEntry(bob.pub, 17_500)), original.timestamp,
            expectedAuthorPubkey = bob.pub
        )
        exchange(bob, alice, 97_500)
        assertEquals(35_000L, alice.ledger.get(groupId, ExpenseIdentity(bob.pub, "cab"))?.expense?.amount)
        bob.expenses.addSettlement(
            Settlement("repayment", bob.pub, alice.pub, 7_500, "INR", timestamp = System.currentTimeMillis() / 1000),
            groupId
        )
        exchange(bob, alice, 90_000)
        DeleteExpenseUseCase(bob.expenses)(groupId, "cab", expectedAuthorPubkey = bob.pub)
        exchange(bob, alice, 107_500)
        assertNull(alice.ledger.get(groupId, ExpenseIdentity(bob.pub, "cab")))
        assertNotNull(alice.ledger.get(groupId, ExpenseIdentity(alice.pub, "dinner")))
        assertFalse(alice.ids(groupId).isEmpty())
    }

    override fun close() {
        try {
            alice.close()
        } finally {
            bob.close()
        }
    }
}
