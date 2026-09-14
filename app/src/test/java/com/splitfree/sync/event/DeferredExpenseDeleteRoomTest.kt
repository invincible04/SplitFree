package com.splitfree.sync.event

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.ExpenseEventHistory
import com.splitfree.data.repository.ExpenseRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import com.splitfree.domain.usecase.expense.GetExpensesUseCase
import com.splitfree.domain.usecase.export.ExportGroupUseCase
import com.splitfree.domain.usecase.export.ImportGroupUseCase
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.sync.nearby.TestIdentity
import com.splitfree.test.FakeSecureStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** L2 regression: no DAO, signature, decryption, validation or ledger projection is mocked. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DeferredExpenseDeleteRoomTest {
    private val alice = TestIdentity(1)
    private val bob = TestIdentity(2)
    private val groupId = "deferred-expense-delete"
    private val uuid = "expense-1"
    private val at = System.currentTimeMillis() / 1000 - 100
    private val encryption = GroupEncryption(CompressionUtil)
    private val key = encryption.generateGroupKey()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var db: AppDatabase
    private lateinit var groups: GroupRepository
    private lateinit var events: EventRepository
    private lateinit var balances: ComputeBalancesUseCase
    private lateinit var expenses: GetExpensesUseCase
    private lateinit var processor: EventProcessor
    private val dao get() = db.eventDao()

    @Before
    fun setup() = runBlocking {
        every { bob.contract.getPublicKeyBytes() } answers { bob.pub.hexToBytes() }
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
            .allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() })
            .build()
        groups = GroupRepository(db.groupDao(), FakeSecureStorage())
        groups.save(
            Group(
                groupId,
                "Trip",
                createdBy = alice.pub,
                createdAt = 1,
                members = listOf(alice.pub, bob.pub),
                relays = emptyList()
            ),
            key
        )
        events = EventRepository(db, dao)
        balances = ComputeBalancesUseCase(events, groups, encryption)
        expenses = GetExpensesUseCase(events, groups, encryption)
        processor = newProcessor()
    }

    @After
    fun cleanup() {
        scope.cancel()
        db.close()
    }

    private fun newProcessor(
        database: AppDatabase = db,
        groupRepo: GroupRepository = groups,
        eventDao: EventDao = database.eventDao()
    ): EventProcessor {
        val settings = mockk<SettingsContract>().also { every { it.giftWrapEnabled } returns true }
        return EventProcessor(
            eventDao, groupRepo, encryption, EventSigner(bob.contract), bob.contract,
            GiftWrapService(bob.contract, settings), EventValidator(),
            EventPostProcessor(
                groupRepo,
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                bob.contract,
                scope
            ),
            MembershipHistory(eventDao, groupRepo, bob.contract)
        )
    }

    /** A second device ingests real signed events, then exports a self-encrypted, MAC-authenticated backup. */
    private suspend fun backup(vararg history: NostrEvent): String {
        val source = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() })
            .build()
        try {
            val sourceGroups = GroupRepository(source.groupDao(), FakeSecureStorage())
            sourceGroups.save(checkNotNull(groups.getById(groupId)), key)
            val sourceProcessor = newProcessor(source, sourceGroups)
            history.forEach {
                assertEquals(
                    IngestOutcome.APPLIED,
                    sourceProcessor.process(
                        it,
                        context = IngestionContext.RECONCILIATION,
                        expectedGroupId = groupId
                    ).outcome
                )
            }
            return ExportGroupUseCase(EventRepository(source, source.eventDao()), sourceGroups, bob.contract)(groupId)
        } finally {
            source.close()
        }
    }

    private suspend fun ingest(event: NostrEvent) = processor.process(
        event,
        context = IngestionContext.RECONCILIATION,
        expectedGroupId = groupId
    )

    private fun signed(
        type: String,
        payload: String,
        author: TestIdentity = alice,
        id: String = uuid,
        createdAt: Long = at
    ): NostrEvent = EventSigner(author.contract).createSignedEvent(
        groupId,
        type,
        encryption.encrypt(payload, key),
        expenseUuid = id,
        createdAt = createdAt
    ).also { assertTrue("fixture must have a real valid signature", it.verify()) }

    private fun expense(
        author: TestIdentity = alice,
        id: String = uuid,
        amount: Long = 100,
        type: String = "expense",
        createdAt: Long = at
    ) = signed(
        type,
        Json.encodeToString(
            Expense(
                id = id,
                amount = amount,
                currency = "USD",
                description = type,
                paidBy = author.pub,
                splitType = SplitType.EQUAL,
                splitAmong = listOf(SplitEntry(alice.pub, amount / 2), SplitEntry(bob.pub, amount / 2)),
                timestamp = createdAt
            )
        ),
        author,
        id,
        createdAt
    )

    private fun deletion(author: TestIdentity = alice, id: String = uuid, createdAt: Long = at + 10) =
        signed("expense_delete", "{}", author, id, createdAt)

    private suspend fun assertDeleted(original: NostrEvent, delete: NostrEvent) {
        assertEquals(EventEntity.APPLY_STATE_APPLIED, dao.getEvent(original.id)?.applyState)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, dao.getEvent(delete.id)?.applyState)
        assertEquals(0, dao.countPending(groupId))
        assertEquals(setOf(original.id, delete.id), events.getEventsByGroup(groupId).map { it.eventId }.toSet())
        assertEquals(listOf(uuid), dao.getDeletedExpenseUuids(groupId))
        assertEquals(listOf(uuid), dao.getAppliedDeletedExpenseUuidsByAuthor(groupId, alice.pub))
        assertNull(
            ExpenseEventHistory.current(
                dao.getExpenseHistoryByAuthor(uuid, groupId, alice.pub),
                ExpenseIdentity(alice.pub, uuid)
            )
        )
        val result = balances.computeWithExclusions(groupId)
        assertTrue("deleted history must contribute no money", result.balances.all { it.net == 0L })
        assertEquals(setOf(ExpenseIdentity(alice.pub, uuid)), result.excludedExpenses)
        assertTrue(expenses.observeWithAuthors(groupId).first().isEmpty())
    }

    @Test
    fun `delete before original converges without exposing the deleted expense`() = runBlocking {
        val original = expense()
        val delete = deletion()
        val observed = Channel<List<EventSnapshot>>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.Unconfined) {
            events.observeEventsByGroup(groupId).collect { observed.send(it) }
        }
        try {
            assertTrue(withTimeout(5_000) { observed.receive() }.isEmpty())
            val held = ingest(delete)
            assertEquals(IngestOutcome.DEFERRED, held.outcome)
            assertEquals("missing original", held.reason)
            assertEquals(1, dao.countPending(groupId))
            assertTrue(events.getEventsByGroup(groupId).isEmpty())
            assertTrue(expenses.observeWithAuthors(groupId).first().isEmpty())
            assertEquals(0, processor.retryDeferred(groupId))

            // Recovery must use durable rows, not state cached by the processor that held the delete.
            processor = newProcessor()
            assertEquals(0, processor.retryDeferred(groupId))
            assertEquals(IngestOutcome.DEFERRED, ingest(delete).outcome)
            val admitted = ingest(original)
            val beforeRetry = events.getEventsByGroup(groupId)
            processor.retryDeferred(groupId)
            assertEquals(
                "Original was refused: ${admitted.reason}; pending=${dao.countPending(groupId)}",
                IngestOutcome.APPLIED,
                admitted.outcome
            )
            assertTrue(admitted.stored)
            assertNull("deleted history must not announce a new expense", admitted.decrypted)
            // Ordinary observers must never get a spendable original while its known delete is pending.
            assertEquals(setOf(original.id, delete.id), beforeRetry.map { it.eventId }.toSet())
            withTimeout(5_000) {
                do {
                    val rows = observed.receive()
                    val ids = rows.map { it.eventId }.toSet()
                    assertFalse("observer exposed a deleted original", original.id in ids && delete.id !in ids)
                } while (original.id !in ids)
            }
            assertDeleted(original, delete)
            processor = newProcessor()
            processor.recoverPending()
            assertEquals(0, processor.retryDeferred(groupId))
            assertEquals(IngestOutcome.ALREADY_APPLIED, ingest(delete).outcome)
            assertEquals(IngestOutcome.ALREADY_APPLIED, ingest(original).outcome)
            assertDeleted(original, delete)
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun `original before delete is an applied zero balance control`() = runBlocking {
        val original = expense()
        val delete = deletion()
        val admitted = ingest(original)
        assertEquals(IngestOutcome.APPLIED, admitted.outcome)
        assertNotNull("ordinary expenses still provide notification content", admitted.decrypted)
        assertEquals(50L, balances(groupId).single { it.pubkey == alice.pub }.net)
        assertEquals(
            listOf(ExpenseIdentity(alice.pub, uuid)),
            expenses.observeWithAuthors(groupId).first().map { it.identity }
        )
        assertEquals(IngestOutcome.APPLIED, ingest(delete).outcome)
        assertEquals(0, processor.retryDeferred(groupId))
        assertDeleted(original, delete)
    }

    @Test
    fun `colliding author cannot satisfy a pending delete or erase another authors expense`() = runBlocking {
        val bobDelete = deletion(bob)
        val aliceOriginal = expense()
        assertEquals(IngestOutcome.DEFERRED, ingest(bobDelete).outcome)
        assertEquals(IngestOutcome.APPLIED, ingest(aliceOriginal).outcome)
        assertEquals(EventEntity.APPLY_STATE_PENDING, dao.getEvent(bobDelete.id)?.applyState)
        assertNull(dao.getExpenseByAuthor(uuid, groupId, bob.pub))
        assertEquals(0, processor.retryDeferred(groupId))
        assertEquals(1, dao.countPendingByAuthor(groupId, bob.pub))
        assertTrue(balances.computeWithExclusions(groupId).excludedExpenses.isEmpty())

        val bobOriginal = expense(bob, amount = 200)
        assertEquals(IngestOutcome.APPLIED, ingest(bobOriginal).outcome)
        processor.retryDeferred(groupId)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, dao.getEvent(bobDelete.id)?.applyState)
        assertEquals(0, dao.countPending(groupId))
        assertEquals(aliceOriginal.id, dao.getExpenseByAuthor(uuid, groupId, alice.pub)?.eventId)
        assertEquals(bobOriginal.id, dao.getExpenseByAuthor(uuid, groupId, bob.pub)?.eventId)
        val result = balances.computeWithExclusions(groupId)
        assertEquals(50L, result.balances.single { it.pubkey == alice.pub }.net)
        assertEquals(-50L, result.balances.single { it.pubkey == bob.pub }.net)
        assertEquals(setOf(ExpenseIdentity(bob.pub, uuid)), result.excludedExpenses)
        assertEquals(
            listOf(ExpenseIdentity(alice.pub, uuid)),
            expenses.observeWithAuthors(groupId).first().map { it.identity }
        )
    }

    @Test
    fun `actually applied delete rejects a newly signed original replay but not another author`() = runBlocking {
        val original = expense()
        val delete = deletion()
        assertEquals(IngestOutcome.APPLIED, ingest(original).outcome)
        assertEquals(IngestOutcome.APPLIED, ingest(delete).outcome)
        assertDeleted(original, delete)

        // A new event ID forces the replay through business rules rather than the duplicate fast path.
        val replay = expense(createdAt = at + 20)
        assertFalse(original.id == replay.id)
        repeat(2) {
            val result = ingest(replay)
            assertEquals(IngestOutcome.REJECTED, result.outcome)
            assertEquals("business rule", result.reason)
            assertFalse(result.stored)
            assertFalse(result.retryable)
            assertNull(dao.getEvent(replay.id))
            processor = newProcessor()
            processor.recoverPending()
        }
        assertDeleted(original, delete)
        val bobOriginal = expense(bob)
        assertEquals(IngestOutcome.APPLIED, ingest(bobOriginal).outcome)
        assertEquals(
            listOf(ExpenseIdentity(bob.pub, uuid)),
            expenses.observeWithAuthors(groupId).first().map { it.identity }
        )
        val result = balances.computeWithExclusions(groupId)
        assertEquals(50L, result.balances.single { it.pubkey == bob.pub }.net)
        assertEquals(setOf(ExpenseIdentity(alice.pub, uuid)), result.excludedExpenses)
    }

    @Test
    fun `correction before original keeps deferred behavior and cannot revive a deleted expense`() = runBlocking {
        val original = expense()
        val correction = expense(type = "expense_correction", amount = 200, createdAt = at + 5)
        assertEquals(IngestOutcome.DEFERRED, ingest(correction).outcome)
        assertTrue(events.getEventsByGroup(groupId).isEmpty())
        assertEquals(0, processor.retryDeferred(groupId))
        assertEquals(IngestOutcome.APPLIED, ingest(original).outcome)
        assertEquals(EventEntity.APPLY_STATE_PENDING, dao.getEvent(correction.id)?.applyState)
        assertEquals(1, processor.retryDeferred(groupId))
        assertEquals(100L, balances(groupId).single { it.pubkey == alice.pub }.net)
        assertEquals(200L, expenses.observe(groupId).first().single().amount)
        assertEquals(0, processor.retryDeferred(groupId))

        assertEquals(IngestOutcome.APPLIED, ingest(deletion()).outcome)
        val afterDelete = expense(type = "expense_correction", amount = 300, createdAt = at + 20)
        assertEquals(IngestOutcome.APPLIED, ingest(afterDelete).outcome)
        processor.retryDeferred(groupId)
        assertTrue(expenses.observe(groupId).first().isEmpty())
        val result = balances.computeWithExclusions(groupId)
        assertTrue(result.balances.all { it.net == 0L })
        assertEquals(setOf(ExpenseIdentity(alice.pub, uuid)), result.excludedExpenses)
    }

    @Test
    fun `pending correction and deletes before original stay deleted after retry`() = runBlocking {
        val correction = expense(type = "expense_correction", amount = 200, createdAt = at + 5)
        val deletes = listOf(deletion(), deletion(createdAt = at + 11))
        val original = expense()
        (listOf(correction) + deletes).forEach { assertEquals(IngestOutcome.DEFERRED, ingest(it).outcome) }
        assertEquals(3, dao.countPending(groupId))
        assertEquals(IngestOutcome.APPLIED, ingest(original).outcome)
        deletes.forEach { assertEquals(EventEntity.APPLY_STATE_APPLIED, dao.getEvent(it.id)?.applyState) }
        assertEquals(EventEntity.APPLY_STATE_PENDING, dao.getEvent(correction.id)?.applyState)
        assertTrue(expenses.observe(groupId).first().isEmpty())
        assertTrue(balances(groupId).all { it.net == 0L })
        processor = newProcessor()
        assertEquals(1, processor.retryDeferred(groupId))
        assertEquals(EventEntity.APPLY_STATE_APPLIED, dao.getEvent(correction.id)?.applyState)
        assertEquals(0, dao.countPending(groupId))
        assertEquals(0, processor.retryDeferred(groupId))
        assertTrue(expenses.observe(groupId).first().isEmpty())
        val result = balances.computeWithExclusions(groupId)
        assertTrue(result.balances.all { it.net == 0L })
        assertEquals(setOf(ExpenseIdentity(alice.pub, uuid)), result.excludedExpenses)
    }

    @Test
    fun `pending delete quota still bounds retention and an original frees only its own slot`() = runBlocking {
        val deletes = (0 until EventProcessor.MAX_PENDING_LEDGER_PER_AUTHOR).map { deletion(id = "expense-$it") }
        deletes.forEach { assertEquals(IngestOutcome.DEFERRED, ingest(it).outcome) }
        val overQuota = deletion(id = "over-quota")
        assertEquals("pending quota", ingest(overQuota).reason)
        assertNull(dao.getEvent(overQuota.id))
        assertEquals(EventProcessor.MAX_PENDING_LEDGER_PER_AUTHOR, dao.countPendingByAuthor(groupId, alice.pub))
        assertEquals(IngestOutcome.DEFERRED, ingest(deletes.first()).outcome)

        assertEquals(IngestOutcome.APPLIED, ingest(expense(id = "expense-0")).outcome)
        assertEquals(EventProcessor.MAX_PENDING_LEDGER_PER_AUTHOR - 1, dao.countPendingByAuthor(groupId, alice.pub))
        assertEquals(EventEntity.APPLY_STATE_APPLIED, dao.getEvent(deletes.first().id)?.applyState)
        assertEquals(IngestOutcome.DEFERRED, ingest(overQuota).outcome)
        assertEquals(EventProcessor.MAX_PENDING_LEDGER_PER_AUTHOR, dao.countPendingByAuthor(groupId, alice.pub))
        assertEquals(0, processor.retryDeferred(groupId))
        assertTrue(expenses.observe(groupId).first().isEmpty())
        val result = balances.computeWithExclusions(groupId)
        assertTrue(result.balances.all { it.net == 0L })
        assertEquals(setOf(ExpenseIdentity(alice.pub, "expense-0")), result.excludedExpenses)
    }

    @Test
    fun `two originals racing one pending delete admit exactly one and reject the other as a replay`() = runBlocking {
        val delete = deletion()
        assertEquals(IngestOutcome.DEFERRED, ingest(delete).outcome)
        val first = expense()
        val second = expense(createdAt = at + 1)
        assertEquals(IngestOutcome.APPLIED, ingest(first).outcome)
        // Interleaving: the second original's business-rule read happened before the first one's write
        // promoted the delete, so validation sees no applied tombstone; the write transaction reads live.
        val staleRead = spyk(dao)
        var stale = true
        coEvery { staleRead.getAppliedDeletedExpenseUuidsByAuthor(groupId, alice.pub) } answers {
            if (stale) {
                stale = false
                emptyList()
            } else {
                callOriginal()
            }
        }
        processor = newProcessor(eventDao = staleRead)

        val secondResult = ingest(second)

        assertEquals(IngestOutcome.REJECTED, secondResult.outcome)
        assertEquals("business rule", secondResult.reason)
        assertFalse(secondResult.stored)
        assertNull(dao.getEvent(second.id))
        // Same outcome as the sequential order, so stored history is deterministic.
        processor = newProcessor()
        assertDeleted(first, delete)
        assertEquals(IngestOutcome.ALREADY_APPLIED, ingest(first).outcome)
        assertEquals("business rule", ingest(second).reason)
    }

    @Test
    fun `delete validated before a concurrent original commit is stored applied not pending`() = runBlocking {
        val original = expense()
        val delete = deletion()
        // Interleaving: the delete's original lookup ran before the original committed (import or local
        // publish); the write transaction then finds the applied original and must not leave it pending.
        val staleRead = spyk(dao)
        var stale = true
        coEvery { staleRead.getExpenseByAuthor(uuid, groupId, alice.pub) } answers {
            if (stale) {
                stale = false
                null
            } else {
                callOriginal()
            }
        }
        processor = newProcessor(eventDao = staleRead)
        assertEquals(IngestOutcome.APPLIED, ingest(original).outcome)
        assertTrue(stale)

        val result = ingest(delete)

        assertEquals(IngestOutcome.APPLIED, result.outcome)
        assertTrue(result.stored)
        processor = newProcessor()
        assertEquals(0, processor.retryDeferred(groupId))
        assertDeleted(original, delete)
        assertEquals(IngestOutcome.ALREADY_APPLIED, ingest(delete).outcome)
    }

    @Test
    fun `invalid original cannot satisfy or apply an authenticated pending delete`() = runBlocking {
        val delete = deletion()
        assertEquals(IngestOutcome.DEFERRED, ingest(delete).outcome)
        val invalid = expense(amount = -100)
        assertEquals("invalid payload", ingest(invalid).reason)
        assertNull(dao.getEvent(invalid.id))
        assertEquals(EventEntity.APPLY_STATE_PENDING, dao.getEvent(delete.id)?.applyState)
        assertEquals(1, dao.countPending(groupId))
        assertEquals(0, processor.retryDeferred(groupId))
        assertTrue(events.getEventsByGroup(groupId).isEmpty())
    }

    @Test
    fun `authenticated backup original resolves sync pending delete before import returns`() = runBlocking {
        val original = expense()
        val delete = deletion()
        val exported = backup(original, delete)
        val importer = ImportGroupUseCase(events, groups, encryption, EventValidator(), bob.contract)
        assertEquals(IngestOutcome.DEFERRED, ingest(delete).outcome)
        assertEquals(listOf(delete.id), dao.getEventIds(groupId))

        // Authentication is real: a tampered backup must not admit the original or promote the delete.
        val tampered = Json.decodeFromString<SplitFreeExport>(exported).copy(hmac = "00".repeat(32))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { importer(tampered) } }
        assertNull(dao.getEvent(original.id))
        assertEquals(EventEntity.APPLY_STATE_PENDING, dao.getEvent(delete.id)?.applyState)

        val observed = Channel<List<EventSnapshot>>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.Unconfined) {
            events.observeEventsByGroup(groupId).collect { observed.send(it) }
        }
        try {
            assertTrue(withTimeout(5_000) { observed.receive() }.isEmpty())
            // The backup includes the delete, but import skips its already-known ID. Only the original is new.
            assertEquals(1, importer(exported))
            assertEquals(EventEntity.APPLY_STATE_APPLIED, dao.getEvent(original.id)?.applyState)
            val visible = expenses.observeWithAuthors(groupId).first()
            val result = balances.computeWithExclusions(groupId)
            assertTrue(
                "Import resurrected deleted expense: pending=${dao.countPending(groupId)}, " +
                    "visible=${visible.map { it.identity }}, balances=${result.balances}",
                visible.isEmpty()
            )
            assertDeleted(original, delete)
            withTimeout(5_000) {
                do {
                    val ids = observed.receive().map { it.eventId }.toSet()
                    assertFalse("import observer exposed a deleted original", original.id in ids && delete.id !in ids)
                } while (original.id !in ids)
            }
            assertEquals(0, importer(exported))
            processor = newProcessor()
            assertEquals(0, processor.retryDeferred(groupId))
            processor.recoverPending()
            assertDeleted(original, delete)
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun `local expense save resolves sync pending delete through publisher commit`() = runBlocking {
        val delete = deletion()
        assertEquals(IngestOutcome.DEFERRED, ingest(delete).outcome)
        val settings = mockk<SettingsContract>().also { every { it.giftWrapEnabled } returns true }
        val publisher = EventPublisher(
            dao,
            db.outboxDao(),
            db.deliveryDao(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            GiftWrapService(alice.contract, settings),
            groups,
            alice.contract,
            db
        )
        val repository =
            ExpenseRepository(dao, groups, encryption, alice.contract, EventSigner(alice.contract), publisher)
        val payload = Json.decodeFromString<Expense>(encryption.decrypt(expense().content, key))
        // The shared insert must join the publisher's transaction: a later outbox failure rolls back
        // both the original and the pending-delete promotion, leaving the authenticated delete retryable.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_delivery BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'delivery failed'); END"
        )
        try {
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { repository.addExpense(payload, groupId, alice.pub) }
            }
            assertEquals(listOf(delete.id), dao.getEventIds(groupId))
            assertEquals(EventEntity.APPLY_STATE_PENDING, dao.getEvent(delete.id)?.applyState)
            assertTrue(events.getEventsByGroup(groupId).isEmpty())
            assertEquals(0, db.outboxDao().count())
            assertTrue(db.deliveryDao().getAvailable(groupId).isEmpty())
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_delivery")
        }
        repository.addExpense(payload, groupId, alice.pub)
        val saved = checkNotNull(dao.getExpenseByAuthor(uuid, groupId, alice.pub))
        val original = checkNotNull(NostrEvent.fromJson(checkNotNull(saved.originalEventJson)))
        assertTrue(original.verify())
        assertTrue(
            "Publisher commit resurrected a sync-deleted expense; pending=${dao.countPending(groupId)}",
            expenses.observeWithAuthors(groupId).first().isEmpty()
        )
        assertDeleted(original, delete)
        assertEquals(1, db.outboxDao().count())
        assertEquals(1, db.deliveryDao().getAvailable(groupId).size)
        repository.addExpense(payload, groupId, alice.pub)
        assertEquals(1, db.outboxDao().count())
        assertEquals(0, processor.retryDeferred(groupId))
        assertDeleted(original, delete)
    }
}
