package com.splitfree.domain.usecase.expense

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.EventRepository
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
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Offline Room ingestion with BIP-340 signatures and NIP-44 encryption. Different authors sharing a
 * settlement id must both count; repeated versions from one author must count once.
 * Events enter RECONCILIATION directly with fake key storage and mocked transport; no relay or UI is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SettlementIdentityRoomTest {
    private val devices = mutableListOf<Device>()
    private val groupId = UUID.randomUUID().toString()
    private val encryption = GroupEncryption(CompressionUtil)
    private val groupKey = encryption.generateGroupKey()
    private val json = Json { ignoreUnknownKeys = true }
    private val direct = Executor { it.run() }

    /** Isolated Room peer with fixture identity, fake key storage and production ingestion. */
    private inner class Device(seed: Int) {
        val identity = TestIdentity(seed)
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
        val settings = mockk<SettingsContract>().also { every { it.giftWrapEnabled } returns true }
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
        val postProcessor = EventPostProcessor(
            groupRepo,
            rotate,
            mockk<RevokeKeyUseCase>(relaxed = true),
            mockk<SelfHealUseCase>(relaxed = true),
            publisher,
            identity.contract,
            scope
        )
        val processor = EventProcessor(
            db.eventDao(),
            groupRepo,
            encryption,
            signer,
            identity.contract,
            giftWrap,
            EventValidator(),
            postProcessor,
            MembershipHistory(db.eventDao(), groupRepo, identity.contract)
        )
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

        fun row(id: String): EventEntity? = runBlocking { db.eventDao().getEvent(id) }

        fun ingest(event: NostrEvent): IngestOutcome = runBlocking {
            processor.process(event, context = IngestionContext.RECONCILIATION, expectedGroupId = groupId).outcome
        }

        fun net(pubkey: String): Long = runBlocking { computeBalances(groupId) }.single { it.pubkey == pubkey }.net
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
            System.currentTimeMillis() / 1000
        )
        return author.signer.createSignedEvent(
            groupId,
            "expense",
            encryption.encrypt(json.encodeToString(Expense.serializer(), value), groupKey),
            expenseUuid = id
        )
    }

    /** A real signed settlement between two members, authored by its payer. */
    private fun settlement(author: Device, payee: String, amount: Long, id: String, createdAt: Long): NostrEvent {
        val value =
            Settlement(id, from = author.pub, to = payee, amount = amount, currency = "USD", timestamp = createdAt)
        return author.signer.createSignedEvent(
            groupId,
            "settlement",
            encryption.encrypt(json.encodeToString(Settlement.serializer(), value), groupKey),
            expenseUuid = id,
            createdAt = createdAt
        )
    }

    @Test
    fun `a settlement uuid shared by an unrelated member pair cannot erase another pair's repayment`() {
        val payer = Device(1)
        val payee = Device(2)
        val otherPayer = Device(3)
        val otherPayee = Device(4)
        val everyone = listOf(payer, payee, otherPayer, otherPayee)
        val members = everyone.map { it.pub }
        everyone.forEach { it.join(members, payer.pub) }

        // payee fronts 100 split evenly with payer, so payer owes 50.
        assertEquals(IngestOutcome.APPLIED, payer.ingest(expense(payee, payer.pub)))
        assertEquals(-50L, payer.net(payer.pub))
        assertEquals(50L, payer.net(payee.pub))

        // Different authors reuse the settlement uuid; the unrelated pair has the earlier timestamp.
        val uuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis() / 1000
        val repayment = settlement(payer, payee.pub, amount = 50, id = uuid, createdAt = now - 1_800)
        val unrelated = settlement(otherPayer, otherPayee.pub, amount = 1, id = uuid, createdAt = now - 3_600)

        // Store the repayment first, then the older unrelated settlement.
        assertEquals(IngestOutcome.APPLIED, payer.ingest(repayment))
        assertEquals(IngestOutcome.APPLIED, payer.ingest(unrelated))
        assertNotEquals(repayment.id, unrelated.id)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, payer.row(repayment.id)?.applyState)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, payer.row(unrelated.id)?.applyState)
        assertEquals(uuid, payer.row(repayment.id)?.expenseUuid)
        assertEquals(uuid, payer.row(unrelated.id)?.expenseUuid)

        // The unrelated settlement contributes only to its own pair.
        assertEquals(1L, payer.net(otherPayer.pub))
        assertEquals(-1L, payer.net(otherPayee.pub))

        // The shared uuid must not suppress the 50 repayment.
        assertEquals("the unrelated pair's uuid suppressed a stored 50 repayment", 0L, payer.net(payee.pub))
        assertEquals("the unrelated pair's uuid suppressed a stored 50 repayment", 0L, payer.net(payer.pub))
    }

    @Test
    fun `the same author's re-sent settlement is stored twice but counted once`() {
        val payer = Device(1)
        val payee = Device(2)
        val members = listOf(payer.pub, payee.pub)
        listOf(payer, payee).forEach { it.join(members, payer.pub) }
        assertEquals(IngestOutcome.APPLIED, payer.ingest(expense(payee, payer.pub)))

        // Same author, id and amount, but new payload/envelope timestamps: count one settlement.
        val uuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis() / 1000
        val first = settlement(payer, payee.pub, amount = 50, id = uuid, createdAt = now - 1_800)
        val retry = settlement(payer, payee.pub, amount = 50, id = uuid, createdAt = now - 900)
        assertNotEquals(first.id, retry.id)
        assertEquals(IngestOutcome.APPLIED, payer.ingest(first))
        assertEquals(IngestOutcome.APPLIED, payer.ingest(retry))
        assertEquals(EventEntity.APPLY_STATE_APPLIED, payer.row(retry.id)?.applyState)

        assertEquals(0L, payer.net(payer.pub))
        assertEquals(0L, payer.net(payee.pub))
    }
}
