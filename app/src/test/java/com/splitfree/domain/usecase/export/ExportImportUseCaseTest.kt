package com.splitfree.domain.usecase.export

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.export.ExportedEvent
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.validation.EventValidator
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportImportUseCaseTest {
    private val eventRepo = mockk<EventRepositoryContract>(relaxed = true)
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val encryption = mockk<GroupEncryption>()
    private val eventValidator =
        mockk<EventValidator> {
            every { isWithinRateLimit(any()) } returns true
            every { isWithinGroupRateLimit(any()) } returns true
            every { isTimestampValid(any()) } returns true
            every { isTimestampValidLenient(any()) } returns true
            every { isContentSafe(any()) } returns true
            every { isCorrectionAuthorValid(any(), any(), any()) } returns true
            every { isDeletedExpense(any(), any(), any()) } returns false
            every { isNotBackdatedBeforeSettlement(any(), any()) } returns true
        }

    private val json = Json { ignoreUnknownKeys = true }
    private val groupKey =
        java.util.Base64
            .getEncoder()
            .encodeToString(ByteArray(32) { 1 })
    private val groupId = "test-group-id"
    private val memberPrivKey = ByteArray(32) { 1 }
    private val memberPubkey = NostrEvent.pubkeyFromPrivkey(memberPrivKey)
    private val strangerPrivKey = ByteArray(32) { 2 }

    private val sampleEntity =
        EventSnapshot(
            eventId = "evt1",
            groupId = groupId,
            pubkey = memberPubkey,
            createdAt = 1700000000,
            kind = 30078,
            contentEncrypted = "enc",
            eventType = "expense",
            expenseUuid = "uuid1",
            sig = "sig1",
            receivedAt = 1700000000,
            originalEventJson = "{}"
        )

    private val group =
        Group(
            id = groupId,
            name = "Test",
            createdBy = memberPubkey,
            createdAt = 1700000000,
            members = listOf(memberPubkey, "pub2"),
            relays = listOf("wss://relay.test")
        )

    private fun buildSignedExportedEvent(
        privateKey: ByteArray = memberPrivKey,
        eventType: String = "expense",
        expenseUuid: String? = "uuid1",
        contentEncrypted: String = "enc",
        createdAt: Long = 1700000000,
        mutateWrapper: (ExportedEvent) -> ExportedEvent = { it }
    ): ExportedEvent {
        val pubkey = NostrEvent.pubkeyFromPrivkey(privateKey)
        val signedEvent = NostrEvent(
            pubkey = pubkey,
            createdAt = createdAt,
            kind = 30078,
            tags = buildList {
                add(listOf("g", groupId))
                add(listOf("t", eventType))
                if (expenseUuid != null) add(listOf("x", expenseUuid))
            },
            content = contentEncrypted
        ).sign(privateKey)
        val wrapper = ExportedEvent(
            eventId = signedEvent.id,
            pubkey = signedEvent.pubkey,
            createdAt = signedEvent.createdAt,
            kind = signedEvent.kind,
            contentEncrypted = signedEvent.content,
            eventType = eventType,
            expenseUuid = expenseUuid,
            sig = signedEvent.sig,
            originalEventJson = signedEvent.toJson()
        )
        return mutateWrapper(wrapper)
    }

    // --- ExportGroupUseCase ---

    @Test
    fun `export produces valid JSON with HMAC`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventRepo.getEventsByGroup(groupId) } returns listOf(sampleEntity)
        val useCase = ExportGroupUseCase(eventRepo, groupRepo)

        val result = useCase(groupId)
        val export = json.decodeFromString<SplitFreeExport>(result)

        assertEquals(1, export.version)
        assertEquals(groupId, export.groupId)
        assertEquals(1, export.events.size)
        assertEquals("evt1", export.events[0].eventId)
        assertTrue(export.hmac.isNotEmpty())
    }

    @Test
    fun `export with no group key produces empty HMAC`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns null
        coEvery { eventRepo.getEventsByGroup(groupId) } returns listOf(sampleEntity)
        val useCase = ExportGroupUseCase(eventRepo, groupRepo)

        val result = useCase(groupId)
        val export = json.decodeFromString<SplitFreeExport>(result)
        assertEquals("", export.hmac)
    }

    @Test
    fun `export with empty events`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventRepo.getEventsByGroup(groupId) } returns emptyList()
        val useCase = ExportGroupUseCase(eventRepo, groupRepo)

        val result = useCase(groupId)
        val export = json.decodeFromString<SplitFreeExport>(result)
        assertTrue(export.events.isEmpty())
    }

    // --- ImportGroupUseCase ---

    private fun computeHmac(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(
            SecretKeySpec(
                java.util.Base64
                    .getDecoder()
                    .decode(key),
                "HmacSHA256"
            )
        )
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun buildExportJson(events: List<ExportedEvent>, hmac: String = ""): String {
        val serializer = kotlinx.serialization.builtins
            .ListSerializer(ExportedEvent.serializer())
        val eventsJson = Json.encodeToString(serializer, events)
        val h = if (hmac.isEmpty()) computeHmac(eventsJson, groupKey) else hmac
        val export = SplitFreeExport(groupId = groupId, exportedAt = 1700000000, events = events, hmac = h)
        return Json.encodeToString(SplitFreeExport.serializer(), export)
    }

    @Test
    fun `import stores new events and returns count`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        every { encryption.decrypt(any(), any()) } returns "decrypted"
        coEvery { eventRepo.insert(any<EventSnapshot>()) } just Runs

        val events = listOf(buildSignedExportedEvent())
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)
        val count = importUseCase(buildExportJson(events))
        assertEquals(1, count)
    }

    @Test
    fun `import deduplicates existing events`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        val existingEvent = buildSignedExportedEvent()
        coEvery { eventRepo.getEventIds(groupId) } returns listOf(existingEvent.eventId)
        coEvery { groupRepo.getById(groupId) } returns group

        val events = listOf(existingEvent)
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)
        val count = importUseCase(buildExportJson(events))
        assertEquals(0, count)
    }

    @Test
    fun `import skips events from non-members`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group

        val events = listOf(buildSignedExportedEvent(privateKey = strangerPrivKey))
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)
        val count = importUseCase(buildExportJson(events))
        assertEquals(0, count)
    }

    @Test
    fun `import allows group_meta from non-members`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        every { encryption.decrypt(any(), any()) } returns "decrypted"
        coEvery { eventRepo.insert(any<EventSnapshot>()) } just Runs

        // Reset rate limiter to avoid interference
        every { eventValidator.isWithinRateLimit(any()) } returns true

        val events = listOf(
            buildSignedExportedEvent(
                privateKey = strangerPrivKey,
                eventType = "group_meta",
                expenseUuid = null
            )
        )
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)
        val count = importUseCase(buildExportJson(events))
        assertEquals(1, count)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects unsupported version`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        val badJson = """{"version":2,"groupId":"$groupId","exportedAt":0,"events":[],"hmac":"abc"}"""
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)
        importUseCase(badJson)
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun `import rejects unknown group`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns null
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)
        importUseCase(buildExportJson(emptyList()))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects missing HMAC`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        val noHmac = """{"version":1,"groupId":"$groupId","exportedAt":0,"events":[],"hmac":""}"""
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)
        importUseCase(noHmac)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects tampered HMAC`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        val events = listOf(ExportedEvent("evt1", "pub1", 1700000000, 30078, "enc", "expense", "uuid1", "sig1"))
        val serializer = kotlinx.serialization.builtins
            .ListSerializer(ExportedEvent.serializer())
        val eventsJson = Json.encodeToString(serializer, events)
        val badHmac = "ff".repeat(32)
        val export = SplitFreeExport(groupId = groupId, exportedAt = 0, events = events, hmac = badHmac)
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)
        importUseCase(Json.encodeToString(SplitFreeExport.serializer(), export))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects invalid HMAC hex`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        val badJson = """{"version":1,"groupId":"$groupId","exportedAt":0,"events":[],"hmac":"xyz"}"""
        ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)(badJson)
        Unit
    }

    @Test
    fun `import skips event with invalid original JSON signature`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        every { eventValidator.isWithinRateLimit(any()) } returns true

        val events =
            listOf(
                ExportedEvent(
                    "evt1",
                    memberPubkey,
                    1700000000,
                    30078,
                    "enc",
                    "expense",
                    "uuid1",
                    "sig1",
                    originalEventJson =
                    """{"id":"bad","pubkey":"pub1","created_at":1,""" +
                        """"kind":1,"tags":[],"content":"x","sig":"badsig"}"""
                )
            )
        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)(buildExportJson(events))
        assertEquals(0, count)
    }

    @Test
    fun `import skips correction from wrong author`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        coEvery { eventRepo.getExpenseByUuid("uuid1", groupId) } returns sampleEntity.copy(pubkey = "other-pub")
        every { eventValidator.isWithinRateLimit(any()) } returns true
        every {
            eventValidator.isCorrectionAuthorValid(
                "expense_correction",
                memberPubkey,
                "other-pub"
            )
        } returns false

        val events = listOf(
            buildSignedExportedEvent(eventType = "expense_correction", expenseUuid = "uuid1")
        )
        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)(buildExportJson(events))
        assertEquals(0, count)
    }

    @Test
    fun `import handles decryption failure gracefully`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        every { encryption.decrypt(any(), any()) } throws RuntimeException("bad")
        coEvery { eventRepo.insert(any<EventSnapshot>()) } just Runs
        every { eventValidator.isWithinRateLimit(any()) } returns true

        val events = listOf(buildSignedExportedEvent())
        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)(buildExportJson(events))
        assertEquals(1, count) // still imported, decrypted is null
    }

    @Test
    fun `import with null group still imports member events`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns null
        every { encryption.decrypt(any(), any()) } returns "dec"
        coEvery { eventRepo.insert(any<EventSnapshot>()) } just Runs
        every { eventValidator.isWithinRateLimit(any()) } returns true

        val events = listOf(buildSignedExportedEvent())
        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)(buildExportJson(events))
        assertEquals(1, count)
    }

    @Test
    fun `import skips event when originalEventJson is missing`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group

        val unsignedWrapper = ExportedEvent(
            eventId = "evt1",
            pubkey = memberPubkey,
            createdAt = 1700000000,
            kind = 30078,
            contentEncrypted = "enc",
            eventType = "expense",
            expenseUuid = "uuid1",
            sig = "sig",
            originalEventJson = null
        )
        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)(
            buildExportJson(listOf(unsignedWrapper))
        )
        assertEquals(0, count)
    }

    @Test
    fun `import stores parsed signed fields when wrapper fields differ`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        every { encryption.decrypt(any(), any()) } returns "decrypted"
        val inserted = slot<EventSnapshot>()
        coEvery { eventRepo.insert(capture(inserted)) } just Runs

        val event = buildSignedExportedEvent(
            mutateWrapper = {
                it.copy(
                    eventId = "wrapper-id",
                    pubkey = "wrapper-pubkey",
                    createdAt = 123L,
                    kind = 1,
                    contentEncrypted = "wrapper-content",
                    eventType = "group_meta",
                    expenseUuid = "wrapper-uuid",
                    sig = "wrapper-sig"
                )
            }
        )

        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator)(buildExportJson(listOf(event)))
        assertEquals(1, count)
        val parsed = NostrEvent.fromJson(event.originalEventJson!!)!!
        assertEquals(parsed.id, inserted.captured.eventId)
        assertEquals(parsed.pubkey, inserted.captured.pubkey)
        assertEquals(parsed.createdAt, inserted.captured.createdAt)
        assertEquals(parsed.kind, inserted.captured.kind)
        assertEquals(parsed.content, inserted.captured.contentEncrypted)
        assertEquals(parsed.sig, inserted.captured.sig)
        assertEquals("expense", inserted.captured.eventType)
        assertEquals("uuid1", inserted.captured.expenseUuid)
        coVerify(exactly = 1) { eventRepo.insert(any<EventSnapshot>()) }
    }
}
