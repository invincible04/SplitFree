package com.splitfree.sync.event

import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip59
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.validation.EventValidator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class EventProcessorTest {
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val encryption = mockk<GroupEncryption>()
    private val signer = mockk<EventSigner>()
    private val identity = mockk<IdentityContract>()
    private val giftWrap = mockk<GiftWrapService>()
    private val eventValidator = mockk<EventValidator>()
    private val postProcessor = mockk<EventPostProcessor>(relaxed = true)
    private val membershipHistory = mockk<MembershipHistory>()

    private lateinit var processor: EventProcessor

    private val groupId = "group-123"
    private val pubkey = "ab".repeat(32)
    private val bob = "bb".repeat(32)
    private val groupKey = "key123"
    private val group = Group(groupId, "Test", "", pubkey, 1000, listOf(pubkey), listOf("wss://r"))
    private val twoMemberGroup = group.copy(members = listOf(pubkey, bob))

    /** Backing map for [useInMemoryEventStore]. */
    private val store = linkedMapOf<String, EventEntity>()

    private fun makeEvent(
        eventType: String = "expense",
        author: String = pubkey,
        expenseUuid: String? = "uuid1",
        content: String = "encrypted",
        createdAt: Long = System.currentTimeMillis() / 1000,
        id: String = "evt-${System.nanoTime()}"
    ) = NostrEvent(
        id = id,
        pubkey = author,
        createdAt = createdAt,
        kind = 30078,
        tags =
        buildList {
            add(listOf("g", groupId))
            add(listOf("t", eventType))
            expenseUuid?.let { add(listOf("x", it)) }
        },
        content = content,
        sig = "sig"
    )

    private fun expenseJson(
        id: String = "uuid1",
        amount: Long = 100,
        currency: String = "USD",
        paidBy: String = pubkey,
        splits: List<Pair<String, Long>> = listOf(pubkey to 100L)
    ): String {
        val splitJson = splits.joinToString(",") { (pk, share) -> """{"pubkey":"$pk","share":$share}""" }
        return """{"id":"$id","amount":$amount,"currency":"$currency","description":"test",""" +
            """"paid_by":"$paidBy","split_type":"equal","split_among":[$splitJson],"timestamp":1000}"""
    }

    private fun settlementJson(
        id: String = "s1",
        from: String = pubkey,
        to: String = bob,
        amount: Long = 100,
        currency: String = "USD",
        timestamp: Long = 1000
    ): String =
        """{"id":"$id","from":"$from","to":"$to","amount":$amount,"currency":"$currency","timestamp":$timestamp}"""

    private fun rotationJson(epoch: Int = 1, removed: String = bob) =
        """{"epoch":$epoch,"encrypted_keys":{},"members":["$pubkey"],"removed_member":"$removed"}"""

    /** Processor wired to a real [EventValidator] so payload rules are actually exercised. */
    private fun realValidatorProcessor() = EventProcessor(
        eventDao,
        groupRepo,
        encryption,
        signer,
        identity,
        giftWrap,
        EventValidator(),
        postProcessor,
        membershipHistory
    )

    /**
     * Map-backed DAO double with duplicate inserts, state updates and ordered pending reads.
     * It models row visibility, not Room transactions or persistence across restarts.
     */
    private fun useInMemoryEventStore() {
        coEvery { eventDao.insert(any()) } answers {
            val entity = firstArg<EventEntity>()
            if (store.containsKey(entity.eventId)) {
                -1L
            } else {
                store[entity.eventId] = entity
                1L
            }
        }
        coEvery { eventDao.getEvent(any()) } answers { store[firstArg()] }
        coEvery { eventDao.setApplyState(any(), any()) } answers {
            val id = firstArg<String>()
            store[id]?.let { store[id] = it.copy(applyState = secondArg()) }
            Unit
        }
        coEvery { eventDao.getPendingEvents(any()) } answers {
            val g = firstArg<String>()
            store.values
                .filter { it.groupId == g && it.applyState == EventEntity.APPLY_STATE_PENDING }
                .sortedWith(compareBy({ it.createdAt }, { it.eventId }))
        }
    }

    private fun storedRow(
        id: String,
        eventType: String = "expense",
        applyState: Int = EventEntity.APPLY_STATE_APPLIED,
        content: String = "encrypted",
        createdAt: Long = 1000
    ) = EventEntity(
        id, groupId, pubkey, createdAt, 30078, content, eventType, null, "sig", 2000,
        applyState = applyState
    )

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0

        every { eventValidator.isWithinRateLimit(any()) } returns true
        every { eventValidator.isWithinGroupRateLimit(any()) } returns true
        every { eventValidator.isTimestampValid(any()) } returns true
        every { eventValidator.isTimestampValidLenient(any()) } returns true
        every { eventValidator.isContentSafe(any()) } returns true
        every { eventValidator.isCorrectionAuthorValid(any(), any(), any()) } returns true
        every { eventValidator.isDeletedExpense(any(), any(), any()) } returns false
        every { eventValidator.isExpenseValid(any(), any()) } returns true
        every { eventValidator.isSettlementValid(any(), any(), any()) } returns true

        every { giftWrap.tryUnwrap(any()) } returns null
        every { signer.verify(any()) } returns true
        coEvery { groupRepo.getById(groupId) } returns group
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, any()) } returns groupKey
        coEvery { groupRepo.resolveRoster(any(), any()) } answers { secondArg() }
        coEvery { groupRepo.isHistoricalCreator(any(), any(), any(), any()) } returns false
        coEvery { groupRepo.hasRevocationInEpoch(any(), any(), any()) } returns false
        coEvery { groupRepo.retiredIdentities(any()) } returns com.splitfree.domain.model.group.RetiredIdentities.NONE
        every { encryption.decrypt(any(), groupKey) } returns expenseJson()
        // The relaxed dao would otherwise hand back a non-null relaxed EventEntity here.
        coEvery { eventDao.getEvent(any()) } returns null
        coEvery { eventDao.insert(any()) } returns 1L
        coEvery { eventDao.getAppliedDeletedExpenseUuidsByAuthor(any(), any()) } returns emptyList()
        coEvery { eventDao.getExpenseByAuthor(any(), any(), any()) } returns null
        coEvery { eventDao.getPendingEvents(any()) } returns emptyList()
        coEvery { postProcessor.handle(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            PostProcessOutcome.APPLIED
        coEvery { membershipHistory.historicalAuthors(any()) } returns emptySet()
        coEvery { membershipHistory.formerMembers(any()) } returns emptySet()
        coEvery { membershipHistory.removalEpochOf(any(), any()) } returns null

        processor =
            EventProcessor(
                eventDao,
                groupRepo,
                encryption,
                signer,
                identity,
                giftWrap,
                eventValidator,
                postProcessor,
                membershipHistory
            )
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `process stores valid expense event`() = runBlocking {
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertTrue(result.stored)
        assertEquals("expense", result.eventType)
        assertEquals("Test", result.groupName)
    }

    @Test
    fun `process rejects event with invalid signature`() = runBlocking {
        every { signer.verify(any()) } returns false
        // Non-gift-wrapped event should be rejected when signature is invalid
        every { giftWrap.tryUnwrap(any()) } returns null
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects event with invalid timestamp`() = runBlocking {
        every { eventValidator.isTimestampValid(any()) } returns false
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process uses lenient timestamp when flag set`() = runBlocking {
        every { eventValidator.isTimestampValid(any()) } returns false
        every { eventValidator.isTimestampValidLenient(any()) } returns true
        val result = processor.process(makeEvent(), knownGroupKey = groupKey, lenientTimestamp = true)
        assertTrue(result.stored)
    }

    @Test
    fun `process rejects event for unknown group`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns null
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects event from non-member`() = runBlocking {
        val stranger = "cc".repeat(32)
        val result = processor.process(makeEvent(author = stranger), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects rate-limited author`() = runBlocking {
        every { eventValidator.isWithinRateLimit(any()) } returns false
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
        // Rate limits run before the group lookup so a flood does not cost a DB read per event.
        coVerify(exactly = 0) { groupRepo.getById(any()) }
    }

    @Test
    fun `process rejects rate-limited group`() = runBlocking {
        every { eventValidator.isWithinGroupRateLimit(any()) } returns false
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
        coVerify(exactly = 0) { groupRepo.getById(any()) }
    }

    @Test
    fun `process consults rate limits before the group lookup`() = runBlocking {
        processor.process(makeEvent(), knownGroupKey = groupKey)
        coVerifyOrder {
            eventValidator.isWithinRateLimit(pubkey)
            eventValidator.isWithinGroupRateLimit(groupId)
            groupRepo.getById(groupId)
        }
    }

    @Test
    fun `process rejects unsafe content`() = runBlocking {
        every { eventValidator.isContentSafe(any()) } returns false
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process reports a lost insert race as ALREADY_APPLIED`() = runBlocking {
        // getEvent saw nothing, but another writer inserted the same id before our insert landed.
        coEvery { eventDao.insert(any()) } returns -1L
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
        assertEquals(IngestOutcome.ALREADY_APPLIED, result.outcome)
        coVerify(exactly = 0) { postProcessor.handle(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `process rejects deleted expense replay`() = runBlocking {
        every { eventValidator.isDeletedExpense(any(), any(), any()) } returns true
        coEvery { eventDao.getAppliedDeletedExpenseUuidsByAuthor(any(), any()) } returns listOf("uuid1")
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process accepts expense created before the latest settlement`() = runBlocking {
        // An expense timestamped before the latest settlement is still admitted: clock skew between
        // phones must not cause legitimate expenses to be silently dropped on peers.
        val now = System.currentTimeMillis() / 1000
        coEvery { eventDao.getLatestEventByType(groupId, "settlement") } returns
            EventEntity("settle-evt", groupId, pubkey, now, 30078, "enc", "settlement", "s1", "sig", now)
        val result = realValidatorProcessor().process(makeEvent(createdAt = now - 3600), knownGroupKey = groupKey)
        assertTrue("Expense older than latest settlement must be accepted", result.stored)
        coVerify(exactly = 0) { eventDao.getLatestEventByType(any(), eq("settlement")) }
    }

    @Test
    fun `process rejects correction from wrong author`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns
            EventEntity("orig", groupId, pubkey, now, 30078, "enc", "expense", "uuid1", "sig", now)
        every {
            eventValidator.isCorrectionAuthorValid(any(), any(), any())
        } returns false
        val result = processor.process(
            makeEvent(eventType = "expense_correction"),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
        assertEquals("business rule", result.reason)
    }

    @Test
    fun `process holds a correction whose original has not arrived and applies it once it has`() = runBlocking {
        useInMemoryEventStore()
        val now = System.currentTimeMillis() / 1000
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns null
        val correction = makeEvent(id = "corr1", eventType = "expense_correction", expenseUuid = "uuid1")

        val held = processor.process(correction, knownGroupKey = groupKey)

        assertTrue(held.stored)
        assertEquals(IngestOutcome.DEFERRED, held.outcome)
        assertEquals("missing original", held.reason)
        assertEquals(EventEntity.APPLY_STATE_PENDING, store["corr1"]?.applyState)
        assertEquals(0, processor.retryDeferred(groupId))
        assertEquals(EventEntity.APPLY_STATE_PENDING, store["corr1"]?.applyState)

        // The original lands (from anywhere): the next retry applies the held correction.
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns
            EventEntity("orig", groupId, pubkey, now, 30078, "enc", "expense", "uuid1", "sig", now)
        assertEquals(1, processor.retryDeferred(groupId))
        assertEquals(EventEntity.APPLY_STATE_APPLIED, store["corr1"]?.applyState)
        // A replay of the held record while pending is a re-drive, not a new row.
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns null
        store["corr1"] = store.getValue("corr1").copy(applyState = EventEntity.APPLY_STATE_PENDING)
        assertEquals(IngestOutcome.DEFERRED, processor.process(correction, knownGroupKey = groupKey).outcome)
        assertEquals(1, store.size)
    }

    @Test
    fun `process holds a delete ahead of its original and applies it when the original lands`() = runBlocking {
        useInMemoryEventStore()
        val now = System.currentTimeMillis() / 1000
        every { encryption.decrypt(any(), groupKey) } returns "{}"
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns null

        val held = processor.process(
            makeEvent(id = "del1", eventType = "expense_delete", expenseUuid = "uuid1"),
            knownGroupKey = groupKey
        )

        assertEquals(IngestOutcome.DEFERRED, held.outcome)
        assertEquals(EventEntity.APPLY_STATE_PENDING, store["del1"]?.applyState)
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns
            EventEntity("orig", groupId, pubkey, now, 30078, "enc", "expense", "uuid1", "sig", now)
        assertEquals(1, processor.retryDeferred(groupId))
        assertEquals(EventEntity.APPLY_STATE_APPLIED, store["del1"]?.applyState)
    }

    @Test
    fun `process stops holding corrections for an author past the pending quota`() = runBlocking {
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns null
        coEvery { eventDao.countPendingByAuthor(groupId, pubkey) } returns EventProcessor.MAX_PENDING_LEDGER_PER_AUTHOR

        val result = processor.process(makeEvent(eventType = "expense_correction"), knownGroupKey = groupKey)

        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertEquals("pending quota", result.reason)
        assertFalse(result.retryable)
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `rejections for a missing key or join are marked retryable, others are not`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } throws RuntimeException("wrong key")
        val undecryptable = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertEquals("undecryptable", undecryptable.reason)
        assertTrue(undecryptable.retryable)

        every { encryption.decrypt(any(), groupKey) } returns expenseJson()
        coEvery { groupRepo.getById(groupId) } returns group.copy(members = listOf("f".repeat(64)))
        val nonMember = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertEquals("not a member", nonMember.reason)
        assertTrue(nonMember.retryable)

        coEvery { groupRepo.getById(groupId) } returns group
        every { eventValidator.isExpenseValid(any(), any()) } returns false
        val malformed = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertEquals("invalid payload", malformed.reason)
        assertFalse(malformed.retryable)
    }

    @Test
    fun `process handles group_meta side effect`() = runBlocking {
        val meta =
            """{"name":"Updated","description":"",""" +
                """"created_by":"$pubkey","created_at":1000,""" +
                """"members":["$pubkey"],"relays":["wss://r"]}"""
        every { encryption.decrypt(any(), groupKey) } returns meta
        val result = processor.process(
            makeEvent(eventType = "group_meta", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
        coVerify {
            postProcessor.handle(eq("group_meta"), eq(meta), eq(pubkey), eq(groupId), any(), eq(false), any(), any())
        }
    }

    @Test
    fun `process rejects group_meta from non-creator`() = runBlocking {
        val stranger = "cc".repeat(32)
        val creatorGroup = group.copy(createdBy = pubkey, members = listOf(pubkey))
        coEvery { groupRepo.getById(groupId) } returns creatorGroup
        every { encryption.decrypt(any(), groupKey) } returns
            """{"name":"Test","members":["$pubkey","dd${"dd".repeat(31)}"],"relays":["wss://r"]}"""
        val result =
            processor.process(
                makeEvent(eventType = "group_meta", author = stranger, expenseUuid = null),
                knownGroupKey = groupKey
            )
        assertFalse(result.stored)
    }

    @Test
    fun `process accepts group_meta self-join announcement`() = runBlocking {
        val joiner = "cc".repeat(32)
        val creatorGroup = group.copy(createdBy = pubkey, members = listOf(pubkey))
        coEvery { groupRepo.getById(groupId) } returns creatorGroup
        // joiner adds only themselves: a valid self-join
        val meta =
            """{"name":"Test","description":"",""" +
                """"created_by":"$pubkey","created_at":1000,""" +
                """"members":["$pubkey","$joiner"],""" +
                """"relays":["wss://r"]}"""
        every { encryption.decrypt(any(), groupKey) } returns meta
        val result =
            processor.process(
                makeEvent(eventType = "group_meta", author = joiner, expenseUuid = null),
                knownGroupKey = groupKey
            )
        assertTrue(result.stored)
    }

    @Test
    fun `process accepts group_meta self-join whose roster omits members it has not seen`() = runBlocking {
        val joiner = "cc".repeat(32)
        val otherMember = "dd".repeat(32)
        val creatorGroup = group.copy(createdBy = pubkey, members = listOf(pubkey, otherMember))
        coEvery { groupRepo.getById(groupId) } returns creatorGroup
        // The joiner's meta does not list otherMember: they simply have not seen that join yet. The
        // roster it carries adds nobody but its author, and the member path only records the author's
        // own membership, so this is a legitimate self-join, not a removal.
        val meta =
            """{"name":"Test","description":"",""" +
                """"created_by":"$pubkey","created_at":1000,""" +
                """"members":["$pubkey","$joiner"],""" +
                """"relays":["wss://r"]}"""
        every { encryption.decrypt(any(), groupKey) } returns meta
        val result =
            processor.process(
                makeEvent(eventType = "group_meta", author = joiner, expenseUuid = null),
                knownGroupKey = groupKey
            )
        assertTrue("A joiner unaware of another member is a legitimate self-join", result.stored)
        coVerify {
            postProcessor.handle(eq("group_meta"), eq(meta), eq(joiner), eq(groupId), any(), any(), any(), any())
        }
    }

    @Test
    fun `process accepts concurrent self-joins whose rosters are each unaware of the other in any order`() =
        runBlocking {
            val joinerB = "cc".repeat(32)
            val joinerC = "dd".repeat(32)
            val onlyA = group.copy(createdBy = pubkey, members = listOf(pubkey))
            val metaB = """{"name":"Test","members":["$pubkey","$joinerB"],"relays":["wss://r"]}"""
            val metaC = """{"name":"Test","members":["$pubkey","$joinerC"],"relays":["wss://r"]}"""
            every { encryption.decrypt("enc-b", groupKey) } returns metaB
            every { encryption.decrypt("enc-c", groupKey) } returns metaC
            fun joinOf(author: String, content: String) =
                makeEvent(eventType = "group_meta", author = author, expenseUuid = null, content = content)

            // B first, then C (C's meta still only knows A and C).
            coEvery { groupRepo.getById(groupId) } returns onlyA
            assertTrue(processor.process(joinOf(joinerB, "enc-b"), knownGroupKey = groupKey).stored)
            coEvery { groupRepo.getById(groupId) } returns onlyA.copy(members = listOf(pubkey, joinerB))
            assertTrue(processor.process(joinOf(joinerC, "enc-c"), knownGroupKey = groupKey).stored)

            // C first, then B.
            coEvery { groupRepo.getById(groupId) } returns onlyA
            assertTrue(processor.process(joinOf(joinerC, "enc-c"), knownGroupKey = groupKey).stored)
            coEvery { groupRepo.getById(groupId) } returns onlyA.copy(members = listOf(pubkey, joinerC))
            assertTrue(processor.process(joinOf(joinerB, "enc-b"), knownGroupKey = groupKey).stored)

            coVerify(exactly = 4) { eventDao.insert(any()) }
        }

    @Test
    fun `process rejects a non-member group_meta that adds anyone but its author`() = runBlocking {
        val joiner = "cc".repeat(32)
        val smuggled = "dd".repeat(32)
        coEvery { groupRepo.getById(groupId) } returns group.copy(createdBy = pubkey, members = listOf(pubkey))
        every { encryption.decrypt(any(), groupKey) } returns
            """{"name":"Test","members":["$pubkey","$joiner","$smuggled"],"relays":["wss://r"]}"""
        val result =
            processor.process(
                makeEvent(eventType = "group_meta", author = joiner, expenseUuid = null),
                knownGroupKey = groupKey
            )
        assertFalse(result.stored)
        assertEquals("not a member", result.reason)
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `process rejects a non-member group_meta that does not list its author`() = runBlocking {
        val stranger = "cc".repeat(32)
        coEvery { groupRepo.getById(groupId) } returns group.copy(createdBy = pubkey, members = listOf(pubkey))
        every { encryption.decrypt(any(), groupKey) } returns
            """{"name":"Test","members":["$pubkey"],"relays":["wss://r"]}"""
        val result =
            processor.process(
                makeEvent(eventType = "group_meta", author = stranger, expenseUuid = null),
                knownGroupKey = groupKey
            )
        assertFalse(result.stored)
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `process handles key_rotation side effect`() = runBlocking {
        val rotationJson =
            """{"epoch":1,""" +
                """"encrypted_keys":{},""" +
                """"members":["$pubkey"],""" +
                """"removed_member":"removed"}"""
        every { encryption.decrypt(any(), groupKey) } returns rotationJson
        val result = processor.process(
            makeEvent(eventType = "key_rotation", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
        coVerify {
            postProcessor.handle(eq("key_rotation"), any(), eq(pubkey), eq(groupId), any(), any(), any(), any())
        }
    }

    @Test
    fun `process handles key_revocation side effect`() = runBlocking {
        val revokeJson =
            """{"oldPubkey":"$pubkey",""" +
                """"newPubkey":"new","reason":"test"}"""
        every { encryption.decrypt(any(), groupKey) } returns revokeJson
        val result = processor.process(
            makeEvent(eventType = "key_revocation", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
        coVerify {
            postProcessor.handle(eq("key_revocation"), any(), eq(pubkey), eq(groupId), any(), any(), any(), any())
        }
    }

    @Test
    fun `process extracts group ID from tags when not provided`() = runBlocking {
        val result = processor.process(makeEvent(), knownGroupId = null, knownGroupKey = groupKey)
        assertTrue(result.stored)
    }

    @Test
    fun `process returns null group key falls through`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns null
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, any()) } returns null
        val result = processor.process(makeEvent(), knownGroupId = groupId, knownGroupKey = null)
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects undecryptable expense instead of storing ciphertext`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } throws RuntimeException("bad")
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse("Undecryptable expense must not be stored", result.stored)
        coVerify(exactly = 0) { eventDao.insert(any()) }
        coVerify(exactly = 0) { postProcessor.handle(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `process rejects undecryptable settlement instead of storing ciphertext`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } throws RuntimeException("bad")
        val result = processor.process(makeEvent(eventType = "settlement"), knownGroupKey = groupKey)
        assertFalse("Undecryptable settlement must not be stored", result.stored)
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `process rejects undecryptable expense after exhausting older epoch keys`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group.copy(keyEpoch = 2)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns "old1"
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns "old0"
        every { encryption.decrypt(any(), any<String>()) } throws RuntimeException("bad")
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
        coVerify { groupRepo.getGroupKeyForEpoch(groupId, 1) }
        coVerify { groupRepo.getGroupKeyForEpoch(groupId, 0) }
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `process unwraps gift-wrapped events`() = runBlocking {
        val inner = makeEvent()
        every { giftWrap.tryUnwrap(any()) } returns Nip59.Unwrapped(inner, "sender", "sealsig")
        // Rumor has sig="" so signer.verify would return false, but we skip it for unwrapped events
        every { signer.verify(any()) } returns false
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertTrue(result.stored)
    }

    @Test
    fun `process stores gift-wrapped rumor with seal-marked signature`() = runBlocking {
        val rumor = makeEvent().copy(sig = "")
        val sealSig = "ab".repeat(64)
        every { giftWrap.tryUnwrap(any()) } returns Nip59.Unwrapped(rumor, pubkey, sealSig)
        every { signer.verify(any()) } returns false
        val stored = slot<EventEntity>()
        coEvery { eventDao.insert(capture(stored)) } returns 1L

        val result = processor.process(makeEvent(), knownGroupKey = groupKey)

        assertTrue(result.stored)
        assertEquals(EventSnapshot.SEAL_SIG_PREFIX + sealSig, stored.captured.sig)
        assertTrue(stored.captured.sig.startsWith("seal:"))
        assertFalse(EventSnapshot.isThirdPartyVerifiable(stored.captured.sig))
        // The rumor itself is persisted unchanged (unsigned); the marker lives only in the sig column.
        assertEquals(rumor.toJson(), stored.captured.originalEventJson)
        assertEquals(rumor.id, stored.captured.eventId)
    }

    @Test
    fun `process stores direct signed event with its own signature`() = runBlocking {
        val stored = slot<EventEntity>()
        coEvery { eventDao.insert(capture(stored)) } returns 1L

        val result = processor.process(makeEvent(), knownGroupKey = groupKey)

        assertTrue(result.stored)
        assertEquals("sig", stored.captured.sig)
        assertTrue(EventSnapshot.isThirdPartyVerifiable(stored.captured.sig))
    }

    @Test
    fun `process rejects event without group tag`() = runBlocking {
        val noGroupEvent =
            NostrEvent(
                id = "evt1",
                pubkey = pubkey,
                createdAt = System.currentTimeMillis() / 1000,
                kind = 30078,
                tags = listOf(listOf("t", "expense")),
                content = "enc",
                sig = "sig"
            )
        val result = processor.process(noGroupEvent, knownGroupId = null, knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    // --- post-processing delegation ---

    @Test
    fun `process with nonCancellable true runs group_meta side effect`() = runBlocking {
        val meta =
            """{"name":"NC","description":"",""" +
                """"created_by":"$pubkey","created_at":1000,""" +
                """"members":["$pubkey"],"relays":["wss://r"]}"""
        every { encryption.decrypt(any(), groupKey) } returns meta
        val result =
            processor.process(
                makeEvent(eventType = "group_meta", expenseUuid = null),
                knownGroupKey = groupKey,
                nonCancellable = true
            )
        assertTrue(result.stored)
        coVerify {
            postProcessor.handle(eq("group_meta"), eq(meta), eq(pubkey), eq(groupId), any(), eq(true), any(), any())
        }
    }

    @Test
    fun `process group_meta with empty members skips updateFromMeta`() = runBlocking {
        val meta =
            """{"name":"Empty","description":"",""" +
                """"created_by":"$pubkey","created_at":1000,""" +
                """"members":[],"relays":[]}"""
        every { encryption.decrypt(any(), groupKey) } returns meta
        val result = processor.process(
            makeEvent(eventType = "group_meta", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
        coVerify(exactly = 0) {
            groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `process group_meta exception is caught`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns "not-json"
        val result = processor.process(
            makeEvent(eventType = "group_meta", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
    }

    @Test
    fun `process key_rotation exception is caught`() = runBlocking {
        // No exception is injected here: this verifies delegation and the stored result only.
        every { encryption.decrypt(any(), groupKey) } returns """{"data":"x"}"""
        val result = processor.process(
            makeEvent(eventType = "key_rotation", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
        coVerify {
            postProcessor.handle(eq("key_rotation"), any(), eq(pubkey), eq(groupId), any(), any(), any(), any())
        }
    }

    @Test
    fun `process key_revocation exception is caught`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns """{"data":"x"}"""
        val result = processor.process(
            makeEvent(eventType = "key_revocation", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
        coVerify {
            postProcessor.handle(eq("key_revocation"), any(), eq(pubkey), eq(groupId), any(), any(), any(), any())
        }
    }

    @Test
    fun `process accepts group_meta from non-creator member and defers mutation controls`() = runBlocking {
        val member = "cc".repeat(32)
        val creatorGroup = group.copy(createdBy = pubkey, members = listOf(pubkey, member))
        coEvery { groupRepo.getById(groupId) } returns creatorGroup
        // Mutation limits are enforced in EventPostProcessor; processor should only validate auth envelope.
        val meta =
            """{"name":"Hacked","description":"",""" +
                """"created_by":"$pubkey","created_at":1000,""" +
                """"members":["$member"],""" +
                """"relays":["wss://r"]}"""
        every { encryption.decrypt(any(), groupKey) } returns meta
        val result =
            processor.process(
                makeEvent(eventType = "group_meta", author = member, expenseUuid = null),
                knownGroupKey = groupKey
            )
        assertTrue(result.stored)
    }

    @Test
    fun `process rejects group_meta from non-member non-creator`() = runBlocking {
        val stranger = "cc".repeat(32)
        val creatorGroup = group.copy(createdBy = pubkey, members = listOf(pubkey))
        coEvery { groupRepo.getById(groupId) } returns creatorGroup
        // decrypt fails, so the self-join cannot be verified and is rejected
        every { encryption.decrypt(any(), any<String>()) } throws RuntimeException("decrypt failed")
        val result =
            processor.process(
                makeEvent(eventType = "group_meta", author = stranger, expenseUuid = null),
                knownGroupKey = groupKey
            )
        assertFalse(result.stored)
    }

    @Test
    fun `process expense_delete checks correction author`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns
            EventEntity("orig", groupId, pubkey, now, 30078, "enc", "expense", "uuid1", "sig", now)
        every { eventValidator.isCorrectionAuthorValid(any(), any(), any()) } returns false
        val result = processor.process(makeEvent(eventType = "expense_delete"), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process expense_correction accepts events created before the latest settlement`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        coEvery { eventDao.getLatestEventByType(groupId, "settlement") } returns
            EventEntity("settle-evt", groupId, pubkey, now, 30078, "enc", "settlement", "s1", "sig", now)
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns
            EventEntity("orig", groupId, pubkey, now - 7200, 30078, "enc", "expense", "uuid1", "sig", now)
        val result =
            realValidatorProcessor().process(
                makeEvent(eventType = "expense_correction", createdAt = now - 3600),
                knownGroupKey = groupKey
            )
        assertTrue(result.stored)
    }

    @Test
    fun `process ignores tags with size less than 2`() = runBlocking {
        val event =
            NostrEvent(
                id = "evt1",
                pubkey = pubkey,
                createdAt = System.currentTimeMillis() / 1000,
                kind = 30078,
                tags = listOf(listOf("g", groupId), listOf("t", "expense"), listOf("x"), listOf("x", "uuid1")),
                content = "enc",
                sig = "sig"
            )
        val result = processor.process(event, knownGroupKey = groupKey)
        assertTrue(result.stored)
    }

    @Test
    fun `process settlement type skips tombstone check`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns settlementJson()
        val result = processor.process(
            makeEvent(eventType = "settlement", expenseUuid = "s1"),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
        coVerify(exactly = 0) { eventDao.getAppliedDeletedExpenseUuidsByAuthor(any(), any()) }
    }

    @Test
    fun `process rejects settlement with unparseable content`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns "not-valid-settlement-json"
        val result = processor.process(
            makeEvent(eventType = "settlement", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects settlement when validator rejects payload`() = runBlocking {
        every { eventValidator.isSettlementValid(any(), any(), any()) } returns false
        every { encryption.decrypt(any(), groupKey) } returns settlementJson()
        val result = processor.process(
            makeEvent(eventType = "settlement", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects settlement not authored by participant`() = runBlocking {
        val carol = "cc".repeat(32)
        coEvery { groupRepo.getById(groupId) } returns group.copy(members = listOf(pubkey, bob, carol))
        every { encryption.decrypt(any(), groupKey) } returns settlementJson(from = pubkey, to = bob)
        val result = realValidatorProcessor().process(
            makeEvent(eventType = "settlement", author = carol, expenseUuid = "s1"),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
    }

    @Test
    fun `process accepts settlement authored by a participant with matching x tag`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        every { encryption.decrypt(any(), groupKey) } returns settlementJson(id = "s1", from = pubkey, to = bob)
        val result = realValidatorProcessor().process(
            makeEvent(eventType = "settlement", expenseUuid = "s1"),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
    }

    @Test
    fun `process rejects settlement without x tag`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        every { encryption.decrypt(any(), groupKey) } returns settlementJson(id = "s1", from = pubkey, to = bob)
        val result = realValidatorProcessor().process(
            makeEvent(eventType = "settlement", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
        coVerify(exactly = 0) { eventDao.insertIfNew(any()) }
    }

    @Test
    fun `process rejects settlement whose x tag does not match payload id`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        every { encryption.decrypt(any(), groupKey) } returns settlementJson(id = "s1", from = pubkey, to = bob)
        val result = realValidatorProcessor().process(
            makeEvent(eventType = "settlement", expenseUuid = "s-other"),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `process rejects self-settlement`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns settlementJson(from = pubkey, to = pubkey)
        val result = realValidatorProcessor().process(
            makeEvent(eventType = "settlement", expenseUuid = "s1"),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects settlement to non-member`() = runBlocking {
        // default group only contains pubkey; bob is not a member
        every { encryption.decrypt(any(), groupKey) } returns settlementJson(from = pubkey, to = bob)
        val result = realValidatorProcessor().process(
            makeEvent(eventType = "settlement", expenseUuid = "s1"),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
    }

    @Test
    fun `process with nonCancellable true runs key_rotation`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns """{"data":"x"}"""
        val result =
            processor.process(
                makeEvent(eventType = "key_rotation", expenseUuid = null),
                knownGroupKey = groupKey,
                nonCancellable = true
            )
        assertTrue(result.stored)
        coVerify {
            postProcessor.handle(eq("key_rotation"), any(), eq(pubkey), eq(groupId), any(), any(), any(), any())
        }
    }

    @Test
    fun `process with nonCancellable true runs key_revocation`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns """{"data":"x"}"""
        val result =
            processor.process(
                makeEvent(eventType = "key_revocation", expenseUuid = null),
                knownGroupKey = groupKey,
                nonCancellable = true
            )
        assertTrue(result.stored)
        coVerify {
            postProcessor.handle(eq("key_revocation"), any(), eq(pubkey), eq(groupId), any(), any(), any(), any())
        }
    }

    @Test
    fun `process decrypted null skips side effects`() = runBlocking {
        every {
            encryption.decrypt(any(), groupKey)
        } throws RuntimeException("bad")
        val result = processor.process(
            makeEvent(eventType = "group_meta", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
        coVerify(exactly = 0) {
            groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `process rejects key_revocation from non-member`() = runBlocking {
        val stranger = "cc".repeat(32)
        every { encryption.decrypt(any(), groupKey) } returns """{"oldPubkey":"x","newPubkey":"y"}"""
        val result = processor.process(
            makeEvent(eventType = "key_revocation", author = stranger, expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects key_rotation from member who is not creator`() = runBlocking {
        val member = "cc".repeat(32)
        val creatorGroup = group.copy(createdBy = pubkey, members = listOf(pubkey, member))
        coEvery { groupRepo.getById(groupId) } returns creatorGroup
        every { encryption.decrypt(any(), groupKey) } returns
            """{"epoch":1,"encrypted_keys":{},"members":["$pubkey"],"removed_member":"$member"}"""
        val result = processor.process(
            makeEvent(eventType = "key_rotation", author = member, expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects undecryptable key_revocation`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } throws RuntimeException("bad")
        val result = processor.process(
            makeEvent(eventType = "key_revocation", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
    }

    @Test
    fun `process expense with null expenseUuid skips tombstone check but is rejected for missing x tag`() =
        runBlocking {
            val result = processor.process(
                makeEvent(eventType = "expense", expenseUuid = null),
                knownGroupKey = groupKey
            )
            assertFalse("Expense without x tag cannot match its payload id", result.stored)
            coVerify(exactly = 0) { eventDao.getAppliedDeletedExpenseUuidsByAuthor(any(), any()) }
            coVerify(exactly = 0) { eventDao.insert(any()) }
        }

    @Test
    fun `process expense_correction with null expenseUuid skips original creator lookup but is rejected`() =
        runBlocking {
            val result = processor.process(
                makeEvent(
                    eventType = "expense_correction",
                    expenseUuid = null
                ),
                knownGroupKey = groupKey
            )
            assertFalse("Correction without x tag cannot match its payload id", result.stored)
            coVerify(exactly = 0) { eventDao.getExpenseByAuthor(any(), any(), any()) }
            coVerify(exactly = 0) { eventDao.insert(any()) }
        }

    @Test
    fun `process expense_delete with null expenseUuid`() = runBlocking {
        // Without an x tag there is nothing a delete could ever resolve against; it is refused, not held.
        val result = processor.process(
            makeEvent(eventType = "expense_delete", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
        assertEquals("business rule", result.reason)
    }

    @Test
    fun `process event with unknown tag key`() = runBlocking {
        val event =
            NostrEvent(
                id = "evt1",
                pubkey = pubkey,
                createdAt = System.currentTimeMillis() / 1000,
                kind = 30078,
                tags = listOf(
                    listOf("g", groupId),
                    listOf("t", "expense"),
                    listOf("z", "unknown"),
                    listOf("x", "uuid1")
                ),
                content = "enc",
                sig = "sig"
            )
        val result = processor.process(event, knownGroupKey = groupKey)
        assertTrue(result.stored)
    }

    @Test
    fun `process group_meta CancellationException is rethrown`() {
        coEvery {
            postProcessor.handle(eq("group_meta"), any(), any(), any(), any(), any(), any(), any())
        } throws kotlinx.coroutines.CancellationException("cancel")
        val meta =
            """{"name":"X","description":"",""" +
                """"created_by":"$pubkey","created_at":1000,""" +
                """"members":["$pubkey"],"relays":["wss://r"]}"""
        every { encryption.decrypt(any(), groupKey) } returns meta
        try {
            runBlocking {
                processor.process(
                    makeEvent(eventType = "group_meta", expenseUuid = null),
                    knownGroupKey = groupKey
                )
            }
            fail("Expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }

    @Test
    fun `process key_rotation CancellationException is rethrown`() {
        coEvery {
            postProcessor.handle(eq("key_rotation"), any(), any(), any(), any(), any(), any(), any())
        } throws kotlinx.coroutines.CancellationException("cancel")
        every {
            encryption.decrypt(any(), groupKey)
        } returns """{"data":"x"}"""
        try {
            runBlocking {
                processor.process(
                    makeEvent(
                        eventType = "key_rotation",
                        expenseUuid = null
                    ),
                    knownGroupKey = groupKey
                )
            }
            fail("Expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }

    @Test
    fun `process key_revocation CancellationException is rethrown`() {
        coEvery {
            postProcessor.handle(eq("key_revocation"), any(), any(), any(), any(), any(), any(), any())
        } throws kotlinx.coroutines.CancellationException("cancel")
        every {
            encryption.decrypt(any(), groupKey)
        } returns """{"data":"x"}"""
        try {
            runBlocking {
                processor.process(
                    makeEvent(
                        eventType = "key_revocation",
                        expenseUuid = null
                    ),
                    knownGroupKey = groupKey
                )
            }
            fail("Expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }

    @Test
    fun `process rejects expense when validator rejects payload`() = runBlocking {
        every { eventValidator.isExpenseValid(any(), any()) } returns false
        val result = processor.process(makeEvent(eventType = "expense"), knownGroupKey = groupKey)
        assertFalse("Expense with invalid payload must be rejected", result.stored)
    }

    @Test
    fun `process rejects expense_correction when validator rejects payload`() = runBlocking {
        every { eventValidator.isExpenseValid(any(), any()) } returns false
        val result = processor.process(makeEvent(eventType = "expense_correction"), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process passes current group members to expense validator`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        processor.process(makeEvent(), knownGroupKey = groupKey)
        verify { eventValidator.isExpenseValid(any(), eq(setOf(pubkey, bob))) }
    }

    @Test
    fun `process rejects expense with unparseable content`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns "not-valid-expense-json"
        val result = processor.process(makeEvent(eventType = "expense"), knownGroupKey = groupKey)
        assertFalse("Unparseable expense content must be rejected", result.stored)
    }

    // --- Remote payload rules exercised with a real EventValidator ---

    @Test
    fun `known author expense waits for missing split participant membership`() = runBlocking {
        assertParticipantMembershipRecovers("expense", expenseJson(splits = listOf(pubkey to 50L, bob to 50L)))
    }

    @Test
    fun `known author expense waits for missing payer membership`() = runBlocking {
        assertParticipantMembershipRecovers("expense", expenseJson(paidBy = bob))
    }

    @Test
    fun `known author correction waits for missing participant membership`() = runBlocking {
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns
            storedRow("original").copy(expenseUuid = "uuid1")
        assertParticipantMembershipRecovers("expense_correction", expenseJson(splits = listOf(bob to 100L)))
    }

    @Test
    fun `known author settlement waits for missing counterparty membership`() = runBlocking {
        assertParticipantMembershipRecovers("settlement", settlementJson(id = "uuid1"))
    }

    private suspend fun assertParticipantMembershipRecovers(eventType: String, payload: String) {
        val realProcessor = realValidatorProcessor()
        val event = makeEvent(eventType = eventType)
        every { encryption.decrypt(any(), groupKey) } returns payload

        val rejected = realProcessor.process(event, context = IngestionContext.RECONCILIATION)
        assertEquals(IngestOutcome.REJECTED, rejected.outcome)
        assertEquals("missing participant", rejected.reason)
        assertTrue(rejected.retryable)
        assertFalse(rejected.stored)
        coVerify(exactly = 0) { eventDao.insert(any()) }
        coVerify(exactly = 0) { postProcessor.handle(any(), any(), any(), any(), any(), any(), any(), any()) }

        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        val accepted = realProcessor.process(event, context = IngestionContext.RECONCILIATION)
        assertEquals(IngestOutcome.APPLIED, accepted.outcome)
        assertTrue(accepted.stored)
        assertFalse(accepted.retryable)
        coVerify(exactly = 1) { eventDao.insert(match { it.eventId == event.id }) }
    }

    @Test
    fun `missing expense participant never makes malformed payload retryable`() = runBlocking {
        val payloads = listOf(
            "not-json",
            expenseJson(amount = -1, splits = listOf(bob to 100L)),
            expenseJson(currency = "usd", splits = listOf(bob to 100L)),
            expenseJson(splits = listOf(bob to 90L)),
            expenseJson(splits = listOf(bob to 0L, pubkey to 100L)),
            expenseJson(splits = listOf(bob to 50L, bob to 50L)),
            expenseJson(splits = listOf(bob to Long.MAX_VALUE, pubkey to 1L)),
            expenseJson(id = "different", splits = listOf(bob to 100L))
        )
        val realProcessor = realValidatorProcessor()
        for (eventType in listOf("expense", "expense_correction")) {
            for (payload in payloads) {
                every { encryption.decrypt(any(), groupKey) } returns payload
                val result = realProcessor.process(
                    makeEvent(eventType = eventType),
                    context = IngestionContext.RECONCILIATION
                )
                assertEquals(payload, "invalid payload", result.reason)
                assertEquals(IngestOutcome.REJECTED, result.outcome)
                assertFalse(payload, result.retryable)
                assertFalse(result.stored)
            }
        }
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `missing settlement participant never makes invalid payload or unrelated author retryable`() = runBlocking {
        val payloads = listOf(
            "not-json",
            settlementJson(id = "uuid1", amount = 0),
            settlementJson(id = "uuid1", currency = "usd"),
            settlementJson(id = "uuid1", from = bob, to = bob),
            settlementJson(id = "uuid1", from = bob, to = "cc".repeat(32)),
            settlementJson(id = "different")
        )
        val realProcessor = realValidatorProcessor()
        for (payload in payloads) {
            every { encryption.decrypt(any(), groupKey) } returns payload
            val result = realProcessor.process(
                makeEvent(eventType = "settlement"),
                context = IngestionContext.RECONCILIATION
            )
            assertEquals(payload, "invalid payload", result.reason)
            assertEquals(IngestOutcome.REJECTED, result.outcome)
            assertFalse(payload, result.retryable)
            assertFalse(result.stored)
        }
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `missing participant does not bypass content safety`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns
            expenseJson(splits = listOf(bob to 100L)).replace("test", "x".repeat(65_537))
        val result = realValidatorProcessor().process(makeEvent(), context = IngestionContext.RECONCILIATION)
        assertEquals("unsafe content", result.reason)
        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertFalse(result.retryable)
        assertFalse(result.stored)
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `process rejects expense whose shares do not sum to amount`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        every { encryption.decrypt(any(), groupKey) } returns
            expenseJson(amount = 100, splits = listOf(pubkey to 50L, bob to 40L))
        val result = realValidatorProcessor().process(makeEvent(), knownGroupKey = groupKey)
        assertFalse("sum(shares) != amount must be rejected", result.stored)
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `process accepts expense whose shares sum to amount`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        every { encryption.decrypt(any(), groupKey) } returns
            expenseJson(amount = 100, splits = listOf(pubkey to 60L, bob to 40L))
        val result = realValidatorProcessor().process(makeEvent(), knownGroupKey = groupKey)
        assertTrue(result.stored)
    }

    @Test
    fun `process rejects expense whose x tag does not match payload id`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns expenseJson(id = "uuid-payload")
        val result = realValidatorProcessor().process(makeEvent(expenseUuid = "uuid-tag"), knownGroupKey = groupKey)
        assertFalse("x tag must match payload id", result.stored)
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `process rejects expense_correction whose payload id does not match original uuid`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns
            EventEntity("orig", groupId, pubkey, now, 30078, "enc", "expense", "uuid1", "sig", now)
        every { encryption.decrypt(any(), groupKey) } returns expenseJson(id = "uuid-new")
        val result = realValidatorProcessor().process(
            makeEvent(eventType = "expense_correction", expenseUuid = "uuid1"),
            knownGroupKey = groupKey
        )
        assertFalse("Correction payload must keep the original uuid", result.stored)
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `process rejects expense_correction whose shares do not sum to amount`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns
            EventEntity("orig", groupId, pubkey, now, 30078, "enc", "expense", "uuid1", "sig", now)
        every { encryption.decrypt(any(), groupKey) } returns
            expenseJson(amount = 200, splits = listOf(pubkey to 100L, bob to 50L))
        val result = realValidatorProcessor().process(
            makeEvent(eventType = "expense_correction", expenseUuid = "uuid1"),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects expense paid by non-member`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns expenseJson(paidBy = bob)
        val result = realValidatorProcessor().process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects expense split with non-member`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns
            expenseJson(amount = 100, splits = listOf(pubkey to 50L, bob to 50L))
        val result = realValidatorProcessor().process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects expense with zero share`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        every { encryption.decrypt(any(), groupKey) } returns
            expenseJson(amount = 100, splits = listOf(pubkey to 100L, bob to 0L))
        val result = realValidatorProcessor().process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects expense with invalid currency`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns expenseJson(currency = "usd")
        val result = realValidatorProcessor().process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    // --- Ingestion outcomes and contexts ---

    @Test
    fun `process reports APPLIED with the inner event id for a stored expense`() = runBlocking {
        val event = makeEvent()
        val result = processor.process(event, knownGroupKey = groupKey)
        assertEquals(IngestOutcome.APPLIED, result.outcome)
        assertTrue(result.stored)
        assertEquals(event.id, result.eventId)
        assertNull(result.reason)
    }

    @Test
    fun `process carries a reason and the event id on rejection`() = runBlocking {
        every { eventValidator.isTimestampValid(any()) } returns false
        val event = makeEvent()
        val result = processor.process(event, knownGroupKey = groupKey)
        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertEquals(event.id, result.eventId)
        assertEquals("expense", result.eventType)
        assertEquals(pubkey, result.authorHex)
        assertEquals("invalid timestamp", result.reason)
    }

    @Test
    fun `process reports ALREADY_APPLIED for a known applied event without re-processing it`() = runBlocking {
        val event = makeEvent()
        coEvery { eventDao.getEvent(event.id) } returns storedRow(event.id)

        val result = processor.process(event, knownGroupKey = groupKey)

        assertFalse(result.stored)
        assertEquals(IngestOutcome.ALREADY_APPLIED, result.outcome)
        assertEquals(event.id, result.eventId)
        assertEquals("expense", result.eventType)
        assertEquals(pubkey, result.authorHex)
        coVerify(exactly = 0) { eventDao.insert(any()) }
        coVerify(exactly = 0) { postProcessor.handle(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.getById(any()) }
    }

    @Test
    fun `process duplicate check runs before rate limits and does not charge them`() = runBlocking {
        val event = makeEvent()
        coEvery { eventDao.getEvent(event.id) } returns storedRow(event.id)

        processor.process(event, knownGroupKey = groupKey)

        verify(exactly = 0) { eventValidator.isWithinRateLimit(any()) }
        verify(exactly = 0) { eventValidator.isWithinGroupRateLimit(any()) }
        verify(exactly = 0) { eventValidator.isTimestampValid(any()) }
    }

    @Test
    fun `process replaying one event 40 times in LIVE leaves the author budget intact`() = runBlocking {
        useInMemoryEventStore()
        val real = realValidatorProcessor()
        val replayed = makeEvent()

        val first = real.process(replayed, knownGroupKey = groupKey)
        assertEquals(IngestOutcome.APPLIED, first.outcome)
        repeat(39) {
            assertEquals(IngestOutcome.ALREADY_APPLIED, real.process(replayed, knownGroupKey = groupKey).outcome)
        }

        // A fresh event from the same author must still be within its 30/min budget.
        val fresh = real.process(makeEvent(), knownGroupKey = groupKey)
        assertEquals(IngestOutcome.APPLIED, fresh.outcome)
        assertEquals(2, store.size)
    }

    @Test
    fun `process rate limits apply in LIVE with a real validator`() = runBlocking {
        val real = realValidatorProcessor()
        val outcomes = (1..31).map { real.process(makeEvent(), knownGroupKey = groupKey).outcome }
        assertEquals(30, outcomes.count { it == IngestOutcome.APPLIED })
        assertEquals(IngestOutcome.REJECTED, outcomes.last())
        assertEquals(
            "author rate limit",
            real.process(makeEvent(), knownGroupKey = groupKey).reason
        )
    }

    @Test
    fun `process rate-limited author in LIVE is REJECTED`() = runBlocking {
        every { eventValidator.isWithinRateLimit(any()) } returns false
        val result = processor.process(makeEvent(), knownGroupKey = groupKey, context = IngestionContext.LIVE)
        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertEquals("author rate limit", result.reason)
    }

    @Test
    fun `process in RECONCILIATION applies 31 events by one author`() = runBlocking {
        val real = realValidatorProcessor()
        val results = (1..31).map {
            real.process(makeEvent(), knownGroupKey = groupKey, context = IngestionContext.RECONCILIATION)
        }
        assertTrue(results.all { it.outcome == IngestOutcome.APPLIED })
        coVerify(exactly = 31) { eventDao.insert(any()) }
    }

    @Test
    fun `process in RECONCILIATION applies 61 events across three authors`() = runBlocking {
        val carol = "cc".repeat(32)
        coEvery { groupRepo.getById(groupId) } returns group.copy(members = listOf(pubkey, bob, carol))
        val real = realValidatorProcessor()
        val authors = listOf(pubkey, bob, carol)
        val results = (0 until 61).map { i ->
            real.process(
                makeEvent(author = authors[i % 3]),
                knownGroupKey = groupKey,
                context = IngestionContext.RECONCILIATION
            )
        }
        assertEquals(61, results.count { it.outcome == IngestOutcome.APPLIED })
    }

    @Test
    fun `process in RECONCILIATION never consults the rate counters`() = runBlocking {
        processor.process(makeEvent(), knownGroupKey = groupKey, context = IngestionContext.RECONCILIATION)
        verify(exactly = 0) { eventValidator.isWithinRateLimit(any()) }
        verify(exactly = 0) { eventValidator.isWithinGroupRateLimit(any()) }
    }

    @Test
    fun `process accepts a 31-day-old event in RECONCILIATION and rejects it in LIVE`() = runBlocking {
        val real = realValidatorProcessor()
        val old = System.currentTimeMillis() / 1000 - 31L * 86400L

        val live = real.process(makeEvent(createdAt = old), knownGroupKey = groupKey)
        assertEquals(IngestOutcome.REJECTED, live.outcome)
        assertEquals("invalid timestamp", live.reason)

        val reconciled = real.process(
            makeEvent(createdAt = old),
            knownGroupKey = groupKey,
            context = IngestionContext.RECONCILIATION
        )
        assertEquals(IngestOutcome.APPLIED, reconciled.outcome)
    }

    @Test
    fun `process RECONCILIATION uses lenient timestamp validation`() = runBlocking {
        every { eventValidator.isTimestampValid(any()) } returns false
        every { eventValidator.isTimestampValidLenient(any()) } returns true
        val result = processor.process(makeEvent(), knownGroupKey = groupKey, context = IngestionContext.RECONCILIATION)
        assertEquals(IngestOutcome.APPLIED, result.outcome)
        verify(exactly = 0) { eventValidator.isTimestampValid(any()) }
    }

    @Test
    fun `process rejects malformed and far-future timestamps even in RECONCILIATION`() = runBlocking {
        val real = realValidatorProcessor()
        val now = System.currentTimeMillis() / 1000
        for (bad in listOf(0L, -5L, now + 2 * 3600)) {
            val result = real.process(
                makeEvent(createdAt = bad),
                knownGroupKey = groupKey,
                context = IngestionContext.RECONCILIATION
            )
            assertEquals("created_at=$bad must be rejected", IngestOutcome.REJECTED, result.outcome)
            assertEquals("invalid timestamp", result.reason)
        }
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    // --- Apply state, deferral and retry ---

    @Test
    fun `process inserts an expense as APPLIED without a state flip`() = runBlocking {
        val stored = slot<EventEntity>()
        coEvery { eventDao.insert(capture(stored)) } returns 1L
        processor.process(makeEvent(), knownGroupKey = groupKey)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, stored.captured.applyState)
        coVerify(exactly = 0) { eventDao.setApplyState(any(), any()) }
    }

    @Test
    fun `process inserts a key_rotation as PENDING and flips it to APPLIED after the effect lands`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns rotationJson()
        val stored = slot<EventEntity>()
        coEvery { eventDao.insert(capture(stored)) } returns 1L
        val event = makeEvent(eventType = "key_rotation", expenseUuid = null)

        val result = processor.process(event, knownGroupKey = groupKey)

        assertEquals(IngestOutcome.APPLIED, result.outcome)
        assertEquals(EventEntity.APPLY_STATE_PENDING, stored.captured.applyState)
        coVerifyOrder {
            eventDao.insert(any())
            postProcessor.handle(
                "key_rotation",
                rotationJson(),
                pubkey,
                groupId,
                event.createdAt,
                false,
                event.id,
                any()
            )
            eventDao.setApplyState(event.id, EventEntity.APPLY_STATE_APPLIED)
        }
    }

    @Test
    fun `process inserts a group_meta as PENDING and flips it after the effect lands`() = runBlocking {
        val meta = """{"name":"Test","created_by":"$pubkey","members":["$pubkey"],"relays":["wss://r"]}"""
        every { encryption.decrypt(any(), groupKey) } returns meta
        val stored = slot<EventEntity>()
        coEvery { eventDao.insert(capture(stored)) } returns 1L
        val event = makeEvent(eventType = "group_meta", expenseUuid = null)

        processor.process(event, knownGroupKey = groupKey)

        assertEquals(EventEntity.APPLY_STATE_PENDING, stored.captured.applyState)
        coVerify(exactly = 1) { eventDao.setApplyState(event.id, EventEntity.APPLY_STATE_APPLIED) }
    }

    @Test
    fun `process threads the event id into the post-processor`() = runBlocking {
        val event = makeEvent(eventType = "group_meta", expenseUuid = null)
        val meta = """{"name":"Test","created_by":"$pubkey","members":["$pubkey"],"relays":["wss://r"]}"""
        every { encryption.decrypt(any(), groupKey) } returns meta
        processor.process(event, knownGroupKey = groupKey)
        coVerify { postProcessor.handle("group_meta", meta, pubkey, groupId, event.createdAt, false, event.id, any()) }
    }

    @Test
    fun `process reports DEFERRED and leaves the row pending when a key_rotation hits an epoch gap`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns rotationJson(epoch = 2)
        coEvery { postProcessor.handle(eq("key_rotation"), any(), any(), any(), any(), any(), any(), any()) } returns
            PostProcessOutcome.DEFERRED
        val event = makeEvent(eventType = "key_rotation", expenseUuid = null)

        val result = processor.process(event, knownGroupKey = groupKey)

        assertTrue("a deferred row is stored", result.stored)
        assertEquals(IngestOutcome.DEFERRED, result.outcome)
        assertEquals(event.id, result.eventId)
        coVerify(exactly = 0) { eventDao.setApplyState(event.id, EventEntity.APPLY_STATE_APPLIED) }
    }

    @Test
    fun `process keeps a failed key_rotation pending and reports it DEFERRED`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns rotationJson()
        coEvery { postProcessor.handle(eq("key_rotation"), any(), any(), any(), any(), any(), any(), any()) } returns
            PostProcessOutcome.FAILED
        val event = makeEvent(eventType = "key_rotation", expenseUuid = null)

        val result = processor.process(event, knownGroupKey = groupKey)

        assertTrue(result.stored)
        assertEquals(IngestOutcome.DEFERRED, result.outcome)
        assertEquals("side effect failed", result.reason)
        coVerify(exactly = 0) { eventDao.setApplyState(event.id, EventEntity.APPLY_STATE_APPLIED) }
    }

    @Test
    fun `failed group_meta side effect keeps the row pending and reports DEFERRED`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns "not-json"
        coEvery { postProcessor.handle(eq("group_meta"), any(), any(), any(), any(), any(), any(), any()) } returns
            PostProcessOutcome.FAILED
        val event = makeEvent(eventType = "group_meta", expenseUuid = null)

        val result = processor.process(event, knownGroupKey = groupKey)

        // A successful mock insert must not make the receipt claim the side effect applied.
        assertTrue(result.stored)
        assertEquals(IngestOutcome.DEFERRED, result.outcome)
        assertEquals("side effect failed", result.reason)
        coVerify(exactly = 0) { eventDao.setApplyState(event.id, EventEntity.APPLY_STATE_APPLIED) }
        coVerify(exactly = 0) { eventDao.setApplyState(event.id, EventEntity.APPLY_STATE_FAILED) }
    }

    @Test
    fun `failed key_revocation side effect keeps the row pending and reports DEFERRED`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns """{"oldPubkey":"$pubkey","newPubkey":""}"""
        coEvery { postProcessor.handle(eq("key_revocation"), any(), any(), any(), any(), any(), any(), any()) } returns
            PostProcessOutcome.FAILED
        val event = makeEvent(eventType = "key_revocation", expenseUuid = null)

        val result = processor.process(event, knownGroupKey = groupKey)

        assertTrue(result.stored)
        assertEquals(IngestOutcome.DEFERRED, result.outcome)
        coVerify(exactly = 0) { eventDao.setApplyState(any(), any()) }
    }

    @Test
    fun `failed side effect on a type without side effects still reports APPLIED`() = runBlocking {
        coEvery { postProcessor.handle(eq("expense"), any(), any(), any(), any(), any(), any(), any()) } returns
            PostProcessOutcome.FAILED
        val event = makeEvent()

        val result = processor.process(event, knownGroupKey = groupKey)

        // Nothing about the ledger depends on a post-processing side effect for an expense.
        assertTrue(result.stored)
        assertEquals(IngestOutcome.APPLIED, result.outcome)
        coVerify(exactly = 0) { eventDao.setApplyState(any(), any()) }
    }

    @Test
    fun `permanently rejected side effect marks the row failed`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns rotationJson()
        coEvery { postProcessor.handle(eq("key_rotation"), any(), any(), any(), any(), any(), any(), any()) } returns
            PostProcessOutcome.REJECTED
        val event = makeEvent(eventType = "key_rotation", expenseUuid = null)

        val result = processor.process(event, knownGroupKey = groupKey)

        assertFalse(result.stored)
        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertEquals("effect rejected", result.reason)
        assertEquals(event.id, result.eventId)
        assertEquals(2, EventEntity.APPLY_STATE_FAILED)
        coVerify(exactly = 1) { eventDao.setApplyState(event.id, EventEntity.APPLY_STATE_FAILED) }
        coVerify(exactly = 0) { eventDao.setApplyState(event.id, EventEntity.APPLY_STATE_APPLIED) }
    }

    @Test
    fun `re-driving a pending duplicate whose effect is permanently rejected marks the row failed`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns rotationJson()
        coEvery { postProcessor.handle(eq("key_rotation"), any(), any(), any(), any(), any(), any(), any()) } returns
            PostProcessOutcome.REJECTED
        val event = makeEvent(eventType = "key_rotation", expenseUuid = null, id = "rot-pending")
        coEvery { eventDao.getEvent("rot-pending") } returns
            storedRow("rot-pending", "key_rotation", EventEntity.APPLY_STATE_PENDING)

        val result = processor.process(event, knownGroupKey = groupKey)

        assertFalse(result.stored)
        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertEquals("effect rejected", result.reason)
        coVerify(exactly = 1) { eventDao.setApplyState("rot-pending", EventEntity.APPLY_STATE_FAILED) }
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `retryDeferred parks a row whose effect is permanently rejected and does not retry it`() = runBlocking {
        useInMemoryEventStore()
        store["rot"] = storedRow("rot", "key_rotation", EventEntity.APPLY_STATE_PENDING)
        every { encryption.decrypt(any(), groupKey) } returns rotationJson()
        coEvery { postProcessor.handle(eq("key_rotation"), any(), any(), any(), any(), any(), any(), any()) } returns
            PostProcessOutcome.REJECTED

        assertEquals(0, processor.retryDeferred(groupId))
        assertEquals(EventEntity.APPLY_STATE_FAILED, store.getValue("rot").applyState)

        // A failed row is no longer pending, so a later pass leaves it alone.
        assertEquals(0, processor.retryDeferred(groupId))
        coVerify(exactly = 1) { postProcessor.handle(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `event for another group delivered with expectedGroupId is rejected before any storage`() = runBlocking {
        val event = makeEvent() // tagged g = groupId

        val result = processor.process(event, knownGroupKey = groupKey, expectedGroupId = "other-group")

        assertFalse(result.stored)
        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertEquals("out of scope", result.reason)
        assertEquals(event.id, result.eventId)
        assertEquals("expense", result.eventType)
        // Rejected before the duplicate check, any DB read, any decryption or any write.
        coVerify(exactly = 0) { eventDao.getEvent(any()) }
        coVerify(exactly = 0) { eventDao.insert(any()) }
        coVerify(exactly = 0) { groupRepo.getById(any()) }
        verify(exactly = 0) { encryption.decrypt(any(), any<String>()) }
        coVerify(exactly = 0) { postProcessor.handle(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { eventValidator.isWithinRateLimit(any()) }
    }

    @Test
    fun `event for the expected group is processed normally`() = runBlocking {
        val result = processor.process(makeEvent(), knownGroupKey = groupKey, expectedGroupId = groupId)
        assertEquals(IngestOutcome.APPLIED, result.outcome)
        assertTrue(result.stored)
    }

    @Test
    fun `process passes the epoch the content decrypted under to the post-processor`() = runBlocking {
        val event = makeEvent(eventType = "group_meta", expenseUuid = null)
        val meta = """{"name":"Old","created_by":"$pubkey","members":["$pubkey"],"relays":["wss://r"]}"""
        coEvery { groupRepo.getById(groupId) } returns group.copy(keyEpoch = 2)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns "old1"
        // Only the epoch-1 key opens it: the meta predates the rotation to epoch 2.
        every { encryption.decrypt(any(), groupKey) } throws RuntimeException("wrong epoch")
        every { encryption.decrypt(any(), "old1") } returns meta
        val stored = slot<EventEntity>()
        coEvery { eventDao.insert(capture(stored)) } returns 1L

        processor.process(event, knownGroupKey = groupKey)

        assertEquals(1, stored.captured.keyEpoch)
        coVerify(exactly = 1) {
            postProcessor.handle("group_meta", meta, pubkey, groupId, event.createdAt, false, event.id, 1)
        }
    }

    @Test
    fun `process re-drives a pending duplicate and flips it to APPLIED`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns rotationJson()
        val event = makeEvent(eventType = "key_rotation", expenseUuid = null, id = "rot-pending")
        coEvery { eventDao.getEvent("rot-pending") } returns
            storedRow("rot-pending", "key_rotation", EventEntity.APPLY_STATE_PENDING)

        val result = processor.process(event, knownGroupKey = groupKey)

        assertEquals(IngestOutcome.APPLIED, result.outcome)
        assertTrue(result.stored)
        assertEquals("Test", result.groupName)
        coVerify(exactly = 0) { eventDao.insert(any()) }
        coVerify {
            postProcessor.handle(
                "key_rotation",
                rotationJson(),
                pubkey,
                groupId,
                1000,
                false,
                "rot-pending",
                any()
            )
        }
        coVerify(exactly = 1) { eventDao.setApplyState("rot-pending", EventEntity.APPLY_STATE_APPLIED) }
        // Re-driving a known row is not new traffic.
        verify(exactly = 0) { eventValidator.isWithinRateLimit(any()) }
    }

    @Test
    fun `process re-driving a pending duplicate that is still blocked stays DEFERRED`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns rotationJson(epoch = 2)
        coEvery { postProcessor.handle(eq("key_rotation"), any(), any(), any(), any(), any(), any(), any()) } returns
            PostProcessOutcome.DEFERRED
        val event = makeEvent(eventType = "key_rotation", expenseUuid = null, id = "rot-pending")
        coEvery { eventDao.getEvent("rot-pending") } returns
            storedRow("rot-pending", "key_rotation", EventEntity.APPLY_STATE_PENDING)

        val result = processor.process(event, knownGroupKey = groupKey)

        assertEquals(IngestOutcome.DEFERRED, result.outcome)
        assertTrue(result.stored)
        coVerify(exactly = 0) { eventDao.setApplyState(any(), any()) }
    }

    @Test
    fun `process re-driving a pending duplicate whose effect fails stays DEFERRED and the row stays pending`() =
        runBlocking {
            every { encryption.decrypt(any(), groupKey) } returns rotationJson()
            coEvery {
                postProcessor.handle(eq("key_rotation"), any(), any(), any(), any(), any(), any(), any())
            } returns
                PostProcessOutcome.FAILED
            val event = makeEvent(eventType = "key_rotation", expenseUuid = null, id = "rot-pending")
            coEvery { eventDao.getEvent("rot-pending") } returns
                storedRow("rot-pending", "key_rotation", EventEntity.APPLY_STATE_PENDING)

            val result = processor.process(event, knownGroupKey = groupKey)

            // The mocked pending row remains retryable; this call must not change its apply state.
            assertEquals(IngestOutcome.DEFERRED, result.outcome)
            assertTrue(result.stored)
            assertEquals("side effect failed", result.reason)
            coVerify(exactly = 0) { eventDao.setApplyState(any(), any()) }
        }

    @Test
    fun `retryDeferred applies a deferred key_rotation once the missing epoch has landed`() = runBlocking {
        useInMemoryEventStore()
        every { encryption.decrypt(any(), groupKey) } returns rotationJson(epoch = 2)
        coEvery {
            postProcessor.handle(eq("key_rotation"), any(), any(), any(), any(), any(), any(), any())
        } returnsMany
            listOf(PostProcessOutcome.DEFERRED, PostProcessOutcome.APPLIED)
        val event = makeEvent(eventType = "key_rotation", expenseUuid = null)

        val first = processor.process(event, knownGroupKey = groupKey)
        assertEquals(IngestOutcome.DEFERRED, first.outcome)
        assertEquals(EventEntity.APPLY_STATE_PENDING, store.getValue(event.id).applyState)

        // ...epoch 1 arrives out of band; the next retry pass succeeds.
        assertEquals(1, processor.retryDeferred(groupId))
        assertEquals(EventEntity.APPLY_STATE_APPLIED, store.getValue(event.id).applyState)
        coVerify(exactly = 2) {
            postProcessor.handle("key_rotation", any(), pubkey, groupId, event.createdAt, false, event.id, any())
        }

        // Nothing left to do, and calling again is harmless.
        assertEquals(0, processor.retryDeferred(groupId))
        coVerify(exactly = 2) { postProcessor.handle(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `retryDeferred returns 0 when nothing is pending`() = runBlocking {
        coEvery { eventDao.getPendingEvents(groupId) } returns emptyList()
        assertEquals(0, processor.retryDeferred(groupId))
        coVerify(exactly = 0) { postProcessor.handle(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `retryDeferred leaves a row pending when its effect is still blocked`() = runBlocking {
        useInMemoryEventStore()
        store["rot"] = storedRow("rot", "key_rotation", EventEntity.APPLY_STATE_PENDING)
        every { encryption.decrypt(any(), groupKey) } returns rotationJson(epoch = 2)
        coEvery { postProcessor.handle(eq("key_rotation"), any(), any(), any(), any(), any(), any(), any()) } returns
            PostProcessOutcome.DEFERRED

        assertEquals(0, processor.retryDeferred(groupId))
        assertEquals(EventEntity.APPLY_STATE_PENDING, store.getValue("rot").applyState)
    }

    @Test
    fun `retryDeferred leaves a row pending when its effect fails`() = runBlocking {
        useInMemoryEventStore()
        store["rot"] = storedRow("rot", "key_rotation", EventEntity.APPLY_STATE_PENDING)
        every { encryption.decrypt(any(), groupKey) } returns rotationJson()
        coEvery { postProcessor.handle(eq("key_rotation"), any(), any(), any(), any(), any(), any(), any()) } returns
            PostProcessOutcome.FAILED

        assertEquals(0, processor.retryDeferred(groupId))
        assertEquals(EventEntity.APPLY_STATE_PENDING, store.getValue("rot").applyState)
    }

    @Test
    fun `retryDeferred applies epoch 2 in the same call once epoch 1 lands, whatever the row order`() = runBlocking {
        useInMemoryEventStore()
        // Rows come back oldest-first; the epoch-2 rotation was (wrongly) stamped earlier than epoch 1.
        store["rot2"] = storedRow("rot2", "key_rotation", EventEntity.APPLY_STATE_PENDING, "enc-2", createdAt = 100)
        store["rot1"] = storedRow("rot1", "key_rotation", EventEntity.APPLY_STATE_PENDING, "enc-1", createdAt = 200)
        every { encryption.decrypt("enc-1", groupKey) } returns rotationJson(epoch = 1)
        every { encryption.decrypt("enc-2", groupKey) } returns rotationJson(epoch = 2)
        // Behave like RotateGroupKeyUseCase: only the next epoch applies.
        var localEpoch = 0
        coEvery { postProcessor.handle(eq("key_rotation"), any(), any(), any(), any(), any(), any(), any()) } answers {
            val epoch = Regex("\"epoch\":(\\d+)").find(secondArg<String>())!!.groupValues[1].toInt()
            if (epoch == localEpoch + 1) {
                localEpoch = epoch
                PostProcessOutcome.APPLIED
            } else {
                PostProcessOutcome.DEFERRED
            }
        }

        assertEquals(2, processor.retryDeferred(groupId))

        assertEquals(2, localEpoch)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, store.getValue("rot1").applyState)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, store.getValue("rot2").applyState)
        assertEquals(0, processor.retryDeferred(groupId))
    }

    @Test
    fun `retryDeferred skips rows whose group is gone`() = runBlocking {
        useInMemoryEventStore()
        store["rot"] = storedRow("rot", "key_rotation", EventEntity.APPLY_STATE_PENDING)
        coEvery { groupRepo.getById(groupId) } returns null

        assertEquals(0, processor.retryDeferred(groupId))
        coVerify(exactly = 0) { postProcessor.handle(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(EventEntity.APPLY_STATE_PENDING, store.getValue("rot").applyState)
    }

    // --- Author-bound expense identity ---

    @Test
    fun `process resolves a correction against the same author's expense only`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        // Bob stored an expense with this uuid; the author of the correction (pubkey) did not.
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, bob) } returns
            EventEntity("orig", groupId, bob, now, 30078, "enc", "expense", "uuid1", "sig", now)
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns null

        val result = realValidatorProcessor().process(
            makeEvent(eventType = "expense_correction", expenseUuid = "uuid1"),
            knownGroupKey = groupKey
        )

        // Bob's expense is not this author's original: the correction waits for the author's own, and
        // is never resolved against Bob's.
        assertEquals(IngestOutcome.DEFERRED, result.outcome)
        assertEquals("missing original", result.reason)
        coVerify { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) }
        coVerify(exactly = 0) { eventDao.getExpenseByAuthor("uuid1", groupId, bob) }
        coVerify { eventDao.insert(match { it.applyState == EventEntity.APPLY_STATE_PENDING }) }
    }

    @Test
    fun `process passes the author-bound original creator to the validator for a delete`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        coEvery { eventDao.getExpenseByAuthor("uuid1", groupId, pubkey) } returns
            EventEntity("orig", groupId, pubkey, now, 30078, "enc", "expense", "uuid1", "sig", now)

        processor.process(makeEvent(eventType = "expense_delete", expenseUuid = "uuid1"), knownGroupKey = groupKey)

        verify { eventValidator.isCorrectionAuthorValid("expense_delete", pubkey, pubkey) }
    }

    @Test
    fun `process only treats an expense as a tombstoned replay if the same author deleted it`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        // Bob deleted *his* uuid1; pubkey's uuid1 is a different expense.
        coEvery { eventDao.getAppliedDeletedExpenseUuidsByAuthor(groupId, bob) } returns listOf("uuid1")
        coEvery { eventDao.getAppliedDeletedExpenseUuidsByAuthor(groupId, pubkey) } returns emptyList()

        val result = realValidatorProcessor().process(makeEvent(expenseUuid = "uuid1"), knownGroupKey = groupKey)

        assertEquals(IngestOutcome.APPLIED, result.outcome)
        coVerify { eventDao.getAppliedDeletedExpenseUuidsByAuthor(groupId, pubkey) }
    }

    @Test
    fun `process rejects a replayed expense the same author deleted`() = runBlocking {
        coEvery { eventDao.getAppliedDeletedExpenseUuidsByAuthor(groupId, pubkey) } returns listOf("uuid1")

        val result = realValidatorProcessor().process(makeEvent(expenseUuid = "uuid1"), knownGroupKey = groupKey)

        assertEquals(IngestOutcome.REJECTED, result.outcome)
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    // --- Historical authors during reconciliation ---

    /** Bob was removed at epoch 1; the group is at epoch 1 with only `pubkey` left. */
    private fun bobRemovedAtEpochOne() {
        coEvery { groupRepo.getById(groupId) } returns group.copy(members = listOf(pubkey), keyEpoch = 1)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns "key-epoch0"
        coEvery { membershipHistory.historicalAuthors(groupId) } returns setOf(pubkey, bob)
        coEvery { membershipHistory.removalEpochOf(groupId, bob) } returns 1
    }

    @Test
    fun `process applies a removed member's expense sealed under a pre-removal epoch in RECONCILIATION`() =
        runBlocking {
            bobRemovedAtEpochOne()
            // Only the epoch-0 key opens it: it predates the removal.
            every { encryption.decrypt(any(), groupKey) } throws RuntimeException("wrong epoch")
            every { encryption.decrypt(any(), "key-epoch0") } returns
                expenseJson(paidBy = bob, splits = listOf(bob to 100L))
            val stored = slot<EventEntity>()
            coEvery { eventDao.insert(capture(stored)) } returns 1L

            val result = realValidatorProcessor().process(
                makeEvent(author = bob),
                knownGroupKey = groupKey,
                context = IngestionContext.RECONCILIATION
            )

            assertEquals(IngestOutcome.APPLIED, result.outcome)
            assertEquals(0, stored.captured.keyEpoch)
            assertEquals(bob, stored.captured.pubkey)
        }

    @Test
    fun `process rejects a removed member's expense in LIVE even under a pre-removal epoch`() = runBlocking {
        bobRemovedAtEpochOne()
        every { encryption.decrypt(any(), groupKey) } throws RuntimeException("wrong epoch")
        every { encryption.decrypt(any(), "key-epoch0") } returns
            expenseJson(paidBy = bob, splits = listOf(bob to 100L))

        val result = realValidatorProcessor().process(makeEvent(author = bob), knownGroupKey = groupKey)

        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertEquals("not a member", result.reason)
        coVerify(exactly = 0) { membershipHistory.historicalAuthors(any()) }
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `process rejects a removed member's expense that only decrypts under the post-removal epoch`() = runBlocking {
        bobRemovedAtEpochOne()
        // Decrypts with the current (epoch 1) key: sealed after Bob was removed, so he should not have had it.
        every { encryption.decrypt(any(), groupKey) } returns expenseJson(paidBy = bob, splits = listOf(bob to 100L))

        val result = realValidatorProcessor().process(
            makeEvent(author = bob),
            knownGroupKey = groupKey,
            context = IngestionContext.RECONCILIATION
        )

        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertEquals("post-removal history", result.reason)
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `process rejects a historical author whose removal epoch is unknown`() = runBlocking {
        bobRemovedAtEpochOne()
        coEvery { membershipHistory.removalEpochOf(groupId, bob) } returns null
        every { encryption.decrypt(any(), groupKey) } throws RuntimeException("wrong epoch")
        every { encryption.decrypt(any(), "key-epoch0") } returns
            expenseJson(paidBy = bob, splits = listOf(bob to 100L))

        val result = realValidatorProcessor().process(
            makeEvent(author = bob),
            knownGroupKey = groupKey,
            context = IngestionContext.RECONCILIATION
        )

        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertEquals("post-removal history", result.reason)
    }

    @Test
    fun `process in RECONCILIATION rejects a non-member who was never in the group`() = runBlocking {
        bobRemovedAtEpochOne()
        val stranger = "cc".repeat(32)

        val result = processor.process(
            makeEvent(author = stranger),
            knownGroupKey = groupKey,
            context = IngestionContext.RECONCILIATION
        )

        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertEquals("not a member", result.reason)
    }

    @Test
    fun `process in RECONCILIATION never admits a former member's control events`() = runBlocking {
        bobRemovedAtEpochOne()
        every { encryption.decrypt(any(), groupKey) } returns """{"oldPubkey":"$bob","newPubkey":""}"""

        val result = processor.process(
            makeEvent(eventType = "key_revocation", author = bob, expenseUuid = null),
            knownGroupKey = groupKey,
            context = IngestionContext.RECONCILIATION
        )

        assertEquals(IngestOutcome.REJECTED, result.outcome)
        coVerify(exactly = 0) { membershipHistory.historicalAuthors(any()) }
    }

    @Test
    fun `process in RECONCILIATION validates participants against current plus historical members`() = runBlocking {
        bobRemovedAtEpochOne()
        // A current member's expense that still splits with the removed Bob.
        every { encryption.decrypt(any(), groupKey) } returns
            expenseJson(amount = 100, splits = listOf(pubkey to 60L, bob to 40L))

        val reconciled = realValidatorProcessor().process(
            makeEvent(),
            knownGroupKey = groupKey,
            context = IngestionContext.RECONCILIATION
        )
        assertEquals(IngestOutcome.APPLIED, reconciled.outcome)

        val live = realValidatorProcessor().process(makeEvent(), knownGroupKey = groupKey)
        assertEquals(IngestOutcome.REJECTED, live.outcome)
        assertEquals("missing participant", live.reason)
        assertTrue(live.retryable)
    }

    @Test
    fun `process in RECONCILIATION loads membership history at most once per event`() = runBlocking {
        bobRemovedAtEpochOne()
        every { encryption.decrypt(any(), groupKey) } throws RuntimeException("wrong epoch")
        every { encryption.decrypt(any(), "key-epoch0") } returns
            expenseJson(paidBy = bob, splits = listOf(bob to 100L))

        processor.process(makeEvent(author = bob), knownGroupKey = groupKey, context = IngestionContext.RECONCILIATION)

        coVerify(exactly = 1) { membershipHistory.historicalAuthors(groupId) }
        verify { eventValidator.isExpenseValid(any(), setOf(pubkey, bob)) }
    }

    @Test
    fun `cached old key cannot masquerade as current epoch`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group.copy(keyEpoch = 1)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns "current-key"
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        every { encryption.decrypt(any(), "current-key") } throws IllegalArgumentException("old ciphertext")
        val event = makeEvent(eventType = "group_meta", expenseUuid = null)
        every { encryption.decrypt(any(), groupKey) } returns
            """{"name":"Old","created_by":"$pubkey","members":["$pubkey"],"relays":[]}"""
        val row = slot<EventEntity>()
        coEvery { eventDao.insert(capture(row)) } returns 1L
        processor.process(event, knownGroupKey = groupKey)
        assertEquals(0, row.captured.keyEpoch)
        coVerify { postProcessor.handle("group_meta", any(), pubkey, groupId, event.createdAt, false, event.id, 0) }
    }

    @Test
    fun `current key is tried after rotation even with a cached old key`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group.copy(keyEpoch = 1)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns "current-key"
        every { encryption.decrypt(any(), "current-key") } returns expenseJson()
        every { encryption.decrypt(any(), groupKey) } throws IllegalArgumentException("stale")
        val row = slot<EventEntity>()
        coEvery { eventDao.insert(capture(row)) } returns 1L
        assertEquals(IngestOutcome.APPLIED, processor.process(makeEvent(), knownGroupKey = groupKey).outcome)
        assertEquals(1, row.captured.keyEpoch)
    }

    @Test
    fun `permanently failed duplicate never reruns its effect`() = runBlocking {
        val event = makeEvent(eventType = "key_rotation", expenseUuid = null, id = "failed")
        coEvery { eventDao.getEvent(event.id) } returns
            storedRow(event.id, "key_rotation", EventEntity.APPLY_STATE_FAILED)
        assertEquals(IngestOutcome.REJECTED, processor.process(event).outcome)
        coVerify(exactly = 0) { postProcessor.handle(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
