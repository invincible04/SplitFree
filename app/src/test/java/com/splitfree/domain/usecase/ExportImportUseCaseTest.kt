package com.splitfree.domain.usecase

import com.splitfree.data.local.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventValidator
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.Group
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ExportImportUseCaseTest {

    private val eventDao = mockk<EventDao>(relaxed = true)
    private val groupRepo = mockk<GroupRepository>()
    private val encryption = mockk<GroupEncryption>()

    private val json = Json { ignoreUnknownKeys = true }
    private val groupKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val groupId = "test-group-id"

    private val sampleEntity = EventEntity(
        eventId = "evt1", groupId = groupId, pubkey = "pub1",
        createdAt = 1700000000, kind = 30078, contentEncrypted = "enc",
        eventType = "expense", expenseUuid = "uuid1", sig = "sig1",
        receivedAt = 1700000000, originalEventJson = "{}"
    )

    private val group = Group(
        id = groupId, name = "Test", createdBy = "pub1",
        createdAt = 1700000000, members = listOf("pub1", "pub2"),
        relays = listOf("wss://relay.test")
    )

    // --- ExportGroupUseCase ---

    @Test
    fun `export produces valid JSON with HMAC`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventDao.getEventsByGroup(groupId) } returns listOf(sampleEntity)
        val useCase = ExportGroupUseCase(eventDao, groupRepo)

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
        coEvery { eventDao.getEventsByGroup(groupId) } returns listOf(sampleEntity)
        val useCase = ExportGroupUseCase(eventDao, groupRepo)

        val result = useCase(groupId)
        val export = json.decodeFromString<SplitFreeExport>(result)
        assertEquals("", export.hmac)
    }

    @Test
    fun `export with empty events`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventDao.getEventsByGroup(groupId) } returns emptyList()
        val useCase = ExportGroupUseCase(eventDao, groupRepo)

        val result = useCase(groupId)
        val export = json.decodeFromString<SplitFreeExport>(result)
        assertTrue(export.events.isEmpty())
    }

    // --- ImportGroupUseCase ---

    private fun computeHmac(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(java.util.Base64.getDecoder().decode(key), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun buildExportJson(events: List<ExportedEvent>, hmac: String = ""): String {
        val eventsJson = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ExportedEvent.serializer()), events)
        val h = if (hmac.isEmpty()) computeHmac(eventsJson, groupKey) else hmac
        val export = SplitFreeExport(groupId = groupId, exportedAt = 1700000000, events = events, hmac = h)
        return Json.encodeToString(SplitFreeExport.serializer(), export)
    }

    @Test
    fun `import stores new events and returns count`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        every { encryption.decrypt(any(), any()) } returns "decrypted"
        coEvery { eventDao.insert(any<EventEntity>()) } just Runs

        val events = listOf(ExportedEvent("evt1", "pub1", 1700000000, 30078, "enc", "expense", "uuid1", "sig1"))
        val importUseCase = ImportGroupUseCase(eventDao, groupRepo, encryption)
        val count = importUseCase(buildExportJson(events))
        assertEquals(1, count)
    }

    @Test
    fun `import deduplicates existing events`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventDao.getEventIds(groupId) } returns listOf("evt1")
        coEvery { groupRepo.getById(groupId) } returns group

        val events = listOf(ExportedEvent("evt1", "pub1", 1700000000, 30078, "enc", "expense", "uuid1", "sig1"))
        val importUseCase = ImportGroupUseCase(eventDao, groupRepo, encryption)
        val count = importUseCase(buildExportJson(events))
        assertEquals(0, count)
    }

    @Test
    fun `import skips events from non-members`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group

        val events = listOf(ExportedEvent("evt1", "stranger", 1700000000, 30078, "enc", "expense", "uuid1", "sig1"))
        val importUseCase = ImportGroupUseCase(eventDao, groupRepo, encryption)
        val count = importUseCase(buildExportJson(events))
        assertEquals(0, count)
    }

    @Test
    fun `import allows group_meta from non-members`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        every { encryption.decrypt(any(), any()) } returns "decrypted"
        coEvery { eventDao.insert(any<EventEntity>()) } just Runs

        // Reset rate limiter to avoid interference
        mockkObject(EventValidator)
        every { EventValidator.isWithinRateLimit(any()) } returns true

        val events = listOf(ExportedEvent("evt1", "stranger", 1700000000, 30078, "enc", "group_meta", null, "sig1"))
        val importUseCase = ImportGroupUseCase(eventDao, groupRepo, encryption)
        val count = importUseCase(buildExportJson(events))
        assertEquals(1, count)

        unmockkObject(EventValidator)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects unsupported version`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        val badJson = """{"version":2,"groupId":"$groupId","exportedAt":0,"events":[],"hmac":"abc"}"""
        val importUseCase = ImportGroupUseCase(eventDao, groupRepo, encryption)
        importUseCase(badJson)
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun `import rejects unknown group`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns null
        val importUseCase = ImportGroupUseCase(eventDao, groupRepo, encryption)
        importUseCase(buildExportJson(emptyList()))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects missing HMAC`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        val noHmac = """{"version":1,"groupId":"$groupId","exportedAt":0,"events":[],"hmac":""}"""
        val importUseCase = ImportGroupUseCase(eventDao, groupRepo, encryption)
        importUseCase(noHmac)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects tampered HMAC`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        val events = listOf(ExportedEvent("evt1", "pub1", 1700000000, 30078, "enc", "expense", "uuid1", "sig1"))
        val eventsJson = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ExportedEvent.serializer()), events)
        val badHmac = "ff".repeat(32)
        val export = SplitFreeExport(groupId = groupId, exportedAt = 0, events = events, hmac = badHmac)
        val importUseCase = ImportGroupUseCase(eventDao, groupRepo, encryption)
        importUseCase(Json.encodeToString(SplitFreeExport.serializer(), export))
        Unit
    }
}
