package com.splitfree.domain.usecase

import com.splitfree.data.local.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.IdentityManager
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SelfHealUseCaseTest {

    private val eventDao = mockk<EventDao>()
    private val nostrClient = mockk<NostrClient>()
    private val signer = mockk<EventSigner>()
    private val identity = mockk<IdentityManager>()

    private lateinit var useCase: SelfHealUseCase
    private val groupId = "group-123"

    private fun entity(id: String, hasJson: Boolean = true) = EventEntity(
        eventId = id, groupId = groupId, pubkey = "pub", createdAt = 1700000000,
        kind = 30078, contentEncrypted = "enc", eventType = "expense",
        sig = "sig", receivedAt = 1700000000,
        originalEventJson = if (hasJson) """{"id":"$id"}""" else null
    )

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        useCase = SelfHealUseCase(eventDao, nostrClient, signer, identity)
    }

    @Test
    fun `returns 0 for empty local events`() = runBlocking {
        coEvery { eventDao.getEventsByGroup(groupId) } returns emptyList()
        assertEquals(0, useCase(groupId))
    }

    @Test
    fun `returns 0 when all events already on relay`() = runBlocking {
        val local = listOf(entity("evt1"), entity("evt2"))
        coEvery { eventDao.getEventsByGroup(groupId) } returns local
        coEvery { nostrClient.fetchEvents(groupId, any()) } returns local.map {
            com.splitfree.domain.crypto.NostrEvent(it.eventId, "pub", 1700000000, 30078, emptyList(), "enc", "sig")
        }
        assertEquals(0, useCase(groupId))
    }

    @Test
    fun `republishes missing events`() = runBlocking {
        val local = listOf(entity("evt1"), entity("evt2"))
        coEvery { eventDao.getEventsByGroup(groupId) } returns local
        coEvery { nostrClient.fetchEvents(groupId, any()) } returns emptyList()
        coEvery { nostrClient.publishJson(any()) } returns true
        assertEquals(2, useCase(groupId))
    }

    @Test
    fun `skips events without originalEventJson`() = runBlocking {
        val local = listOf(entity("evt1", hasJson = false))
        coEvery { eventDao.getEventsByGroup(groupId) } returns local
        coEvery { nostrClient.fetchEvents(groupId, any()) } returns emptyList()
        assertEquals(0, useCase(groupId))
    }

    @Test
    fun `counts only successful publishes`() = runBlocking {
        val local = listOf(entity("evt1"), entity("evt2"))
        coEvery { eventDao.getEventsByGroup(groupId) } returns local
        coEvery { nostrClient.fetchEvents(groupId, any()) } returns emptyList()
        coEvery { nostrClient.publishJson(match { it.contains("evt1") }) } returns true
        coEvery { nostrClient.publishJson(match { it.contains("evt2") }) } returns false
        assertEquals(1, useCase(groupId))
    }

    @Test
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }
}
