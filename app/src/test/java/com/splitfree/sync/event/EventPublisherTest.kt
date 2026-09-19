package com.splitfree.sync.event

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.ExpenseRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.NostrKind
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.ExpenseCorrectionCommand
import com.splitfree.domain.repository.ExpenseRevisionConflictException
import com.splitfree.domain.repository.ExpenseSaveConflictException
import com.splitfree.domain.repository.OutboxFullException
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.usecase.expense.AddExpenseUseCase
import com.splitfree.domain.usecase.group.ControlOperationLock
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.sync.worker.OutboxDrainScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EventPublisherTest {
    private val databaseName = "expense-save-${UUID.randomUUID()}.db"
    private lateinit var db: AppDatabase
    private lateinit var groupRepo: GroupRepository
    private lateinit var publisher: EventPublisher
    private val throttler = mockk<EventThrottler>(relaxed = true)
    private val drainScheduler = mockk<OutboxDrainScheduler>(relaxed = true)
    private val giftWrap = mockk<GiftWrapService>()
    private val identity = mockk<IdentityManager>()
    private val keyStore = mockk<SecureStorage>(relaxed = true)
    private val myPub = "aa".repeat(32)
    private val otherPub = "bb".repeat(32)
    private val thirdPub = "cc".repeat(32)
    private val group = Group(
        "g1",
        "Group",
        createdBy = myPub,
        createdAt = 1000,
        members = listOf(myPub, otherPub, thirdPub),
        relays = emptyList()
    )
    private val event = NostrEvent(
        id = "evt1",
        pubkey = myPub,
        createdAt = 1000,
        kind = NostrKind.APP_SPECIFIC,
        content = "enc",
        sig = "sig"
    )
    private val expense = Expense(
        "operation", 100, "INR", "Lunch", myPub, SplitType.EQUAL,
        listOf(SplitEntry(myPub, 50), SplitEntry(otherPub, 50)), 1000, "food"
    )

    @Before
    fun setup() = runBlocking {
        every { identity.hasPendingKeyPair() } returns false
        every { identity.stagedIdentitySwitch() } returns null
        every { identity.getPublicKeyHex() } returns myPub
        every { keyStore.getString(any(), any()) } returns "group-key"
        every { giftWrap.enabled } returns true
        every { giftWrap.wrapIfEnabled(any(), any()) } answers {
            assertFalse("Wrapping must happen outside the transaction", db.inTransaction())
            firstArg<NostrEvent>().copy(
                id = "${firstArg<NostrEvent>().id}-${secondArg<String>()}",
                kind = NostrKind.GIFT_WRAP
            )
        }
        openDatabase()
        groupRepo.save(group, "group-key")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun openDatabase() {
        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, databaseName).build()
        groupRepo = GroupRepository(db.groupDao(), keyStore)
        publisher =
            EventPublisher(
                db.eventDao(),
                db.outboxDao(),
                db.deliveryDao(),
                throttler,
                drainScheduler,
                giftWrap,
                groupRepo,
                identity,
                db,
                mockk(relaxed = true)
            )
    }

    private fun repository(): ExpenseRepository {
        val encryption = mockk<GroupEncryption>()
        every { encryption.encrypt(any(), any()) } answers {
            assertFalse("Encryption must happen outside the transaction", db.inTransaction())
            "encrypted:${firstArg<String>()}"
        }
        every { encryption.decrypt(any(), any()) } answers { firstArg<String>().removePrefix("encrypted:") }
        val signer = mockk<EventSigner>()
        every { signer.createSignedEvent(any(), any(), any(), any()) } answers {
            assertFalse("Signing must happen outside the transaction", db.inTransaction())
            val address = EventSigner.relayAddress(firstArg(), secondArg(), UUID.randomUUID().toString())
            event.copy(
                id = UUID.randomUUID().toString(),
                content = thirdArg(),
                tags = listOf(listOf("d", address), listOf("g", firstArg()), listOf("t", secondArg()))
            )
        }
        every { signer.createSignedCommandEvent(any(), any(), any(), any(), any(), any(), any()) } answers {
            assertFalse("Signing must happen outside the transaction", db.inTransaction())
            val address = EventSigner.relayAddress(firstArg(), secondArg(), arg(4))
            event.copy(
                id = UUID.randomUUID().toString(),
                content = thirdArg(),
                createdAt = arg<Long?>(5) ?: 1000,
                tags = listOf(listOf("d", address), listOf("g", firstArg()), listOf("t", secondArg()))
            )
        }
        return ExpenseRepository(
            db.eventDao(),
            groupRepo,
            encryption,
            identity,
            signer,
            publisher,
            MembershipHistory(db.eventDao(), groupRepo, identity)
        )
    }

    /** Command-address fixture with a dummy signature; tests publication bookkeeping, not signing. */
    private fun commandEvent(id: String, type: String, commandId: String, createdAt: Long = 1000) = event.copy(
        id = id,
        createdAt = createdAt,
        tags = listOf(
            listOf("d", EventSigner.relayAddress("g1", type, commandId)),
            listOf("g", "g1"),
            listOf("t", type)
        )
    )

    @Test
    fun `publishToGroup atomically stores every recipient except self`() = runBlocking {
        publisher.publishToGroup(event, "g1", "enc", "expense")

        assertNotNull(db.eventDao().getEvent(event.id))
        assertEquals(setOf("evt1-$otherPub", "evt1-$thirdPub"), db.outboxDao().getAll().map { it.eventId }.toSet())
        assertTrue(db.outboxDao().getAll().all { it.eventType == "expense" })
        verify(exactly = 0) { giftWrap.wrapIfEnabled(any(), myPub) }
        verify(exactly = 2) { throttler.enqueue(any()) }
        verify(exactly = 1) { drainScheduler.requestDrain() }
    }

    @Test
    fun `publishToGroup without gift wrap stores direct delivery`() = runBlocking {
        every { giftWrap.enabled } returns false
        publisher.publishToGroup(event, "g1", "enc", "expense")
        assertEquals(listOf(event.id), db.outboxDao().getAll().map { it.eventId })
        verify { throttler.enqueue(event) }
    }

    @Test
    fun `publishDirect skips wrapping and saveAndQueue skips throttling but both request a durable drain`() =
        runBlocking {
            publisher.publishDirect(event, "g1", "enc", "expense")
            publisher.saveAndQueue(event.copy(id = "snapshot"), "g1", "enc", "snapshot")
            assertEquals(2, db.outboxDao().count())
            verify(exactly = 0) { giftWrap.wrapIfEnabled(any(), any()) }
            verify(exactly = 1) { throttler.enqueue(any()) }
            verify(exactly = 2) { drainScheduler.requestDrain() }
        }

    @Test
    fun `a save that commits nothing does not request a drain`() = runBlocking {
        assertTrue(publisher.publishExpense(event, group, "operation"))
        assertFalse(publisher.publishExpense(event.copy(id = "retry-event"), group, "operation"))
        publisher.saveAndQueue(event.copy(id = "snapshot"), "g1", "enc", "snapshot")
        publisher.saveAndQueue(event.copy(id = "snapshot"), "g1", "enc", "snapshot")

        verify(exactly = 2) { drainScheduler.requestDrain() }
    }

    @Test
    fun `a failing drain scheduler does not undo or fail a committed save`() = runBlocking {
        every { drainScheduler.requestDrain() } throws IllegalStateException("WorkManager is not initialized")

        assertTrue(publisher.publishExpense(event, group, "operation"))
        publisher.saveAndQueue(event.copy(id = "snapshot"), "g1", "enc", "snapshot")

        assertNotNull(db.eventDao().getEvent(event.id))
        assertNotNull(db.eventDao().getEvent("snapshot"))
        assertEquals(3, db.outboxDao().count())
        verify(exactly = 2) { throttler.enqueue(any()) }
    }

    @Test
    fun `wrapping failure leaves no local event or deliveries and can retry`() = runBlocking {
        every { giftWrap.wrapIfEnabled(any(), thirdPub) } throws IllegalStateException("Wrapping failed")
        expectFailure<IllegalStateException> { publisher.publishExpense(event, group, "operation") }
        assertEmptySave()
        every { giftWrap.wrapIfEnabled(any(), thirdPub) } returns event.copy(id = "third", kind = NostrKind.GIFT_WRAP)
        assertTrue(publisher.publishExpense(event, group, "operation"))
        assertEquals(2, db.outboxDao().count())
    }

    @Test
    fun `event insert failure rolls back whole save`() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_event BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT, 'event failed'); END"
        )
        expectFailure<Exception> { publisher.publishExpense(event, group, "operation") }
        assertEmptySave()
    }

    @Test
    fun `first recipient insert failure rolls back event`() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_delivery BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'delivery failed'); END"
        )
        expectFailure<Exception> { publisher.publishExpense(event, group, "operation") }
        assertEmptySave()
    }

    @Test
    fun `second recipient insert failure rolls back event and first recipient then retry succeeds`() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_delivery BEFORE INSERT ON outbox WHEN (SELECT COUNT(*) FROM outbox) = 1 " +
                "BEGIN SELECT RAISE(ABORT, 'delivery failed'); END"
        )
        expectFailure<Exception> { publisher.publishExpense(event, group, "operation") }
        assertEmptySave()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_delivery")
        assertTrue(publisher.publishExpense(event, group, "operation"))
        assertEquals(1, db.eventDao().getEventCount("g1"))
        assertEquals(2, db.outboxDao().count())
    }

    @Test
    fun `capacity checks complete recipient batch and does not silently lose expense`() = runBlocking {
        fillOutbox(4999)
        expectFailure<OutboxFullException> { publisher.publishExpense(event, group, "operation") }
        assertNull(db.eventDao().getEvent(event.id))
        assertEquals(4999, db.outboxDao().count())
        verify(exactly = 0) { throttler.enqueue(any()) }
        db.outboxDao().delete("queued-0")
        assertTrue(publisher.publishExpense(event, group, "operation"))
        assertEquals(5000, db.outboxDao().count())
    }

    @Test
    fun `rotation at expense admission threshold queues every recipient and advances local epoch`() = runBlocking {
        fillOutbox(4999)
        val privateKey = ByteArray(32).also { it[31] = 1 }
        val creator = NostrEvent.pubkeyFromPrivkey(privateKey)
        val peer = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 2 })
        val rotationGroup = group.copy(createdBy = creator, members = listOf(creator, peer, thirdPub))
        groupRepo.save(rotationGroup, "group-key")
        every { identity.getPublicKeyHex() } returns creator
        every { identity.getPrivateKeyBytes() } answers { privateKey.copyOf() }
        // No key stored for epoch 1 yet, so the rotation generates one instead of resuming an interrupted attempt.
        var epochKey: String? = null
        every { keyStore.getString("g1:1", any()) } answers { epochKey }
        every { keyStore.putString("g1:1", any()) } answers { epochKey = secondArg() }
        val encryption = mockk<GroupEncryption>()
        every { encryption.generateGroupKey() } returns "rotated-key"
        every { encryption.encrypt(any(), "rotated-key") } answers { "meta:${firstArg<String>()}" }
        every { encryption.decrypt(any(), "rotated-key") } answers { firstArg<String>().removePrefix("meta:") }
        val signer = EventSigner(identity)

        RotateGroupKeyUseCase(
            groupRepo,
            encryption,
            identity,
            signer,
            publisher,
            mockk(relaxed = true),
            ControlOperationJournal(db.controlOperationDao()),
            ControlOperationLock()
        )("g1", thirdPub)

        val stored = db.eventDao().getEventsByGroup("g1")
        val rotations = stored.filter { it.eventType == "key_rotation" }
        val metas = stored.filter { it.eventType == "group_meta" }
        assertEquals(2, rotations.size)
        assertEquals(1, metas.size)
        assertEquals(5002, db.outboxDao().count())
        assertEquals(3, db.outboxDao().countByEventIds(stored.map { it.eventId }))
        assertEquals(1, groupRepo.getById("g1")?.keyEpoch)
        assertEquals(listOf(creator, peer), groupRepo.getMembers("g1"))
        // The post-rotation group_meta is encrypted with, and recorded under, the NEW epoch.
        assertEquals(1, metas.single().keyEpoch)
        assertTrue(metas.single().contentEncrypted.startsWith("meta:"))
        assertTrue(thirdPub !in metas.single().contentEncrypted)
        verify { keyStore.putString("g1:1", "rotated-key") }
        verify(exactly = 3) { throttler.enqueue(any()) }
        verify(exactly = 0) { giftWrap.wrapIfEnabled(any(), any()) }
    }

    @Test
    fun `control publishing paths retain complete deliveries above expense admission threshold`() = runBlocking {
        fillOutbox(5000)
        publisher.publishDirect(event.copy(id = "rotation"), "g1", "enc", "key_rotation")
        publisher.publishToGroup(event.copy(id = "metadata"), "g1", "enc", "group_meta")
        publisher.saveAndQueue(event.copy(id = "snapshot"), "g1", "enc", "snapshot")

        assertEquals(3, db.eventDao().getEventCount("g1"))
        assertEquals(5004, db.outboxDao().count())
        assertEquals(
            4,
            db.outboxDao().countByEventIds(listOf("rotation", "metadata-$otherPub", "metadata-$thirdPub", "snapshot"))
        )
        verify(exactly = 3) { throttler.enqueue(any()) }
        expectFailure<OutboxFullException> { publisher.publishExpense(event, group, "operation") }
        assertNull(db.eventDao().getEvent(event.id))
        assertEquals(5004, db.outboxDao().count())
    }

    @Test
    fun `post commit throttle failure including cancellation does not fail durable save`() = runBlocking {
        every { throttler.enqueue(any()) } throws CancellationException("Caller disappeared")
        repository().addExpense(expense, "g1")
        assertEquals(1, db.eventDao().getEventCount("g1"))
        assertEquals(2, db.outboxDao().count())
        assertTrue(AddExpenseUseCase(repository()).isSaved("g1", expense.id))
    }

    @Test
    fun `concurrent same operation commits only one complete batch`() = runBlocking {
        val results = listOf(event, event.copy(id = "retry-event")).map { candidate ->
            async { publisher.publishExpense(candidate, group, "operation") }
        }.awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(1, db.eventDao().getEventCount("g1"))
        assertEquals(2, db.outboxDao().count())
    }

    @Test
    fun `concurrent repository retry with changed payload reports conflict and keeps one expense`() = runBlocking {
        val repo = repository()
        val results = listOf(expense, expense.copy(description = "Different lunch")).map { candidate ->
            async { runCatching { repo.addExpense(candidate, "g1") } }
        }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is ExpenseSaveConflictException })
        assertEquals(1, db.eventDao().getEventCount("g1"))
        assertEquals(2, db.outboxDao().count())
    }

    @Test
    fun `reopen recovers committed expense and same retry does not duplicate deliveries`() = runBlocking {
        repository().addExpense(expense, "g1")
        val deliveryIds = db.outboxDao().getAll().map { it.eventId }.toSet()
        db.close()
        openDatabase()

        assertTrue(AddExpenseUseCase(repository()).isSaved("g1", expense.id))
        assertEquals(expense, repository().getSavedExpense("g1", expense.id))
        repository().addExpense(expense.copy(splitAmong = expense.splitAmong.reversed()), "g1")
        assertEquals(1, db.eventDao().getEventCount("g1"))
        assertEquals(deliveryIds, db.outboxDao().getAll().map { it.eventId }.toSet())
        expectFailure<ExpenseSaveConflictException> { repository().addExpense(expense.copy(category = "travel"), "g1") }
        assertEquals(1, db.eventDao().getEventCount("g1"))
    }

    @Test
    fun `removed members and changed epoch after preparation reject stale save`() = runBlocking {
        groupRepo.save(group.copy(members = listOf(myPub, otherPub)), "group-key")
        expectFailure<IllegalStateException> { publisher.publishExpense(event, group, "operation") }
        assertEmptySave()
        every { keyStore.getString("g1:${group.keyEpoch + 1}", any()) } returns null
        groupRepo.save(group.copy(keyEpoch = group.keyEpoch + 1), "new-key")
        expectFailure<IllegalStateException> { publisher.publishExpense(event, group, "operation") }
        assertEmptySave()
    }

    @Test
    fun `logical dedup is scoped to author and group`() = runBlocking {
        assertTrue(publisher.publishExpense(event, group, "operation"))
        every { identity.getPublicKeyHex() } returns otherPub
        assertTrue(publisher.publishExpense(event.copy(id = "other-author", pubkey = otherPub), group, "operation"))
        val secondGroup = group.copy(id = "g2")
        groupRepo.save(secondGroup, "group-key")
        assertTrue(
            publisher.publishExpense(event.copy(id = "other-group", pubkey = otherPub), secondGroup, "operation")
        )
        assertEquals(2, db.eventDao().getEventCount("g1"))
        assertEquals(1, db.eventDao().getEventCount("g2"))
    }

    @Test
    fun `hasOutboxMatching and IDs reflect durable queue`() = runBlocking {
        publisher.publishDirect(event, "g1", "enc", "expense")
        assertTrue(publisher.hasOutboxMatching { "evt1" in it })
        assertFalse(publisher.hasOutboxMatching { "missing" in it })
        assertTrue(publisher.hasOutboxEventsById(listOf("evt1")))
        assertFalse(publisher.hasOutboxEventsById(emptyList()))
    }

    // --- redeliverAuthoredEvents ---

    private val newMember = "dd".repeat(32)
    private val anotherNewMember = "ee".repeat(32)

    /** A locally stored event, as EventPublisher/EventProcessor would have persisted it. */
    private fun storedEvent(
        id: String,
        eventType: String = "expense",
        author: String = myPub,
        sig: String = "sig",
        withJson: Boolean = true
    ): EventEntity {
        val nostr = event.copy(id = id, pubkey = author, sig = if (sig.startsWith("seal:")) "" else sig)
        return EventEntity(
            eventId = id, groupId = "g1", pubkey = author, createdAt = 1000, kind = NostrKind.APP_SPECIFIC,
            contentEncrypted = "enc", eventType = eventType, expenseUuid = null, sig = sig, receivedAt = 1000,
            originalEventJson = if (withJson) nostr.toJson() else null
        )
    }

    private suspend fun store(vararg entities: EventEntity) {
        entities.forEach { db.eventDao().insert(it) }
    }

    @Test
    fun `redeliverAuthoredEvents queues one wrap per authored event and recipient`() = runBlocking {
        store(
            storedEvent("exp1"),
            storedEvent("exp2", eventType = "settlement"),
            storedEvent("exp3", eventType = "expense_correction"),
            storedEvent("exp4", eventType = "expense_delete"),
            storedEvent("exp5", eventType = "snapshot")
        )

        val queued = publisher.redeliverAuthoredEvents("g1", listOf(newMember, anotherNewMember))

        assertEquals(10, queued)
        val rows = db.outboxDao().getAll()
        assertEquals(10, rows.size)
        val expectedIds = listOf("exp1", "exp2", "exp3", "exp4", "exp5").flatMap { id ->
            listOf("$id-$newMember", "$id-$anotherNewMember")
        }
        assertEquals(expectedIds.toSet(), rows.map { it.eventId }.toSet())
        // Outbox rows keep the inner event's type so eviction/criticality rules stay meaningful.
        assertEquals(
            mapOf("expense" to 2, "settlement" to 2, "expense_correction" to 2, "expense_delete" to 2, "snapshot" to 2),
            rows.groupingBy { it.eventType!! }.eachCount()
        )
        verify(exactly = 10) { throttler.enqueue(match { it.kind == NostrKind.GIFT_WRAP }) }
        verify(exactly = 5) { giftWrap.wrapIfEnabled(any(), newMember) }
        verify(exactly = 5) { giftWrap.wrapIfEnabled(any(), anotherNewMember) }
        // Re-delivery never creates new local events; it only wraps existing ones.
        assertEquals(5, db.eventDao().getEventCount("g1"))
    }

    @Test
    fun `redeliverAuthoredEvents wraps the stored original event as-is`() = runBlocking {
        store(storedEvent("exp1"))
        publisher.redeliverAuthoredEvents("g1", listOf(newMember))
        verify(exactly = 1) {
            giftWrap.wrapIfEnabled(match { it.id == "exp1" && it.pubkey == myPub && it.sig == "sig" }, newMember)
        }
    }

    @Test
    fun `redeliverAuthoredEvents skips direct-published structural events`() = runBlocking {
        store(
            storedEvent("meta", eventType = "group_meta"),
            storedEvent("rot", eventType = "key_rotation"),
            storedEvent("rev", eventType = "key_revocation"),
            storedEvent("exp1")
        )
        val queued = publisher.redeliverAuthoredEvents("g1", listOf(newMember))
        assertEquals(1, queued)
        assertEquals(listOf("exp1-$newMember"), db.outboxDao().getAll().map { it.eventId })
    }

    @Test
    fun `redeliverAuthoredEvents skips events by other authors`() = runBlocking {
        store(storedEvent("mine"), storedEvent("theirs", author = otherPub))
        val queued = publisher.redeliverAuthoredEvents("g1", listOf(newMember))
        assertEquals(1, queued)
        assertEquals(listOf("mine-$newMember"), db.outboxDao().getAll().map { it.eventId })
        verify(exactly = 0) { giftWrap.wrapIfEnabled(match { it.pubkey == otherPub }, any()) }
    }

    @Test
    fun `redeliverAuthoredEvents skips seal-signed rumors and rows without a signature or JSON`() = runBlocking {
        store(
            storedEvent("signed"),
            storedEvent("sealed", sig = "seal:" + "ab".repeat(64)),
            storedEvent("unsigned", sig = ""),
            storedEvent("nojson", withJson = false)
        )
        val queued = publisher.redeliverAuthoredEvents("g1", listOf(newMember))
        assertEquals(1, queued)
        assertEquals(listOf("signed-$newMember"), db.outboxDao().getAll().map { it.eventId })
    }

    @Test
    fun `redeliverAuthoredEvents excludes self and duplicate recipients`() = runBlocking {
        store(storedEvent("exp1"))
        val queued = publisher.redeliverAuthoredEvents("g1", listOf(myPub, newMember, newMember))
        assertEquals(1, queued)
        verify(exactly = 0) { giftWrap.wrapIfEnabled(any(), myPub) }
        verify(exactly = 1) { giftWrap.wrapIfEnabled(any(), newMember) }
    }

    @Test
    fun `redeliverAuthoredEvents returns 0 for empty recipients or only self`() = runBlocking {
        store(storedEvent("exp1"))
        assertEquals(0, publisher.redeliverAuthoredEvents("g1", emptyList()))
        assertEquals(0, publisher.redeliverAuthoredEvents("g1", listOf(myPub)))
        assertEquals(0, db.outboxDao().count())
        verify(exactly = 0) { giftWrap.wrapIfEnabled(any(), any()) }
        verify(exactly = 0) { throttler.enqueue(any()) }
    }

    @Test
    fun `redeliverAuthoredEvents returns 0 when there is nothing authored`() = runBlocking {
        store(storedEvent("theirs", author = otherPub))
        assertEquals(0, publisher.redeliverAuthoredEvents("g1", listOf(newMember)))
        assertEquals(0, db.outboxDao().count())
        verify(exactly = 0) { throttler.enqueue(any()) }
    }

    @Test
    fun `redeliverAuthoredEvents returns 0 when gift wrap is disabled`() = runBlocking {
        every { giftWrap.enabled } returns false
        store(storedEvent("exp1"))
        assertEquals(0, publisher.redeliverAuthoredEvents("g1", listOf(newMember)))
        assertEquals(0, db.outboxDao().count())
        verify(exactly = 0) { giftWrap.wrapIfEnabled(any(), any()) }
        verify(exactly = 0) { throttler.enqueue(any()) }
    }

    @Test
    fun `redeliverAuthoredEvents respects the outbox cap and queues what fits`() = runBlocking {
        fillOutbox(4997)
        store(storedEvent("exp1"), storedEvent("exp2"), storedEvent("exp3"))

        // 3 events x 2 recipients = 6 wraps, but only 3 slots remain.
        val queued = publisher.redeliverAuthoredEvents("g1", listOf(newMember, anotherNewMember))

        assertEquals(3, queued)
        assertEquals(5000, db.outboxDao().count())
        verify(exactly = 3) { throttler.enqueue(any()) }
        // Every queued row came from the mock rewrap, not a pre-existing filler row.
        val redeliveries = db.outboxDao().getAll().filter { it.eventId.startsWith("exp") }
        assertEquals(3, redeliveries.size)
    }

    @Test
    fun `redeliverAuthoredEvents queues nothing when the outbox is already full`() = runBlocking {
        fillOutbox(5000)
        store(storedEvent("exp1"))
        assertEquals(0, publisher.redeliverAuthoredEvents("g1", listOf(newMember)))
        assertEquals(5000, db.outboxDao().count())
        verify(exactly = 0) { throttler.enqueue(any()) }
    }

    @Test
    fun `redeliverAuthoredEvents does not wrap inside the transaction`() = runBlocking {
        // The giftWrap mock in setup() asserts db.inTransaction() is false on every call.
        store(storedEvent("exp1"), storedEvent("exp2"))
        assertEquals(2, publisher.redeliverAuthoredEvents("g1", listOf(newMember)))
    }

    // --- publishMutation: corrections, deletions and settlements as commands ---

    @Test
    fun `correction recovery after reopen preserves signed event and deliveries and never reapplies stale command`() =
        runBlocking {
            repository().addExpense(expense, "g1")
            val original = checkNotNull(repository().getEditableExpense("g1", expense.id, myPub))
            val first = ExpenseCorrectionCommand("correction-1", original.revisionId)
            val corrected = expense.copy(description = "First edit")
            repository().correctExpense(expense.id, corrected, "g1", myPub, first)
            val edited = checkNotNull(repository().getEditableExpense("g1", expense.id, myPub))
            assertEquals(corrected, edited.expense)
            assertNotEquals(original.revisionId, edited.revisionId)
            val second = ExpenseCorrectionCommand("correction-2", edited.revisionId)
            repository().correctExpense(expense.id, expense.copy(description = "Newer edit"), "g1", myPub, second)
            val revisions = db.eventDao().getEventsByGroup("g1").filter { it.eventType == "expense_correction" }
            assertTrue(revisions[1].createdAt > revisions[0].createdAt)
            assertEquals(expense.timestamp, edited.expense.timestamp)
            val eventJson = db.eventDao().getEventsByGroup("g1").map { it.originalEventJson }
            val deliveries = db.outboxDao().getAll().map { it.eventJson }.toSet()
            db.close()
            openDatabase()
            assertEquals(corrected, repository().getSavedCorrection("g1", expense.id, myPub, first))
            repository().correctExpense(expense.id, corrected, "g1", myPub, first)
            val current = repository().getEditableExpense("g1", expense.id, myPub)
            assertEquals("Newer edit", current?.expense?.description)
            assertEquals(eventJson, db.eventDao().getEventsByGroup("g1").map { it.originalEventJson })
            assertEquals(deliveries, db.outboxDao().getAll().map { it.eventJson }.toSet())
            expectFailure<ExpenseSaveConflictException> {
                val changed = corrected.copy(description = "Changed retry")
                repository().correctExpense(expense.id, changed, "g1", myPub, first)
            }
            expectFailure<ExpenseRevisionConflictException> {
                val stale = first.copy(id = "unsaved-stale-command")
                repository().correctExpense(expense.id, corrected, "g1", myPub, stale)
            }
        }

    @Test
    fun `original and each correction occupy distinct relay addresses in storage`() = runBlocking {
        repository().addExpense(expense, "g1")
        val revision = checkNotNull(repository().getEditableExpense("g1", expense.id, myPub)).revisionId
        repository().correctExpense(expense.id, expense.copy(description = "Edit"), "g1", myPub)
        val addresses = db.eventDao().getEventsByGroup("g1").map { row ->
            NostrEvent.fromJson(checkNotNull(row.originalEventJson))?.tags?.single { it[0] == "d" }?.get(1)
        }
        assertEquals(2, addresses.size)
        assertEquals(2, addresses.distinct().size)
        assertTrue(addresses.all { it != null && it.startsWith("g1:") })
        assertNotEquals(revision, checkNotNull(repository().getEditableExpense("g1", expense.id, myPub)).revisionId)
    }

    @Test
    fun `concurrent correction commands against one revision admit exactly one`() = runBlocking {
        repository().addExpense(expense, "g1")
        val revision = checkNotNull(repository().getEditableExpense("g1", expense.id, myPub)).revisionId
        val results = listOf("first", "second").map { command ->
            async {
                runCatching {
                    repository().correctExpense(
                        expense.id,
                        expense.copy(description = command),
                        "g1",
                        myPub,
                        ExpenseCorrectionCommand(command, revision)
                    )
                }
            }
        }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is ExpenseRevisionConflictException })
        assertEquals(2, db.eventDao().getEventCount("g1"))
        assertEquals(4, db.outboxDao().count())
    }

    @Test
    fun `every mutation refuses stale group membership and epoch at commit`() = runBlocking {
        repository().addExpense(expense, "g1")
        val revision = checkNotNull(repository().getEditableExpense("g1", expense.id, myPub)).revisionId
        val baselineEvents = db.eventDao().getEventCount("g1")
        val baselineOutbox = db.outboxDao().count()
        every { keyStore.getString("g1:1", any()) } returns null
        for (changed in listOf(group.copy(members = listOf(myPub, otherPub)), group.copy(keyEpoch = 1))) {
            groupRepo.save(changed, if (changed.keyEpoch == 0) "group-key" else "changed-key")
            for (type in listOf("expense_correction", "expense_delete", "settlement")) {
                expectFailure<IllegalStateException> {
                    publisher.publishMutation(
                        commandEvent("$type-${changed.keyEpoch}", type, "$type-${changed.keyEpoch}"),
                        group,
                        type,
                        expense.id,
                        "$type-${changed.keyEpoch}",
                        if (type == "settlement") null else revision
                    )
                }
            }
        }
        assertEquals(baselineEvents, db.eventDao().getEventCount("g1"))
        assertEquals(baselineOutbox, db.outboxDao().count())
    }

    @Test
    fun `delete and correction CAS refuse a revision replaced after preparation`() = runBlocking {
        repository().addExpense(expense, "g1")
        val revision = checkNotNull(repository().getEditableExpense("g1", expense.id, myPub)).revisionId
        repository().correctExpense(
            expense.id,
            expense.copy(description = "New"),
            "g1",
            myPub,
            ExpenseCorrectionCommand("new", revision)
        )
        for (type in listOf("expense_correction", "expense_delete")) {
            expectFailure<ExpenseRevisionConflictException> {
                val stale = commandEvent(type, type, "stale-$type")
                publisher.publishMutation(stale, group, type, expense.id, "stale-$type", revision)
            }
        }
        assertEquals(2, db.eventDao().getEventCount("g1"))
    }

    @Test
    fun `a mutation command already saved is not saved twice`() = runBlocking {
        repository().addExpense(expense, "g1")
        val revision = checkNotNull(repository().getEditableExpense("g1", expense.id, myPub)).revisionId
        val first = commandEvent("first", "expense_correction", "cmd")
        assertTrue(publisher.publishMutation(first, group, "expense_correction", expense.id, "cmd", revision))
        // A retry re-signs the same command at a later second: new event id, same relay address.
        val retry = commandEvent("retry", "expense_correction", "cmd", createdAt = 2000)
        assertFalse(publisher.publishMutation(retry, group, "expense_correction", expense.id, "cmd", revision))
        assertEquals(2, db.eventDao().getEventCount("g1"))
        assertEquals(4, db.outboxDao().count())
        assertNull(db.eventDao().getEvent("retry"))
    }

    @Test
    fun `settlement commands validate the group snapshot and dedup on command id`() = runBlocking {
        val settlement = commandEvent("s1", "settlement", "s1")
        assertTrue(publisher.publishMutation(settlement, group, "settlement", "s1", "s1"))
        val retry = commandEvent("s1-retry", "settlement", "s1")
        assertFalse(publisher.publishMutation(retry, group, "settlement", "s1", "s1"))
        assertEquals(1, db.eventDao().getEventCount("g1"))
        assertEquals(2, db.outboxDao().count())
        expectFailure<IllegalArgumentException> {
            publisher.publishMutation(commandEvent("bad", "expense", "bad"), group, "expense", "s1", "bad")
        }
        expectFailure<IllegalArgumentException> {
            publisher.publishMutation(
                commandEvent("bad", "expense_delete", "bad"),
                group,
                "expense_delete",
                "s1",
                "bad"
            )
        }
    }

    @Test
    fun `mutation commands are capped by the outbox like expenses`() = runBlocking {
        fillOutbox(4999)
        expectFailure<OutboxFullException> {
            publisher.publishMutation(commandEvent("s1", "settlement", "s1"), group, "settlement", "s1", "s1")
        }
        assertNull(db.eventDao().getEvent("s1"))
        assertEquals(4999, db.outboxDao().count())
    }

    @Test
    fun `deleted expense never admits a stale correction`() = runBlocking {
        repository().addExpense(expense, "g1")
        val revision = checkNotNull(repository().getEditableExpense("g1", expense.id, myPub)).revisionId
        repository().deleteExpense(expense.id, "g1", expectedAuthorPubkey = myPub)
        expectFailure<ExpenseRevisionConflictException> {
            repository().correctExpense(expense.id, expense, "g1", myPub, ExpenseCorrectionCommand("stale", revision))
        }
        assertNull(repository().getEditableExpense("g1", expense.id, myPub))
        assertEquals(2, db.eventDao().getEventCount("g1"))
    }

    @Test
    fun `concurrent copies of one correction command reconcile without duplicate deliveries`() = runBlocking {
        repository().addExpense(expense, "g1")
        val revision = checkNotNull(repository().getEditableExpense("g1", expense.id, myPub)).revisionId
        val command = ExpenseCorrectionCommand("same-command", revision)
        val results = (1..2).map {
            async { runCatching { repository().correctExpense(expense.id, expense, "g1", myPub, command) } }
        }.awaitAll()
        assertTrue(results.all { it.isSuccess })
        assertEquals(2, db.eventDao().getEventCount("g1"))
        assertEquals(4, db.outboxDao().count())
    }

    @Test
    fun `concurrent copies of one correction command with differing payload report conflict`() = runBlocking {
        repository().addExpense(expense, "g1")
        val revision = checkNotNull(repository().getEditableExpense("g1", expense.id, myPub)).revisionId
        val command = ExpenseCorrectionCommand("same-command", revision)
        val results = listOf("first", "different").map { description ->
            async {
                runCatching {
                    val payload = expense.copy(description = description)
                    repository().correctExpense(expense.id, payload, "g1", myPub, command)
                }
            }
        }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is ExpenseSaveConflictException })
        assertEquals(2, db.eventDao().getEventCount("g1"))
        assertEquals(4, db.outboxDao().count())
    }

    @Test
    fun `command lookups stay author qualified`() = runBlocking {
        repository().addExpense(expense, "g1")
        val revision = checkNotNull(repository().getEditableExpense("g1", expense.id, myPub)).revisionId
        val mine = commandEvent("mine", "expense_correction", "shared")
        assertTrue(publisher.publishMutation(mine, group, "expense_correction", expense.id, "shared", revision))
        // Another author's expense under the same UUID and command id is a separate record.
        every { identity.getPublicKeyHex() } returns otherPub
        assertTrue(publisher.publishExpense(event.copy(id = "other-original", pubkey = otherPub), group, expense.id))
        assertTrue(
            publisher.publishMutation(
                commandEvent("theirs", "expense_correction", "shared").copy(pubkey = otherPub),
                group,
                "expense_correction",
                expense.id,
                "shared",
                "other-original"
            )
        )
        assertEquals(4, db.eventDao().getEventCount("g1"))
    }

    // --- publishCreatedGroup ---

    @Test
    fun `create persists group metadata and outbox atomically and retries preserve original key`() = runBlocking {
        val created = group.copy(id = "created", members = listOf(myPub))
        every { keyStore.getString("created:0", any()) } returns null
        every { keyStore.getString("created", any()) } returns null
        val meta = event.copy(
            id = "created-meta",
            tags = listOf(
                listOf("d", EventSigner.relayAddress("created", "group_meta", "creation")),
                listOf("g", "created")
            )
        )
        assertTrue(publisher.publishCreatedGroup(meta, created, "first-key"))
        assertTrue(publisher.hasCreatedGroupCommand("created", myPub, "creation"))
        assertFalse(publisher.hasCreatedGroupCommand("created", myPub, "other"))
        assertFalse(publisher.hasCreatedGroupCommand("created", otherPub, "creation"))
        db.close()
        openDatabase()
        assertFalse(publisher.publishCreatedGroup(meta.copy(id = "retry-meta"), created, "replacement-key"))
        assertNotNull(groupRepo.getById(created.id))
        assertEquals(1, db.eventDao().getEventCount(created.id))
        assertEquals(1, db.outboxDao().count())
        verify(exactly = 1) { keyStore.putString("created:0", "first-key") }
        verify(exactly = 0) { keyStore.putString("created:0", "replacement-key") }
        verify(exactly = 1) { throttler.enqueue(meta) }
    }

    @Test
    fun `failed create delivery rolls back group and metadata together`() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_creation BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'delivery failed'); END"
        )
        val created = group.copy(id = "created", members = listOf(myPub))
        expectFailure<Exception> {
            publisher.publishCreatedGroup(event.copy(id = "created-meta"), created, "group-key")
        }
        assertNull(groupRepo.getById(created.id))
        assertEquals(0, db.eventDao().getEventCount(created.id))
        assertEquals(0, db.outboxDao().count())
        verify(exactly = 0) { throttler.enqueue(any()) }
    }

    @Test
    fun `create refuses an identity that is not the creator without writing anything`() = runBlocking {
        val created = group.copy(id = "created", createdBy = otherPub, members = listOf(otherPub))
        expectFailure<IllegalStateException> {
            publisher.publishCreatedGroup(event.copy(id = "created-meta"), created, "group-key")
        }
        assertNull(groupRepo.getById(created.id))
        assertEquals(0, db.outboxDao().count())
        verify(exactly = 0) { keyStore.putString("created:0", any()) }
    }

    private suspend fun fillOutbox(count: Int) {
        db.withTransaction {
            repeat(count) { db.outboxDao().insert(OutboxEntity("queued-$it", "{}", 1)) }
        }
    }

    private suspend fun assertEmptySave() {
        assertEquals(0, db.eventDao().getEventCount("g1"))
        assertEquals(0, db.outboxDao().count())
        verify(exactly = 0) { throttler.enqueue(any()) }
        verify(exactly = 0) { drainScheduler.requestDrain() }
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ${T::class.simpleName}, got $failure", failure is T)
    }
}
