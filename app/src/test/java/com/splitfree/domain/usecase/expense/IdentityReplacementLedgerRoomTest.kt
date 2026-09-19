package com.splitfree.domain.usecase.expense

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.ExpenseRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.IdentityHistoryPage
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.group.ControlOperationLock
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
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
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Offline Room peers exercise signed ingestion, identity replacement and settlement from either party.
 * Events are passed directly to ingestion; revocation helpers may call the handler directly.
 * LIVE denotes ingestion policy, not relay delivery. Key storage and transport are fakes; no UI is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class IdentityReplacementLedgerRoomTest {
    private val devices = mutableListOf<Device>()
    private val groupId = UUID.randomUUID().toString()
    private val encryption = GroupEncryption(CompressionUtil)
    private val groupKey = encryption.generateGroupKey()
    private val json = Json { ignoreUnknownKeys = true }
    private val direct = Executor { it.run() }

    /** Default fixture timestamps advance by one second and remain within LIVE ingestion age limits. */
    private var clock = System.currentTimeMillis() / 1000 - 600

    private inner class Device(seed: Int) {
        val identity = TestIdentity(seed).also {
            every { it.contract.identityState() } returns com.splitfree.domain.repository.IdentityState.READY
            every { it.contract.stagedIdentitySwitch() } returns null
        }
        val pub = identity.pub
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
            .allowMainThreadQueries()
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
            .build()
        val groupRepo = GroupRepository(db.groupDao(), FakeSecureStorage())
        val signer = EventSigner(identity.contract)
        val settings = mockk<SettingsContract>().also { every { it.giftWrapEnabled } returns false }
        val giftWrap = GiftWrapService(identity.contract, settings)
        val eventRepo = EventRepository(db, db.eventDao())
        val publisher = EventPublisher(
            db.eventDao(),
            db.outboxDao(),
            db.deliveryDao(),
            mockk<EventThrottler>(relaxed = true),
            mockk(relaxed = true),
            giftWrap,
            groupRepo,
            identity.contract,
            db, settings
        )
        val rotate = RotateGroupKeyUseCase(
            groupRepo,
            encryption,
            identity.contract,
            signer,
            publisher,
            eventRepo,
            ControlOperationJournal(db.controlOperationDao()),
            ControlOperationLock()
        )
        val revokeKey = RevokeKeyUseCase(
            identity.contract,
            groupRepo,
            encryption,
            signer,
            publisher,
            ControlOperationJournal(db.controlOperationDao()),
            ControlOperationLock(),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        val heal = mockk<SelfHealUseCase>(relaxed = true)
        val postProcessor = EventPostProcessor(
            groupRepo,
            rotate,
            revokeKey,
            heal,
            publisher,
            identity.contract,
            scope
        )
        val history = MembershipHistory(db.eventDao(), groupRepo, identity.contract)
        val processor = EventProcessor(
            db.eventDao(),
            groupRepo,
            encryption,
            signer,
            identity.contract,
            giftWrap,
            EventValidator(),
            postProcessor,
            history
        )
        val expenses =
            ExpenseRepository(db.eventDao(), groupRepo, encryption, identity.contract, signer, publisher, history)
        val computeBalances = ComputeBalancesUseCase(eventRepo, groupRepo, encryption)

        init {
            devices += this
        }

        fun join(members: List<String>, creator: String) = runBlocking {
            groupRepo.save(
                Group(groupId, "Room", createdBy = creator, createdAt = 1_000, members = members, relays = emptyList()),
                groupKey
            )
        }

        /**
         * Real replacements include successor-signed history; legacy removals deliberately have no evidence.
         * The handler call keeps these financial tests independent of revocation delivery order.
         */
        fun revoke(old: String, new: String, successorKey: ByteArray? = null) = runBlocking {
            if (successorKey == null) {
                revokeAt(old, new, null, ++clock, "legacy-$clock-${old.take(6)}")
            } else {
                val author = devices.first { it.pub == old }
                val successor = devices.first { it.pub == new }
                val event = revocationEvent(author, new, successorKey)
                val payload = encryption.decrypt(event.content, groupKey)
                assertTrue(revokeKey.handleRevocation(payload, old, groupId, event.createdAt, event.id))
                certifyHistory(event, successor)
            }
        }

        /** [revoke] with an explicit clock, for records whose canonical order differs from their arrival order. */
        fun revokeAt(old: String, new: String, successorKey: ByteArray?, at: Long, eventId: String) = runBlocking {
            val proof = successorKey?.let { KeyRevocation.proveSuccessor(groupId, old, new, it) }.orEmpty()
            val payload = json.encodeToString(KeyRevocation(old, new, "Key compromised", successorProof = proof))
            assertTrue(revokeKey.handleRevocation(payload, old, groupId, at, eventId))
        }

        fun certifyHistory(revocation: NostrEvent, successor: Device) = runBlocking {
            val ids = eventRepo.getEventsByGroup(groupId)
                .filter { it.pubkey == revocation.pubkey && it.eventType in IdentityHistoryPage.MONEY_TYPES }
                .map { it.eventId }
            IdentityHistoryPage.create(groupId, revocation.pubkey, successor.pub, revocation, ids).forEach { page ->
                val event = successor.signer.createSignedEvent(
                    groupId,
                    IdentityHistoryPage.TYPE,
                    encryption.encrypt(json.encodeToString(page), groupKey),
                    createdAt = revocation.createdAt
                )
                assertEquals(IngestOutcome.APPLIED, ingest(event, IngestionContext.RECONCILIATION))
            }
        }

        fun assertUnavailable() {
            assertThrows(BalanceUnavailableException::class.java) { balances() }
        }

        fun retired() = runBlocking { groupRepo.retiredIdentities(groupId) }

        fun createdBy() = runBlocking { groupRepo.getById(groupId) }?.createdBy

        fun ingest(event: NostrEvent, context: IngestionContext = IngestionContext.LIVE): IngestOutcome = runBlocking {
            processor.process(event, context = context, expectedGroupId = groupId).outcome
        }

        fun balances(): Map<String, Long> = runBlocking { computeBalances(groupId) }.associate { it.pubkey to it.net }

        fun settle(from: String, to: String, amount: Long): NostrEvent = runBlocking {
            val settlement =
                Settlement(UUID.randomUUID().toString(), from, to, amount, "USD", timestamp = ++clock)
            expenses.addSettlement(settlement, groupId)
            val row = db.eventDao().getEventsByType(groupId, "settlement").last { it.pubkey == pub }
            checkNotNull(NostrEvent.fromJson(checkNotNull(row.originalEventJson)))
        }

        fun members(): List<String> = runBlocking { groupRepo.getMembers(groupId) }
    }

    @After
    fun cleanup() {
        devices.forEach { it.scope.cancel() }
        devices.forEach { it.db.close() }
    }

    /** A real signed expense: [author] fronts [amount] split evenly with [other]. */
    private fun expense(author: Device, other: String, amount: Long = 100): NostrEvent {
        val id = UUID.randomUUID().toString()
        val value = Expense(
            id,
            amount,
            "USD",
            "Room",
            author.pub,
            SplitType.EQUAL,
            listOf(SplitEntry(author.pub, amount / 2), SplitEntry(other, amount - amount / 2)),
            ++clock
        )
        return author.signer.createSignedEvent(
            groupId,
            "expense",
            encryption.encrypt(json.encodeToString(Expense.serializer(), value), groupKey),
            expenseUuid = id,
            createdAt = clock
        )
    }

    private fun everyoneIngests(event: NostrEvent, vararg into: Device) {
        for (device in into) assertEquals(IngestOutcome.APPLIED, device.ingest(event))
    }

    /** Signed revocation from [author]; successor possession is proven only when [successorKey] is supplied. */
    private fun revocationEvent(author: Device, successor: String, successorKey: ByteArray? = null): NostrEvent {
        val proof = successorKey?.let { KeyRevocation.proveSuccessor(groupId, author.pub, successor, it) }.orEmpty()
        val payload = KeyRevocation(author.pub, successor, "Key compromised", successorProof = proof)
        return author.signer.createSignedEvent(
            groupId,
            "key_revocation",
            encryption.encrypt(json.encodeToString(payload), groupKey),
            createdAt = ++clock
        )
    }

    @Test
    fun `a debt owed by a replaced member follows the new identity and the new identity can settle it`() {
        val alice = Device(1)
        val bob = Device(2)
        val bob2 = Device(3)
        listOf(alice, bob, bob2).forEach { it.join(listOf(alice.pub, bob.pub), alice.pub) }

        // Alice fronts 100 split evenly: bob owes her 50, on every device.
        everyoneIngests(expense(alice, bob.pub), alice, bob, bob2)
        assertEquals(mapOf(alice.pub to 50L, bob.pub to -50L), alice.balances())

        // Bob replaces his identity; every device applies the same revocation.
        listOf(alice, bob, bob2).forEach { it.revoke(bob.pub, bob2.pub, bob2.identity.priv) }
        assertEquals(listOf(alice.pub, bob2.pub), alice.members())

        // Bob2 inherits the 50 debt; Bob's old key is absent from the projected balances.
        assertEquals(mapOf(alice.pub to 50L, bob2.pub to -50L), alice.balances())
        assertEquals(mapOf(alice.pub to 50L, bob2.pub to -50L), bob2.balances())

        // Bob2 records repayment; Alice accepts it under LIVE ingestion rules and both ledgers settle to zero.
        val repayment = bob2.settle(from = bob2.pub, to = alice.pub, amount = 50)
        assertEquals(mapOf(alice.pub to 0L, bob2.pub to 0L), bob2.balances())
        assertEquals(IngestOutcome.APPLIED, alice.ingest(repayment))
        assertEquals(mapOf(alice.pub to 0L, bob2.pub to 0L), alice.balances())
    }

    @Test
    fun `the counterparty can settle a debt owed by a replaced member`() {
        val alice = Device(1)
        val bob = Device(2)
        val bob2 = Device(3)
        listOf(alice, bob2).forEach { it.join(listOf(alice.pub, bob.pub), alice.pub) }
        everyoneIngests(expense(alice, bob.pub), alice, bob2)
        listOf(alice, bob2).forEach { it.revoke(bob.pub, bob2.pub, bob2.identity.priv) }

        // The creditor records repayment from the replacement identity; both ledgers must settle to zero.
        val received = alice.settle(from = bob2.pub, to = alice.pub, amount = 50)
        assertEquals(mapOf(alice.pub to 0L, bob2.pub to 0L), alice.balances())
        assertEquals(IngestOutcome.APPLIED, bob2.ingest(received))
        assertEquals(mapOf(alice.pub to 0L, bob2.pub to 0L), bob2.balances())
    }

    @Test
    fun `a creditor creator who replaces their identity is still owed and can be repaid`() {
        val alice = Device(1)
        val alice2 = Device(2)
        val bob = Device(3)
        listOf(alice, alice2, bob).forEach { it.join(listOf(alice.pub, bob.pub), alice.pub) }
        everyoneIngests(expense(alice, bob.pub), alice, alice2, bob)

        listOf(alice, alice2, bob).forEach { it.revoke(alice.pub, alice2.pub, alice2.identity.priv) }
        assertEquals(alice2.pub, runBlocking { bob.groupRepo.getById(groupId) }?.createdBy)
        assertEquals(mapOf(alice2.pub to 50L, bob.pub to -50L), bob.balances())

        val repayment = bob.settle(from = bob.pub, to = alice2.pub, amount = 50)
        assertEquals(IngestOutcome.APPLIED, alice2.ingest(repayment))
        assertEquals(mapOf(alice2.pub to 0L, bob.pub to 0L), alice2.balances())
        assertEquals(mapOf(alice2.pub to 0L, bob.pub to 0L), bob.balances())
    }

    @Test
    fun `a replacement chain and activity before and after each hop fold into the final identity`() {
        val alice = Device(1)
        val bob = Device(2)
        val bob2 = Device(3)
        val bob3 = Device(4)
        listOf(alice, bob3).forEach { it.join(listOf(alice.pub, bob.pub), alice.pub) }

        everyoneIngests(expense(alice, bob.pub, amount = 100), alice, bob3) // bob -50
        listOf(alice, bob3).forEach { it.revoke(bob.pub, bob2.pub, bob2.identity.priv) }
        everyoneIngests(expense(alice, bob2.pub, amount = 40), alice, bob3) // bob2 -20
        listOf(alice, bob3).forEach { it.revoke(bob2.pub, bob3.pub, bob3.identity.priv) }
        everyoneIngests(expense(alice, bob3.pub, amount = 10), alice, bob3) // bob3 -5

        assertEquals(mapOf(alice.pub to 75L, bob3.pub to -75L), alice.balances())
        assertEquals(alice.balances(), bob3.balances())

        val repayment = bob3.settle(from = bob3.pub, to = alice.pub, amount = 75)
        assertEquals(IngestOutcome.APPLIED, alice.ingest(repayment))
        assertEquals(mapOf(alice.pub to 0L, bob3.pub to 0L), alice.balances())
    }

    @Test
    fun `legacy removal leaves balances unavailable while admitting a counterparty settlement`() {
        val alice = Device(1)
        val bob = Device(2)
        val carol = Device(3)
        val roster = listOf(alice.pub, bob.pub, carol.pub)
        listOf(alice, bob, carol).forEach { it.join(roster, alice.pub) }
        everyoneIngests(expense(alice, bob.pub), alice, bob, carol)

        // Legacy removal changes membership but cannot certify the retired identity's complete history.
        listOf(alice, carol).forEach { it.revoke(bob.pub, "") }
        assertEquals(listOf(alice.pub, carol.pub), alice.members())
        alice.assertUnavailable()

        // Alice records Bob's repayment; Carol accepts her event under LIVE ingestion rules.
        val received = alice.settle(from = bob.pub, to = alice.pub, amount = 50)
        alice.assertUnavailable()
        assertEquals(IngestOutcome.APPLIED, carol.ingest(received))
        carol.assertUnavailable()
    }

    @Test
    fun `legacy removal admits former counterparty settlement during reconciliation without certifying balances`() {
        val alice = Device(1)
        val bob = Device(2)
        val carol = Device(3)
        val roster = listOf(alice.pub, bob.pub, carol.pub)
        listOf(alice, bob, carol).forEach { it.join(roster, alice.pub) }
        everyoneIngests(expense(alice, bob.pub), alice, bob, carol)

        // Legacy removal changes membership but cannot certify the retired identity's complete history.
        listOf(alice, carol).forEach { it.revoke(bob.pub, "") }
        assertEquals(listOf(alice.pub, carol.pub), alice.members())
        alice.assertUnavailable()

        // Alice records Bob's repayment; Carol receives it later through reconciliation.
        val received = alice.settle(from = bob.pub, to = alice.pub, amount = 50)
        alice.assertUnavailable()
        assertEquals(IngestOutcome.APPLIED, carol.ingest(received, IngestionContext.RECONCILIATION))
        carol.assertUnavailable()
    }

    @Test
    fun `a removed identity cannot author a settlement and a stranger cannot be named as counterparty`() {
        val alice = Device(1)
        val bob = Device(2)
        val carol = Device(3)
        val mallory = Device(4)
        listOf(alice, bob, carol).forEach { it.join(listOf(alice.pub, bob.pub, carol.pub), alice.pub) }
        everyoneIngests(expense(alice, bob.pub), alice, bob, carol)
        listOf(alice, carol).forEach { it.revoke(bob.pub, "") }

        // Uncertified retired-key history is quarantined, never applied to the ledger.
        val receivers = listOf(alice, carol)
        val before = runBlocking {
            receivers.associateWith { it.eventRepo.getEventsByGroup(groupId).map { row -> row.eventId } }
        }
        val fromRemoved = bob.settle(from = bob.pub, to = alice.pub, amount = 50)
        assertEquals(IngestOutcome.DEFERRED, alice.ingest(fromRemoved))
        assertEquals(IngestOutcome.DEFERRED, carol.ingest(fromRemoved))
        assertEquals(IngestOutcome.DEFERRED, carol.ingest(fromRemoved, IngestionContext.RECONCILIATION))
        for (receiver in receivers) {
            runBlocking {
                val row = checkNotNull(receiver.db.eventDao().getEvent(fromRemoved.id))
                assertEquals(EventEntity.APPLY_STATE_AWAITING_HISTORY, row.applyState)
                assertEquals(fromRemoved.toJson(), row.originalEventJson)
                assertEquals(before.getValue(receiver), receiver.eventRepo.getEventsByGroup(groupId).map { it.eventId })
                assertEquals(0, receiver.db.outboxDao().count())
            }
            receiver.assertUnavailable()
        }

        // Alice cannot record a settlement with someone who was never a member.
        val refused = assertThrows(IllegalArgumentException::class.java) {
            alice.settle(from = mallory.pub, to = alice.pub, amount = 50)
        }
        assertTrue(refused.message.orEmpty().contains("not a member"))

        // Nor may a member forge a settlement they are not party to.
        assertThrows(IllegalArgumentException::class.java) {
            carol.settle(from = bob.pub, to = alice.pub, amount = 50)
        }
    }

    @Test
    fun `retired identities are read back with their final successor`() = runBlocking {
        val alice = Device(1)
        val bob = Device(2)
        val bob2 = Device(3)
        val bob3 = Device(4)
        alice.join(listOf(alice.pub, bob.pub), alice.pub)
        assertEquals(mapOf<String, String>(), alice.groupRepo.retiredIdentities(groupId).successors)

        alice.revoke(bob.pub, bob2.pub, bob2.identity.priv)
        alice.revoke(bob2.pub, bob3.pub, bob3.identity.priv)
        alice.revoke(alice.pub, "")

        val retired = alice.groupRepo.retiredIdentities(groupId)
        assertEquals(setOf(bob.pub, bob2.pub, alice.pub), retired.revoked)
        assertEquals(mapOf(bob.pub to bob3.pub, bob2.pub to bob3.pub), retired.successors)
        assertEquals(bob3.pub, retired.resolve(bob.pub))
        assertEquals(alice.pub, retired.resolve(alice.pub))
        assertFalse(retired.isRetired(bob3.pub))
        assertNull(retired.successors[alice.pub])
    }

    @Test
    fun `unproven creditor successor cannot erase debt or certify balances`() {
        val alice = Device(1)
        val bob = Device(2)
        listOf(alice, bob).forEach { it.join(listOf(alice.pub, bob.pub), alice.pub) }
        everyoneIngests(expense(alice, bob.pub), alice, bob)
        assertEquals(mapOf(alice.pub to 50L, bob.pub to -50L), alice.balances())

        // Bob's signed revocation names Alice but supplies no proof of possession of her key.
        assertEquals(IngestOutcome.APPLIED, alice.ingest(revocationEvent(bob, alice.pub)))

        assertEquals(listOf(alice.pub), alice.members())
        alice.assertUnavailable()
        assertEquals(alice.pub, runBlocking { alice.groupRepo.getById(groupId) }?.createdBy)

        // Alice can still record that Bob paid her in cash.
        alice.settle(from = bob.pub, to = alice.pub, amount = 50)
        alice.assertUnavailable()
    }

    @Test
    fun `a replacement without successor proof keeps attribution and requires history recovery`() {
        val alice = Device(1)
        val bob = Device(2)
        val bob2 = Device(3)
        listOf(alice, bob).forEach { it.join(listOf(alice.pub, bob.pub), alice.pub) }
        everyoneIngests(expense(alice, bob.pub), alice, bob)

        // Same as a legacy client's revocation: authentic, but silent about who holds the new key.
        assertEquals(IngestOutcome.APPLIED, alice.ingest(revocationEvent(bob, bob2.pub)))

        assertEquals(listOf(alice.pub, bob2.pub), alice.members())
        alice.assertUnavailable()
        assertEquals(bob.pub, runBlocking { alice.groupRepo.retiredIdentities(groupId) }.resolve(bob.pub))
    }

    @Test
    fun `a proven replacement received live moves the debt to the new key`() {
        val alice = Device(1)
        val bob = Device(2)
        val bob2 = Device(3)
        listOf(alice, bob).forEach { it.join(listOf(alice.pub, bob.pub), alice.pub) }
        everyoneIngests(expense(alice, bob.pub), alice, bob)

        val retirement = revocationEvent(bob, bob2.pub, bob2.identity.priv)
        assertEquals(IngestOutcome.APPLIED, alice.ingest(retirement))
        alice.certifyHistory(retirement, bob2)

        assertEquals(listOf(alice.pub, bob2.pub), alice.members())
        assertEquals(mapOf(alice.pub to 50L, bob2.pub to -50L), alice.balances())
    }

    @Test
    fun `a chain hop that arrives before its predecessor is retried into the same final identity`() {
        val alice = Device(1)
        val bob = Device(2)
        val bob2 = Device(3)
        val bob3 = Device(4)
        val inOrder = Device(5)
        listOf(alice, inOrder).forEach { it.join(listOf(alice.pub, bob.pub), alice.pub) }
        everyoneIngests(expense(alice, bob.pub), alice, inOrder)
        val firstHop = revocationEvent(bob, bob2.pub, bob2.identity.priv)
        val secondHop = revocationEvent(bob2, bob3.pub, bob3.identity.priv)

        // The second hop is rejected until Bob2 is admitted; explicit redelivery then succeeds.
        assertEquals(IngestOutcome.REJECTED, alice.ingest(secondHop))
        assertEquals(IngestOutcome.APPLIED, alice.ingest(firstHop))
        assertEquals(IngestOutcome.APPLIED, alice.ingest(secondHop))
        assertEquals(IngestOutcome.APPLIED, inOrder.ingest(firstHop))
        assertEquals(IngestOutcome.APPLIED, inOrder.ingest(secondHop))
        listOf(alice, inOrder).forEach {
            it.certifyHistory(firstHop, bob2)
            it.certifyHistory(secondHop, bob3)
        }

        assertEquals(mapOf(alice.pub to 50L, bob3.pub to -50L), inOrder.balances())
        assertEquals(inOrder.balances(), alice.balances())
        assertEquals(listOf(alice.pub, bob3.pub), inOrder.members())
        assertEquals(inOrder.members(), alice.members())
    }

    @Test
    fun `two devices that hear conflicting revocations in opposite orders converge`() {
        val alice = Device(1)
        val bob = Device(2)
        val bob2 = Device(3)
        val bob3 = Device(4)
        val inOrder = Device(5)
        listOf(alice, inOrder).forEach { it.join(listOf(alice.pub, bob.pub), alice.pub) }
        everyoneIngests(expense(alice, bob.pub), alice, inOrder)

        // Two handler calls supply successor proofs; the earlier record (T+1, "e") must win in either order.
        val t = ++clock
        alice.revokeAt(bob.pub, bob3.pub, bob3.identity.priv, t + 2, "l")
        alice.revokeAt(bob.pub, bob2.pub, bob2.identity.priv, t + 1, "e")
        inOrder.revokeAt(bob.pub, bob2.pub, bob2.identity.priv, t + 1, "e")
        inOrder.revokeAt(bob.pub, bob3.pub, bob3.identity.priv, t + 2, "l")

        for (device in listOf(alice, inOrder)) {
            assertEquals(listOf(alice.pub, bob2.pub), device.members())
            device.assertUnavailable()
            assertEquals(mapOf(bob.pub to bob2.pub), device.retired().successors)
            // bob3's seat existed only by virtue of the superseded record.
            assertFalse(bob3.pub in device.members())
            assertEquals(alice.pub, device.createdBy())
        }
    }

    @Test
    fun `a superseded successor who was independently a member keeps their seat`() {
        val alice = Device(1)
        val bob = Device(2)
        val carol = Device(3)
        val bob2 = Device(4)
        alice.join(listOf(alice.pub, bob.pub, carol.pub), alice.pub)
        everyoneIngests(expense(alice, bob.pub), alice)

        // The later record names carol, who already has a seat of her own: bob simply drops out.
        val t = ++clock
        alice.revokeAt(bob.pub, carol.pub, carol.identity.priv, t + 2, "l")
        assertEquals(listOf(alice.pub, carol.pub), alice.members())

        // The earlier record supersedes it: carol's independent seat is untouched, bob2 takes bob's.
        alice.revokeAt(bob.pub, bob2.pub, bob2.identity.priv, t + 1, "e")

        assertEquals(setOf(alice.pub, carol.pub, bob2.pub), alice.members().toSet())
        assertEquals(3, alice.members().size)
        alice.assertUnavailable()
        assertEquals(mapOf(bob.pub to bob2.pub), alice.retired().successors)
        assertEquals(alice.pub, alice.createdBy())
    }

    @Test
    fun `conflicting proven revocations converge on attribution but leave balances unavailable`() {
        val alice = Device(1)
        val bob = Device(2)
        val bob2 = Device(3)
        val bob3 = Device(4)
        val other = Device(5)
        listOf(alice, other).forEach { it.join(listOf(alice.pub, bob.pub), alice.pub) }
        everyoneIngests(expense(alice, bob.pub), alice, other)
        val early = revocationEvent(bob, bob2.pub, bob2.identity.priv)
        val late = revocationEvent(bob, bob3.pub, bob3.identity.priv)

        assertEquals(IngestOutcome.APPLIED, alice.ingest(late))
        assertEquals(IngestOutcome.APPLIED, alice.ingest(early))
        assertEquals(IngestOutcome.APPLIED, other.ingest(early))
        assertEquals(IngestOutcome.APPLIED, other.ingest(late))

        other.assertUnavailable()
        alice.assertUnavailable()
        assertEquals(other.retired().successors, alice.retired().successors)
    }

    @Test
    fun `superseding a creator replacement must move creator authority to canonical winner`() {
        val creatorDevice = Device(1)
        val independent = Device(2)
        val winner = Device(3)
        creatorDevice.join(listOf(creatorDevice.pub, independent.pub), creatorDevice.pub)
        val t = ++clock
        creatorDevice.revokeAt(creatorDevice.pub, independent.pub, independent.identity.priv, t + 2, "late")
        creatorDevice.revokeAt(creatorDevice.pub, winner.pub, winner.identity.priv, t + 1, "early")

        assertEquals(listOf(winner.pub, independent.pub).toSet(), creatorDevice.members().toSet())
        assertEquals(
            "Creator authority must follow the canonical earlier successor",
            winner.pub,
            creatorDevice.createdBy()
        )
    }

    @Test
    fun `superseding after later creator metadata removed old seat must not resurrect it`() = runBlocking {
        val creatorDevice = Device(1)
        val old = Device(2)
        val lateSuccessor = Device(3)
        val earlySuccessor = Device(4)
        creatorDevice.join(listOf(creatorDevice.pub, old.pub), creatorDevice.pub)
        val t = ++clock
        creatorDevice.revokeAt(old.pub, lateSuccessor.pub, lateSuccessor.identity.priv, t + 2, "late")
        assertTrue(
            creatorDevice.groupRepo.updateFromMeta(
                groupId, "Room", listOf(creatorDevice.pub), emptyList(), t + 3,
                createdBy = creatorDevice.pub, eventId = "meta", expectedKeyEpoch = 0,
                expectedCreator = creatorDevice.pub
            )
        )
        creatorDevice.revokeAt(old.pub, earlySuccessor.pub, earlySuccessor.identity.priv, t + 1, "early")

        assertFalse(
            "A seat deliberately removed by newer creator metadata must stay removed",
            earlySuccessor.pub in creatorDevice.members()
        )
        assertEquals(listOf(creatorDevice.pub), creatorDevice.members())
    }

    @Test
    fun `canonical accepted relay change migrates history but a stale meta does not`() = runBlocking {
        val creator = Device(1)
        creator.join(listOf(creator.pub), creator.pub)
        val now = ++clock
        fun event(at: Long, relay: String): NostrEvent {
            val meta = com.splitfree.domain.model.group.GroupMeta(
                name = "Room",
                createdBy = creator.pub,
                createdAt = 1000,
                members = listOf(creator.pub),
                relays = listOf(relay)
            )
            return creator.signer.createSignedEvent(
                groupId,
                "group_meta",
                encryption.encrypt(json.encodeToString(meta), groupKey),
                createdAt = at
            )
        }
        assertEquals(IngestOutcome.APPLIED, creator.ingest(event(now, "wss://relay.example")))
        io.mockk.coVerify(exactly = 1) { creator.heal(groupId) }
        assertEquals(IngestOutcome.APPLIED, creator.ingest(event(now - 1, "wss://stale.example")))
        io.mockk.coVerify(exactly = 1) { creator.heal(groupId) }
    }

    @Test
    fun `earlier competing retirement under retained old epoch is admitted after rotation`() = runBlocking {
        val creator = Device(1)
        val old = Device(2)
        val first = Device(3)
        val winner = Device(4)
        creator.join(listOf(creator.pub, old.pub), creator.pub)
        val early = revocationEvent(old, winner.pub, winner.identity.priv)
        val late = revocationEvent(old, first.pub, first.identity.priv)
        assertEquals(IngestOutcome.APPLIED, creator.ingest(late))
        creator.groupRepo.saveGroupKeyForEpoch(groupId, 1, encryption.generateGroupKey())
        assertTrue(creator.groupRepo.applyKeyRotation(groupId, 1, listOf(creator.pub), emptyMap()))
        assertEquals(IngestOutcome.APPLIED, creator.ingest(early))
        assertEquals(winner.pub, creator.retired().resolve(old.pub))
        assertEquals(listOf(creator.pub), creator.members())
        assertEquals(0, creator.db.eventDao().getEvent(early.id)!!.keyEpoch)
    }
}
