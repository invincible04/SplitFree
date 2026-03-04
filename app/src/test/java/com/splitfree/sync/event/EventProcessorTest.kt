package com.splitfree.sync.event

import com.splitfree.data.local.dao.EventDao
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
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
import io.mockk.unmockkStatic
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
    private val groupKey = "key123"
    private val group = Group(groupId, "Test", "", pubkey, 1000, listOf(pubkey), listOf("wss://r"))

    private fun makeEvent(
        eventType: String = "expense",
        author: String = pubkey,
        expenseUuid: String? = "uuid1",
        content: String = "encrypted"
    ) = NostrEvent(
        id = "evt-${System.nanoTime()}",
        pubkey = author,
        createdAt = System.currentTimeMillis() / 1000,
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

    private fun settlementJson(
        id: String = "s1",
        from: String = pubkey,
        to: String = "bb".repeat(32),
        amount: Long = 100,
        currency: String = "USD",
        timestamp: Long = 1000
    ): String =
        """{"id":"$id","from":"$from","to":"$to","amount":$amount,"currency":"$currency","timestamp":$timestamp}"""

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
        every { eventValidator.isNotBackdatedBeforeSettlement(any(), any()) } returns true
        every { eventValidator.isExpenseAmountValid(any(), any()) } returns true

        every { giftWrap.tryUnwrap(any()) } returns null
        every { signer.verify(any()) } returns true
        coEvery { groupRepo.getById(groupId) } returns group
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        every { encryption.decrypt(any(), groupKey) } returns
            """{"id":"uuid1","amount":100,"currency":"USD","description":"test","paid_by":"$pubkey","split_type":"equal","split_among":[{"pubkey":"$pubkey","share":100}],"timestamp":1000}"""
        coEvery { eventDao.insertIfNew(any()) } returns true
        coEvery { eventDao.getDeletedExpenseUuids(any()) } returns emptyList()
        coEvery { eventDao.getLatestEventByType(any(), any()) } returns null
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
    fun `process rejects backdated expense`() = runBlocking {
        every { eventValidator.isNotBackdatedBeforeSettlement(any(), any()) } returns false
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertFalse(result.stored)
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
    fun `process handles decryption failure gracefully`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } throws RuntimeException("bad")
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        // Still stored — decrypted is null but event is kept
        assertTrue(result.stored)
    }

    @Test
    fun `process unwraps gift-wrapped events`() = runBlocking {
        val inner = makeEvent()
        every { giftWrap.tryUnwrap(any()) } returns Pair(inner, "sender")
        // Rumor has sig="" so signer.verify would return false — but we skip it for unwrapped events
        every { signer.verify(any()) } returns false
        val result = processor.process(makeEvent(), knownGroupKey = groupKey)
        assertTrue(result.stored)
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
    fun `process expense_correction checks backdating`() = runBlocking {
        every { eventValidator.isNotBackdatedBeforeSettlement(any(), any()) } returns false
        val result = processor.process(makeEvent(eventType = "expense_correction"), knownGroupKey = groupKey)
        assertFalse(result.stored)
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
    fun `process settlement type skips tombstone and backdating checks`() = runBlocking {
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
    fun `process rejects settlement not authored by participant`() = runBlocking {
        val alice = "aa".repeat(32)
        val bob = "bb".repeat(32)
        every { encryption.decrypt(any(), groupKey) } returns settlementJson(from = alice, to = bob)
        val result = processor.process(
            makeEvent(eventType = "settlement", author = "cc".repeat(32), expenseUuid = null),
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
    fun `process expense with null expenseUuid skips tombstone check`() = runBlocking {
        val result = processor.process(
            makeEvent(eventType = "expense", expenseUuid = null),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
        coVerify(exactly = 0) { eventDao.getDeletedExpenseUuids(any()) }
    }

    @Test
    fun `process expense_correction with null expenseUuid skips original creator lookup`() = runBlocking {
        val result = processor.process(
            makeEvent(
                eventType = "expense_correction",
                expenseUuid = null
            ),
            knownGroupKey = groupKey
        )
        assertTrue(result.stored)
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
    fun `process rejects expense with invalid amount`() = runBlocking {
        every { eventValidator.isExpenseAmountValid(any(), any()) } returns false
        val result = processor.process(makeEvent(eventType = "expense"), knownGroupKey = groupKey)
        assertFalse("Expense with invalid amount must be rejected", result.stored)
    }

    @Test
    fun `process rejects expense_correction with invalid amount`() = runBlocking {
        every { eventValidator.isExpenseAmountValid(any(), any()) } returns false
        val result = processor.process(makeEvent(eventType = "expense_correction"), knownGroupKey = groupKey)
        assertFalse(result.stored)
    }

    @Test
    fun `process rejects expense with unparseable content`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } returns "not-valid-expense-json"
        val result = processor.process(makeEvent(eventType = "expense"), knownGroupKey = groupKey)
        assertFalse("Unparseable expense content must be rejected", result.stored)
    }

    @Test
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }
}
