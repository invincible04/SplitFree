package com.splitfree.sync

import com.splitfree.data.local.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.*
import com.splitfree.domain.model.Group
import com.splitfree.domain.usecase.MigrateGroupUseCase
import com.splitfree.domain.usecase.RevokeKeyUseCase
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EventProcessorTest {
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val groupRepo = mockk<GroupRepository>()
    private val encryption = mockk<GroupEncryption>()
    private val signer = mockk<EventSigner>()
    private val identity = mockk<IdentityManager>()
    private val giftWrap = mockk<GiftWrapService>()
    private val migrateGroup = mockk<MigrateGroupUseCase>(relaxed = true)
    private val revokeKey = mockk<RevokeKeyUseCase>(relaxed = true)

    private lateinit var processor: EventProcessor

    private val groupId = "group-123"
    private val pubkey = "ab".repeat(32)
    private val groupKey = "key123"
    private val group = Group(groupId, "Test", "", pubkey, 1000, listOf(pubkey), listOf("wss://r"))

    private fun makeEvent(
        eventType: String = "expense",
        author: String = pubkey,
        expenseUuid: String? = "uuid1",
        content: String = "encrypted",
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
        sig = "sig",
    )

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0

        // Reset EventValidator rate limiter state
        mockkObject(EventValidator)
        every { EventValidator.isWithinRateLimit(any()) } returns true
        every { EventValidator.isWithinGroupRateLimit(any()) } returns true
        every { EventValidator.isTimestampValid(any()) } returns true
        every { EventValidator.isTimestampValidLenient(any()) } returns true
        every { EventValidator.isContentSafe(any()) } returns true
        every { EventValidator.isCorrectionAuthorValid(any(), any(), any()) } returns true
        every { EventValidator.isDeletedExpense(any(), any(), any()) } returns false
        every { EventValidator.isNotBackdatedBeforeSettlement(any(), any()) } returns true

        every { giftWrap.tryUnwrap(any()) } returns null
        every { signer.verify(any()) } returns true
        coEvery { groupRepo.getById(groupId) } returns group
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        every { encryption.decrypt(any(), groupKey) } returns """{"test":"data"}"""
        coEvery { eventDao.insertIfNew(any()) } returns true
        coEvery { eventDao.getDeletedExpenseUuids(any()) } returns emptyList()
        coEvery { eventDao.getLatestEventByType(any(), any()) } returns null
        coEvery { eventDao.getExpenseByUuid(any()) } returns null

        processor = EventProcessor(eventDao, groupRepo, encryption, signer, identity, giftWrap, migrateGroup, revokeKey)
    }

    @Test
    fun `process stores valid expense event`() =
        runBlocking {
            val result = processor.process(makeEvent(), knownGroupKey = groupKey)
            assertTrue(result.stored)
            assertEquals("expense", result.eventType)
            assertEquals("Test", result.groupName)
        }

    @Test
    fun `process rejects event with invalid signature`() =
        runBlocking {
            every { signer.verify(any()) } returns false
            val result = processor.process(makeEvent(), knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    @Test
    fun `process rejects event with invalid timestamp`() =
        runBlocking {
            every { EventValidator.isTimestampValid(any()) } returns false
            val result = processor.process(makeEvent(), knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    @Test
    fun `process uses lenient timestamp when flag set`() =
        runBlocking {
            every { EventValidator.isTimestampValid(any()) } returns false
            every { EventValidator.isTimestampValidLenient(any()) } returns true
            val result = processor.process(makeEvent(), knownGroupKey = groupKey, lenientTimestamp = true)
            assertTrue(result.stored)
        }

    @Test
    fun `process rejects event for unknown group`() =
        runBlocking {
            coEvery { groupRepo.getById(groupId) } returns null
            val result = processor.process(makeEvent(), knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    @Test
    fun `process rejects event from non-member`() =
        runBlocking {
            val stranger = "cc".repeat(32)
            val result = processor.process(makeEvent(author = stranger), knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    @Test
    fun `process rejects rate-limited author`() =
        runBlocking {
            every { EventValidator.isWithinRateLimit(any()) } returns false
            val result = processor.process(makeEvent(), knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    @Test
    fun `process rejects rate-limited group`() =
        runBlocking {
            every { EventValidator.isWithinGroupRateLimit(any()) } returns false
            val result = processor.process(makeEvent(), knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    @Test
    fun `process rejects unsafe content`() =
        runBlocking {
            every { EventValidator.isContentSafe(any()) } returns false
            val result = processor.process(makeEvent(), knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    @Test
    fun `process rejects duplicate event`() =
        runBlocking {
            coEvery { eventDao.insertIfNew(any()) } returns false
            val result = processor.process(makeEvent(), knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    @Test
    fun `process rejects deleted expense replay`() =
        runBlocking {
            every { EventValidator.isDeletedExpense(any(), any(), any()) } returns true
            coEvery { eventDao.getDeletedExpenseUuids(any()) } returns listOf("uuid1")
            val result = processor.process(makeEvent(), knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    @Test
    fun `process rejects backdated expense`() =
        runBlocking {
            every { EventValidator.isNotBackdatedBeforeSettlement(any(), any()) } returns false
            val result = processor.process(makeEvent(), knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    @Test
    fun `process rejects correction from wrong author`() =
        runBlocking {
            every { EventValidator.isCorrectionAuthorValid(any(), any(), any()) } returns false
            val result = processor.process(makeEvent(eventType = "expense_correction"), knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    @Test
    fun `process handles group_meta side effect`() =
        runBlocking {
            val meta = """{"name":"Updated","description":"","created_by":"$pubkey","created_at":1000,"members":["$pubkey"],"relays":["wss://r"]}"""
            every { encryption.decrypt(any(), groupKey) } returns meta
            val result = processor.process(makeEvent(eventType = "group_meta", expenseUuid = null), knownGroupKey = groupKey)
            assertTrue(result.stored)
            coVerify { groupRepo.updateFromMeta(groupId, "Updated", listOf(pubkey), listOf("wss://r"), any()) }
        }

    @Test
    fun `process rejects group_meta from non-member non-creator`() =
        runBlocking {
            val stranger = "cc".repeat(32)
            val creatorGroup = group.copy(createdBy = pubkey, members = listOf(pubkey))
            coEvery { groupRepo.getById(groupId) } returns creatorGroup
            // stranger is not member, not creator, and decrypt returns meta without stranger as only-added member
            every { encryption.decrypt(any(), groupKey) } returns
                """{"name":"Test","members":["$pubkey","dd${"dd".repeat(31)}"],"relays":["wss://r"]}"""
            val result = processor.process(
                makeEvent(eventType = "group_meta", author = stranger, expenseUuid = null),
                knownGroupKey = groupKey,
            )
            assertFalse(result.stored)
        }

    @Test
    fun `process accepts group_meta self-join announcement`() =
        runBlocking {
            val joiner = "cc".repeat(32)
            val creatorGroup = group.copy(createdBy = pubkey, members = listOf(pubkey))
            coEvery { groupRepo.getById(groupId) } returns creatorGroup
            // joiner adds only themselves — valid self-join
            val meta = """{"name":"Test","description":"","created_by":"$pubkey","created_at":1000,"members":["$pubkey","$joiner"],"relays":["wss://r"]}"""
            every { encryption.decrypt(any(), groupKey) } returns meta
            val result = processor.process(
                makeEvent(eventType = "group_meta", author = joiner, expenseUuid = null),
                knownGroupKey = groupKey,
            )
            assertTrue(result.stored)
        }

    @Test
    fun `process rejects group_meta self-join that removes members`() =
        runBlocking {
            val joiner = "cc".repeat(32)
            val otherMember = "dd".repeat(32)
            val creatorGroup = group.copy(createdBy = pubkey, members = listOf(pubkey, otherMember))
            coEvery { groupRepo.getById(groupId) } returns creatorGroup
            // joiner adds themselves but removes otherMember — invalid
            val meta = """{"name":"Test","description":"","created_by":"$pubkey","created_at":1000,"members":["$pubkey","$joiner"],"relays":["wss://r"]}"""
            every { encryption.decrypt(any(), groupKey) } returns meta
            val result = processor.process(
                makeEvent(eventType = "group_meta", author = joiner, expenseUuid = null),
                knownGroupKey = groupKey,
            )
            assertFalse(result.stored)
        }

    @Test
    fun `process handles group_migrate side effect`() =
        runBlocking {
            every { encryption.decrypt(any(), groupKey) } returns
                """{"newGroupId":"new-id","encryptedKeys":{},"members":["$pubkey"],"removedMember":"removed"}"""
            val result = processor.process(makeEvent(eventType = "group_migrate", expenseUuid = null), knownGroupKey = groupKey)
            assertTrue(result.stored)
            coVerify { migrateGroup.handleMigration(any(), pubkey, groupId) }
        }

    @Test
    fun `process handles key_revocation side effect`() =
        runBlocking {
            every { encryption.decrypt(any(), groupKey) } returns """{"oldPubkey":"$pubkey","newPubkey":"new","reason":"test"}"""
            // key_revocation is a privileged type — allow non-member
            val result = processor.process(makeEvent(eventType = "key_revocation", expenseUuid = null), knownGroupKey = groupKey)
            assertTrue(result.stored)
            coVerify { revokeKey.handleRevocation(any(), pubkey, groupId) }
        }

    @Test
    fun `process extracts group ID from tags when not provided`() =
        runBlocking {
            val result = processor.process(makeEvent(), knownGroupId = null, knownGroupKey = groupKey)
            assertTrue(result.stored)
        }

    @Test
    fun `process returns null group key falls through`() =
        runBlocking {
            coEvery { groupRepo.getGroupKey(groupId) } returns null
            val result = processor.process(makeEvent(), knownGroupId = groupId, knownGroupKey = null)
            assertFalse(result.stored)
        }

    @Test
    fun `process handles decryption failure gracefully`() =
        runBlocking {
            every { encryption.decrypt(any(), groupKey) } throws RuntimeException("bad")
            val result = processor.process(makeEvent(), knownGroupKey = groupKey)
            // Still stored — decrypted is null but event is kept
            assertTrue(result.stored)
        }

    @Test
    fun `process unwraps gift-wrapped events`() =
        runBlocking {
            val inner = makeEvent()
            every { giftWrap.tryUnwrap(any()) } returns Pair(inner, "sender")
            val result = processor.process(makeEvent(), knownGroupKey = groupKey)
            assertTrue(result.stored)
        }

    @Test
    fun `process rejects event without group tag`() =
        runBlocking {
            val noGroupEvent =
                NostrEvent(
                    id = "evt1",
                    pubkey = pubkey,
                    createdAt = System.currentTimeMillis() / 1000,
                    kind = 30078,
                    tags = listOf(listOf("t", "expense")),
                    content = "enc",
                    sig = "sig",
                )
            val result = processor.process(noGroupEvent, knownGroupId = null, knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    // --- Branch coverage additions ---

    @Test
    fun `process with nonCancellable true runs group_meta side effect`() =
        runBlocking {
            val meta = """{"name":"NC","description":"","created_by":"$pubkey","created_at":1000,"members":["$pubkey"],"relays":["wss://r"]}"""
            every { encryption.decrypt(any(), groupKey) } returns meta
            val result =
                processor.process(
                    makeEvent(eventType = "group_meta", expenseUuid = null),
                    knownGroupKey = groupKey,
                    nonCancellable = true,
                )
            assertTrue(result.stored)
            coVerify { groupRepo.updateFromMeta(groupId, "NC", listOf(pubkey), listOf("wss://r"), any()) }
        }

    @Test
    fun `process group_meta with empty members skips updateFromMeta`() =
        runBlocking {
            val meta = """{"name":"Empty","description":"","created_by":"$pubkey","created_at":1000,"members":[],"relays":[]}"""
            every { encryption.decrypt(any(), groupKey) } returns meta
            val result = processor.process(makeEvent(eventType = "group_meta", expenseUuid = null), knownGroupKey = groupKey)
            assertTrue(result.stored)
            coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `process group_meta exception is caught`() =
        runBlocking {
            every { encryption.decrypt(any(), groupKey) } returns "not-json"
            val result = processor.process(makeEvent(eventType = "group_meta", expenseUuid = null), knownGroupKey = groupKey)
            assertTrue(result.stored)
        }

    @Test
    fun `process group_migrate exception is caught`() =
        runBlocking {
            coEvery { migrateGroup.handleMigration(any(), any(), any()) } throws RuntimeException("fail")
            every { encryption.decrypt(any(), groupKey) } returns """{"data":"x"}"""
            val result = processor.process(makeEvent(eventType = "group_migrate", expenseUuid = null), knownGroupKey = groupKey)
            assertTrue(result.stored)
        }

    @Test
    fun `process key_revocation exception is caught`() =
        runBlocking {
            coEvery { revokeKey.handleRevocation(any(), any(), any()) } throws RuntimeException("fail")
            every { encryption.decrypt(any(), groupKey) } returns """{"data":"x"}"""
            val result = processor.process(makeEvent(eventType = "key_revocation", expenseUuid = null), knownGroupKey = groupKey)
            assertTrue(result.stored)
        }

    @Test
    fun `process group_meta from non-member is rejected`() =
        runBlocking {
            val stranger = "cc".repeat(32)
            val groupWithCreator = group.copy(createdBy = pubkey, members = listOf(pubkey))
            coEvery { groupRepo.getById(groupId) } returns groupWithCreator
            // decrypt fails — can't verify self-join, so reject
            every { encryption.decrypt(any(), any<String>()) } throws RuntimeException("decrypt failed")
            val result =
                processor.process(
                    makeEvent(eventType = "group_meta", author = stranger, expenseUuid = null),
                    knownGroupKey = groupKey,
                )
            assertFalse(result.stored)
        }

    @Test
    fun `process expense_delete checks correction author`() =
        runBlocking {
            every { EventValidator.isCorrectionAuthorValid(any(), any(), any()) } returns false
            val result = processor.process(makeEvent(eventType = "expense_delete"), knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    @Test
    fun `process expense_correction checks backdating`() =
        runBlocking {
            every { EventValidator.isNotBackdatedBeforeSettlement(any(), any()) } returns false
            val result = processor.process(makeEvent(eventType = "expense_correction"), knownGroupKey = groupKey)
            assertFalse(result.stored)
        }

    @Test
    fun `process ignores tags with size less than 2`() =
        runBlocking {
            val event =
                NostrEvent(
                    id = "evt1",
                    pubkey = pubkey,
                    createdAt = System.currentTimeMillis() / 1000,
                    kind = 30078,
                    tags = listOf(listOf("g", groupId), listOf("t", "expense"), listOf("x"), listOf("x", "uuid1")),
                    content = "enc",
                    sig = "sig",
                )
            val result = processor.process(event, knownGroupKey = groupKey)
            assertTrue(result.stored)
        }

    @Test
    fun `process settlement type skips tombstone and backdating checks`() =
        runBlocking {
            val result = processor.process(makeEvent(eventType = "settlement", expenseUuid = null), knownGroupKey = groupKey)
            assertTrue(result.stored)
            coVerify(exactly = 0) { eventDao.getDeletedExpenseUuids(any()) }
        }

    @Test
    fun `process with nonCancellable true runs group_migrate`() =
        runBlocking {
            every { encryption.decrypt(any(), groupKey) } returns """{"data":"x"}"""
            val result =
                processor.process(
                    makeEvent(eventType = "group_migrate", expenseUuid = null),
                    knownGroupKey = groupKey,
                    nonCancellable = true,
                )
            assertTrue(result.stored)
            coVerify { migrateGroup.handleMigration(any(), pubkey, groupId) }
        }

    @Test
    fun `process with nonCancellable true runs key_revocation`() =
        runBlocking {
            every { encryption.decrypt(any(), groupKey) } returns """{"data":"x"}"""
            val result =
                processor.process(
                    makeEvent(eventType = "key_revocation", expenseUuid = null),
                    knownGroupKey = groupKey,
                    nonCancellable = true,
                )
            assertTrue(result.stored)
            coVerify { revokeKey.handleRevocation(any(), pubkey, groupId) }
        }

    @Test
    fun `process decrypted null skips side effects`() =
        runBlocking {
            every { encryption.decrypt(any(), groupKey) } throws RuntimeException("bad")
            val result = processor.process(makeEvent(eventType = "group_meta", expenseUuid = null), knownGroupKey = groupKey)
            assertTrue(result.stored)
            coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `process expense with null expenseUuid skips tombstone check`() =
        runBlocking {
            val result = processor.process(makeEvent(eventType = "expense", expenseUuid = null), knownGroupKey = groupKey)
            assertTrue(result.stored)
            coVerify(exactly = 0) { eventDao.getDeletedExpenseUuids(any()) }
        }

    @Test
    fun `process expense_correction with null expenseUuid skips original creator lookup`() =
        runBlocking {
            val result = processor.process(makeEvent(eventType = "expense_correction", expenseUuid = null), knownGroupKey = groupKey)
            assertTrue(result.stored)
        }

    @Test
    fun `process expense_delete with null expenseUuid`() =
        runBlocking {
            val result = processor.process(makeEvent(eventType = "expense_delete", expenseUuid = null), knownGroupKey = groupKey)
            assertTrue(result.stored)
        }

    @Test
    fun `process event with unknown tag key`() =
        runBlocking {
            val event =
                NostrEvent(
                    id = "evt1",
                    pubkey = pubkey,
                    createdAt = System.currentTimeMillis() / 1000,
                    kind = 30078,
                    tags = listOf(listOf("g", groupId), listOf("t", "expense"), listOf("z", "unknown"), listOf("x", "uuid1")),
                    content = "enc",
                    sig = "sig",
                )
            val result = processor.process(event, knownGroupKey = groupKey)
            assertTrue(result.stored)
        }

    @Test
    fun `process group_meta CancellationException is rethrown`() {
        coEvery { groupRepo.updateFromMeta(any(), any(), any(), any(), any()) } throws kotlinx.coroutines.CancellationException("cancel")
        val meta = """{"name":"X","description":"","created_by":"$pubkey","created_at":1000,"members":["$pubkey"],"relays":["wss://r"]}"""
        every { encryption.decrypt(any(), groupKey) } returns meta
        try {
            runBlocking { processor.process(makeEvent(eventType = "group_meta", expenseUuid = null), knownGroupKey = groupKey) }
            fail("Expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }

    @Test
    fun `process group_migrate CancellationException is rethrown`() {
        coEvery { migrateGroup.handleMigration(any(), any(), any()) } throws kotlinx.coroutines.CancellationException("cancel")
        every { encryption.decrypt(any(), groupKey) } returns """{"data":"x"}"""
        try {
            runBlocking { processor.process(makeEvent(eventType = "group_migrate", expenseUuid = null), knownGroupKey = groupKey) }
            fail("Expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }

    @Test
    fun `process key_revocation CancellationException is rethrown`() {
        coEvery { revokeKey.handleRevocation(any(), any(), any()) } throws kotlinx.coroutines.CancellationException("cancel")
        every { encryption.decrypt(any(), groupKey) } returns """{"data":"x"}"""
        try {
            runBlocking { processor.process(makeEvent(eventType = "key_revocation", expenseUuid = null), knownGroupKey = groupKey) }
            fail("Expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }

    @Test
    fun teardown() {
        unmockkObject(EventValidator)
        unmockkStatic(android.util.Log::class)
    }
}
