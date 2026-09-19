package com.splitfree.sync.event

import android.app.Application
import androidx.room.Room
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.ExpenseRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.settings.UserPreferences
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
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.IdentityHistoryPage
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import com.splitfree.domain.usecase.group.ControlOperationLock
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.validation.EventValidator
import com.splitfree.test.FakeSecureStorage
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Room, personal-key promotion and signed control/ledger ingestion; no network or hardware Keystore. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class IdentityRotationHistoryRoomTest {
    private val devices = mutableListOf<Device>()
    private val encryption = GroupEncryption(CompressionUtil)
    private val initialKey = encryption.generateGroupKey()
    private val createdAt = System.currentTimeMillis() / 1000 - 1000
    private lateinit var groupId: String

    private inner class Device(seed: Int) {
        val app: Application = RuntimeEnvironment.getApplication()
        val identity = IdentityManager(app, FakeSecureStorage()).apply {
            importKey("00".repeat(31) + seed.toString(16).padStart(2, '0'))
        }
        val pub get() = identity.getPublicKeyHex()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() }).build()
        val groups = GroupRepository(db.groupDao(), FakeSecureStorage())
        val events = EventRepository(db, db.eventDao())
        val settings = UserPreferences(app).apply { giftWrapEnabled = false }
        val signer = EventSigner(identity)
        val journal = ControlOperationJournal(db.controlOperationDao())
        val lock = ControlOperationLock()
        val history = MembershipHistory(db.eventDao(), groups, identity)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val publisher = EventPublisher(
            db.eventDao(), db.outboxDao(), db.deliveryDao(), mockk(relaxed = true), mockk(relaxed = true),
            GiftWrapService(identity, settings), groups, identity, db, settings
        )
        val rotate = RotateGroupKeyUseCase(groups, encryption, identity, signer, publisher, events, journal, lock)
        val revoke = RevokeKeyUseCase(identity, groups, encryption, signer, publisher, journal, lock, settings, history)
        val processor = EventProcessor(
            db.eventDao(), groups, encryption, signer, identity, GiftWrapService(identity, settings), EventValidator(),
            EventPostProcessor(groups, rotate, revoke, mockk(relaxed = true), publisher, identity, scope), history
        )
        val expenses = ExpenseRepository(db.eventDao(), groups, encryption, identity, signer, publisher, history)
        val compute = ComputeBalancesUseCase(events, groups, encryption)

        init {
            devices += this
        }

        suspend fun join(roster: List<String>, creator: String) {
            groups.save(
                Group(
                    groupId,
                    "Trip",
                    createdBy = creator,
                    createdAt = createdAt,
                    members = roster,
                    relays = emptyList()
                ),
                initialKey
            )
        }

        suspend fun balances() = compute(groupId).associate { it.pubkey to it.net }

        suspend fun event(id: String): NostrEvent = checkNotNull(
            NostrEvent.fromJson(checkNotNull(db.eventDao().getEvent(id)?.originalEventJson))
        )

        suspend fun ingest(event: NostrEvent, context: IngestionContext = IngestionContext.RECONCILIATION) {
            val result = processor.process(event, context = context, expectedGroupId = groupId)
            assertTrue(
                "${event.tags}: $result",
                result.outcome == IngestOutcome.APPLIED ||
                    result.outcome == IngestOutcome.ALREADY_APPLIED
            )
        }
    }

    @After fun cleanup() {
        devices.forEach {
            it.scope.cancel()
            it.db.close()
        }
    }

    @Test fun `rotation removal then real identity replacement retains settlement and peer balances`() = runBlocking {
        verifyReplacement(legacyCheckpoint = false)
    }

    @Test fun `legacy rotation evidence is retained before the old personal key is destroyed`() = runBlocking {
        verifyReplacement(legacyCheckpoint = true)
    }

    @Test fun `retaining legacy rotation facts leaves all projected group fields unchanged`() = runBlocking {
        verifyReplacement(legacyCheckpoint = true, verifyNeutralRetention = true)
    }

    private suspend fun verifyReplacement(legacyCheckpoint: Boolean, verifyNeutralRetention: Boolean = false) {
        val alice = Device(1)
        val bob = Device(2)
        val carol = Device(3)
        val oldAlice = alice.pub
        groupId = GroupIdentity.derive(oldAlice, createdAt)
        val roster = listOf(oldAlice, bob.pub, carol.pub)
        listOf(alice, carol).forEach { it.join(roster, oldAlice) }
        val expense = Expense(
            UUID.randomUUID().toString(),
            100,
            "USD",
            "Dinner",
            oldAlice,
            SplitType.EQUAL,
            listOf(SplitEntry(oldAlice, 50), SplitEntry(bob.pub, 50)),
            createdAt + 1
        )
        alice.expenses.addExpense(expense, groupId)
        val expenseRow = alice.db.eventDao().getEventsByType(groupId, "expense").single()
        carol.ingest(alice.event(expenseRow.eventId))
        assertEquals(mapOf(oldAlice to 50L, bob.pub to -50L), alice.balances())

        alice.rotate(groupId, bob.pub)
        val rotations = alice.db.eventDao().getEventsByType(groupId, "key_rotation")
        val forCarol = rotations.map { alice.event(it.eventId) }.single { event ->
            event.tags.any { it == listOf("p", carol.pub) }
        }
        carol.ingest(forCarol)
        val meta = alice.db.eventDao().getEventsByType(groupId, "group_meta").last()
        carol.ingest(alice.event(meta.eventId))
        assertTrue(bob.pub in alice.history.formerMembers(groupId))
        assertTrue(bob.pub in carol.history.formerMembers(groupId))
        assertEquals(1, alice.history.removalEpochOf(groupId, bob.pub))
        if (legacyCheckpoint) {
            // Simulates the v4 -> v5 additive migration: projected fields survive, facts start empty.
            val row = checkNotNull(alice.db.groupDao().getById(groupId))
            alice.db.groupDao().update(row.copy(projectionJson = ""))
            assertTrue(alice.groups.authenticatedRotations(groupId).isEmpty())
        }
        val originalRotationRows = alice.db.eventDao().getEventsByType(groupId, "key_rotation")
        if (verifyNeutralRetention) {
            val beforeRetention = checkNotNull(alice.db.groupDao().getById(groupId))
            alice.history.retainRotationHistory(groupId)
            val afterRetention = checkNotNull(alice.db.groupDao().getById(groupId))
            assertEquals(beforeRetention.copy(projectionJson = afterRetention.projectionJson), afterRetention)
            assertTrue(alice.groups.authenticatedRotations(groupId).isNotEmpty())
        }
        val successor = alice.revoke()
        assertFalse(oldAlice == successor)
        assertEquals(successor, alice.pub)
        assertFalse(alice.identity.hasPendingKeyPair())
        assertEquals(originalRotationRows, alice.db.eventDao().getEventsByType(groupId, "key_rotation"))
        assertTrue(alice.groups.authenticatedRotations(groupId).isNotEmpty())
        assertTrue(
            bob.pub in MembershipHistory(alice.db.eventDao(), alice.groups, alice.identity).formerMembers(groupId)
        )
        assertEquals(1, alice.history.removalEpochOf(groupId, bob.pub))
        assertEquals(mapOf(successor to 50L, bob.pub to -50L), alice.balances())
        val revocation = alice.db.eventDao().getEventsByType(groupId, "key_revocation").single()
        carol.ingest(alice.event(revocation.eventId))
        val checkpoints = alice.db.eventDao().getEventsByType(groupId, IdentityHistoryPage.TYPE)
        assertTrue(checkpoints.isNotEmpty())
        checkpoints.forEach { carol.ingest(alice.event(it.eventId)) }
        val epochKey = checkNotNull(alice.groups.getGroupKeyForEpoch(groupId, 1))
        val companion = alice.db.eventDao().getEventsByType(groupId, "group_meta").single {
            Json.decodeFromString<GroupMeta>(encryption.decrypt(it.contentEncrypted, epochKey))
                .creatorTransitions.isNotEmpty()
        }
        carol.ingest(alice.event(companion.eventId))
        assertEquals(alice.balances(), carol.balances())

        val forged = Expense(
            UUID.randomUUID().toString(),
            100,
            "USD",
            "Post-removal",
            bob.pub,
            SplitType.EQUAL,
            listOf(SplitEntry(bob.pub, 50), SplitEntry(successor, 50)),
            System.currentTimeMillis() / 1000
        )
        val forgedEvent = bob.signer.createSignedEvent(
            groupId,
            "expense",
            encryption.encrypt(Json.encodeToString(Expense.serializer(), forged), epochKey),
            expenseUuid = forged.id
        )
        val rejected = alice.processor.process(
            forgedEvent,
            context = IngestionContext.RECONCILIATION,
            expectedGroupId = groupId
        )
        assertEquals(IngestOutcome.REJECTED, rejected.outcome)
        assertEquals("post-removal history", rejected.reason)
        assertEquals(alice.balances(), carol.balances())

        alice.expenses.addSettlement(
            Settlement(
                UUID.randomUUID().toString(),
                bob.pub,
                successor,
                20,
                "USD",
                timestamp = System.currentTimeMillis() / 1000
            ),
            groupId
        )
        val paid = alice.db.eventDao().getEventsByType(groupId, "settlement").single()
        carol.ingest(alice.event(paid.eventId))
        assertEquals(mapOf(successor to 30L, bob.pub to -30L), alice.balances())
        assertEquals(alice.balances(), carol.balances())

        // A different current counterparty can send A2 a valid LIVE repayment involving departed B.
        val incoming = Settlement(
            UUID.randomUUID().toString(),
            bob.pub,
            carol.pub,
            10,
            "USD",
            timestamp = System.currentTimeMillis() / 1000
        )
        carol.expenses.addSettlement(incoming, groupId)
        val received = carol.db.eventDao().getEventsByType(groupId, "settlement").single { it.pubkey == carol.pub }
        alice.ingest(carol.event(received.eventId), IngestionContext.LIVE)
        assertEquals(alice.balances(), carol.balances())
        assertEquals(20L, -alice.balances().getValue(bob.pub))
    }
}
