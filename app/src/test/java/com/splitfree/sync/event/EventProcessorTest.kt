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
import com.splitfree.sync.event.EventPostProcessor
import com.splitfree.sync.event.EventProcessor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private lateinit var processor: EventProcessor

    private val groupId = "group-123"
    private val pubkey = "ab".repeat(32)
    private val bob = "bb".repeat(32)
    private val groupKey = "key123"
    private val group = Group(groupId, "Test", "", pubkey, 1000, listOf(pubkey), listOf("wss://r"))
    private val twoMemberGroup = group.copy(members = listOf(pubkey, bob))

    private fun makeEvent(
        eventType: String = "expense",
        author: String = pubkey,
        expenseUuid: String? = "uuid1",
        content: String = "encrypted",
        createdAt: Long = System.currentTimeMillis() / 1000
    ) = NostrEvent(
        id = "evt-${System.nanoTime()}",
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

    /** Processor wired to a real [EventValidator] so payload rules are actually exercised. */
    private fun realValidatorProcessor() =
        EventProcessor(eventDao, groupRepo, encryption, signer, identity, giftWrap, EventValidator(), postProcessor)

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0

        // Setup EventValidator mock
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
        every { encryption.decrypt(any(), groupKey) } returns expenseJson()
        coEvery { eventDao.insertIfNew(any()) } returns true
        coEvery { eventDao.getDeletedExpenseUuids(any()) } returns emptyList()
        coEvery { eventDao.getExpenseByUuid(any(), any()) } returns null

        processor =
            EventProcessor(
                eventDao,
                groupRepo,
                encryption,
                signer,
                identity,
                giftWrap,
                eventValidator,
                postProcessor
            )
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
    }

    @Test
    fun `process rejects rate-limited group`() = runBlocking {
        every { eventValidator.isWithinGroupRateLimit(any()) } returns false
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects unsafe content`() = runBlocking {
        every { eventValidator.isContentSafe(any()) } returns false
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects duplicate event`() = runBlocking {
        coEvery { eventDao.insertIfNew(any()) } returns false
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects deleted expense replay`() = runBlocking {
        every { eventValidator.isDeletedExpense(any(), any(), any()) } returns true
        coEvery { eventDao.getDeletedExpenseUuids(any()) } returns listOf("uuid1")
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process accepts expense created before the latest settlement`() = runBlocking {
        // Regression for the removed backdating rule: clock skew between phones must not
        // cause legitimate expenses to be silently dropped on peers.
        val now = System.currentTimeMillis() / 1000
        coEvery { eventDao.getLatestEventByType(groupId, "settlement") } returns
            EventEntity("settle-evt", groupId, pubkey, now, 30078, "enc", "settlement", "s1", "sig", now)
        val result = realValidatorProcessor().process(makeEvent(createdAt = now - 3600), knownGroupKey = groupKey)
        assertTrue("Expense older than latest settlement must be accepted", result.stored)
        coVerify(exactly = 0) { eventDao.getLatestEventByType(any(), eq("settlement")) }
    }

    @Test
    fun `process rejects correction from wrong author`() = runBlocking {
        every {
            eventValidator.isCorrectionAuthorValid(any(), any(), any())
        } returns false
        val result = processor.process(
            makeEvent(eventType = "expense_correction"),
            knownGroupKey = groupKey
        )
        assertFalse(result.stored)
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
        coVerify { postProcessor.handle(eq("group_meta"), eq(meta), eq(pubkey), eq(groupId), any(), eq(false)) }
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
        // joiner adds only themselves — valid self-join
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
    fun `process rejects group_meta self-join that removes members`() = runBlocking {
        val joiner = "cc".repeat(32)
        val otherMember = "dd".repeat(32)
        val creatorGroup = group.copy(createdBy = pubkey, members = listOf(pubkey, otherMember))
        coEvery { groupRepo.getById(groupId) } returns creatorGroup
        // joiner adds themselves but meta omits otherMember — CWE-863 fix rejects
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
        assertFalse("Self-join that removes members must be rejected", result.stored)
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
        coVerify { postProcessor.handle(eq("key_rotation"), any(), eq(pubkey), eq(groupId), any(), any()) }
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
        coVerify { postProcessor.handle(eq("key_revocation"), any(), eq(pubkey), eq(groupId), any(), any()) }
    }

    @Test
    fun `process extracts group ID from tags when not provided`() = runBlocking {
        val result = processor.process(makeEvent(), knownGroupId = null, knownGroupKey = groupKey)
        assertTrue(result.stored)
    }

    @Test
    fun `process returns null group key falls through`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns null
        val result = processor.process(makeEvent(), knownGroupId = groupId, knownGroupKey = null)
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects undecryptable expense instead of storing ciphertext`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } throws RuntimeException("bad")
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse("Undecryptable expense must not be stored", result.stored)
        coVerify(exactly = 0) { eventDao.insertIfNew(any()) }
        coVerify(exactly = 0) { postProcessor.handle(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `process rejects undecryptable settlement instead of storing ciphertext`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } throws RuntimeException("bad")
        val result = processor.process(makeEvent(eventType = "settlement"), knownGroupKey = groupKey)
        assertFalse("Undecryptable settlement must not be stored", result.stored)
        coVerify(exactly = 0) { eventDao.insertIfNew(any()) }
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
        coVerify(exactly = 0) { eventDao.insertIfNew(any()) }
    }

    @Test
    fun `process unwraps gift-wrapped events`() = runBlocking {
        val inner = makeEvent()
        every { giftWrap.tryUnwrap(any()) } returns Nip59.Unwrapped(inner, "sender", "sealsig")
        // Rumor has sig="" so signer.verify would return false — but we skip it for unwrapped events
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
        coEvery { eventDao.insertIfNew(capture(stored)) } returns true

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
        coEvery { eventDao.insertIfNew(capture(stored)) } returns true

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

    // --- Branch coverage additions ---

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
        coVerify { postProcessor.handle(eq("group_meta"), eq(meta), eq(pubkey), eq(groupId), any(), eq(true)) }
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
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any()) }
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
        // Exception handling is now in EventPostProcessor, not EventProcessor.
        // Verify EventProcessor delegates correctly.
        every { encryption.decrypt(any(), groupKey) } returns """{"data":"x"}"""
        val result = processor.process(
            makeEvent(eventType = "key_rotation", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
        coVerify { postProcessor.handle(eq("key_rotation"), any(), eq(pubkey), eq(groupId), any(), any()) }
    }

    @Test
    fun `process key_revocation exception is caught`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns """{"data":"x"}"""
        val result = processor.process(
            makeEvent(eventType = "key_revocation", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
        coVerify { postProcessor.handle(eq("key_revocation"), any(), eq(pubkey), eq(groupId), any(), any()) }
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
        // decrypt fails — can't verify self-join, so reject
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
        every { eventValidator.isCorrectionAuthorValid(any(), any(), any()) } returns false
        val result = processor.process(makeEvent(eventType = "expense_delete"), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process expense_correction accepts events created before the latest settlement`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        coEvery { eventDao.getLatestEventByType(groupId, "settlement") } returns
            EventEntity("settle-evt", groupId, pubkey, now, 30078, "enc", "settlement", "s1", "sig", now)
        coEvery { eventDao.getExpenseByUuid("uuid1", groupId) } returns
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
            makeEvent(eventType = "settlement", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
        coVerify(exactly = 0) { eventDao.getDeletedExpenseUuids(any()) }
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
    fun `process accepts settlement without x tag`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        every { encryption.decrypt(any(), groupKey) } returns settlementJson(id = "s1", from = pubkey, to = bob)
        val result = realValidatorProcessor().process(
            makeEvent(eventType = "settlement", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
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
        coVerify(exactly = 0) { eventDao.insertIfNew(any()) }
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
        coVerify { postProcessor.handle(eq("key_rotation"), any(), eq(pubkey), eq(groupId), any(), any()) }
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
        coVerify { postProcessor.handle(eq("key_revocation"), any(), eq(pubkey), eq(groupId), any(), any()) }
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
            groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any())
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
            coVerify(exactly = 0) { eventDao.getDeletedExpenseUuids(any()) }
            coVerify(exactly = 0) { eventDao.insertIfNew(any()) }
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
            coVerify(exactly = 0) { eventDao.getExpenseByUuid(any(), any()) }
            coVerify(exactly = 0) { eventDao.insertIfNew(any()) }
        }

    @Test
    fun `process expense_delete with null expenseUuid`() = runBlocking {
        val result = processor.process(
            makeEvent(eventType = "expense_delete", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
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
            postProcessor.handle(eq("group_meta"), any(), any(), any(), any(), any())
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
            postProcessor.handle(eq("key_rotation"), any(), any(), any(), any(), any())
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
            postProcessor.handle(eq("key_revocation"), any(), any(), any(), any(), any())
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
    fun `process rejects expense whose shares do not sum to amount`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        every { encryption.decrypt(any(), groupKey) } returns
            expenseJson(amount = 100, splits = listOf(pubkey to 50L, bob to 40L))
        val result = realValidatorProcessor().process(makeEvent(), knownGroupKey = groupKey)
        assertFalse("sum(shares) != amount must be rejected", result.stored)
        coVerify(exactly = 0) { eventDao.insertIfNew(any()) }
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
        coVerify(exactly = 0) { eventDao.insertIfNew(any()) }
    }

    @Test
    fun `process rejects expense_correction whose payload id does not match original uuid`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        coEvery { eventDao.getExpenseByUuid("uuid1", groupId) } returns
            EventEntity("orig", groupId, pubkey, now, 30078, "enc", "expense", "uuid1", "sig", now)
        every { encryption.decrypt(any(), groupKey) } returns expenseJson(id = "uuid-new")
        val result = realValidatorProcessor().process(
            makeEvent(eventType = "expense_correction", expenseUuid = "uuid1"),
            knownGroupKey = groupKey
        )
        assertFalse("Correction payload must keep the original uuid", result.stored)
        coVerify(exactly = 0) { eventDao.insertIfNew(any()) }
    }

    @Test
    fun `process rejects expense_correction whose shares do not sum to amount`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        coEvery { groupRepo.getById(groupId) } returns twoMemberGroup
        coEvery { eventDao.getExpenseByUuid("uuid1", groupId) } returns
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

    @Test
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }
}
