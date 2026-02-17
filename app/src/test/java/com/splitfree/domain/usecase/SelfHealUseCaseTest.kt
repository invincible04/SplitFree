package com.splitfree.domain.usecase

import com.splitfree.data.local.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.IdentityManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SelfHealUseCaseTest {
    private val eventDao = mockk<EventDao>()
    private val nostrClient = mockk<NostrClient>()
    private val signer = mockk<EventSigner>()
    private val identity = mockk<IdentityManager>()
    private val groupRepo = mockk<com.splitfree.data.repository.GroupRepository>()

    private lateinit var useCase: SelfHealUseCase
    private val groupId = "group-123"
    private val memberPub = "pub"

    private fun entity(
        id: String,
        hasJson: Boolean = true,
        pubkey: String = memberPub,
    ) = EventEntity(
        eventId = id,
        groupId = groupId,
        pubkey = pubkey,
        createdAt = 1700000000,
        kind = 30078,
        contentEncrypted = "enc",
        eventType = "expense",
        sig = "sig",
        receivedAt = 1700000000,
        originalEventJson = if (hasJson) """{"id":"$id"}""" else null,
    )

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { nostrClient.isConnected } returns true
        every { identity.getPublicKeyHex() } returns memberPub
        coEvery { groupRepo.getById(groupId) } returns com.splitfree.domain.model.Group(
            groupId, "Test", "", memberPub, 1000, listOf(memberPub), listOf("wss://r")
        )
        useCase = SelfHealUseCase(eventDao, nostrClient, signer, identity, groupRepo)
    }

    @Test
    fun `returns 0 for empty local events`() =
        runTest {
            coEvery { eventDao.getEventsByGroup(groupId) } returns emptyList()
            assertEquals(0, useCase(groupId))
        }

    @Test
    fun `returns 0 when all events already on relay`() =
        runTest {
            val local = listOf(entity("evt1"), entity("evt2"))
            coEvery { eventDao.getEventsByGroup(groupId) } returns local
            coEvery { nostrClient.fetchEvents(groupId, any(), any()) } returns
                local.map {
                    com.splitfree.domain.crypto
                        .NostrEvent(it.eventId, "pub", 1700000000, 30078, emptyList(), "enc", "sig")
                }
            assertEquals(0, useCase(groupId))
        }

    @Test
    fun `republishes missing events`() =
        runTest {
            val local = listOf(entity("evt1"), entity("evt2"))
            coEvery { eventDao.getEventsByGroup(groupId) } returns local
            coEvery { nostrClient.fetchEvents(groupId, any(), any()) } returns emptyList()
            coEvery { nostrClient.publishJson(any()) } returns true
            assertEquals(2, useCase(groupId))
        }

    @Test
    fun `skips events without originalEventJson`() =
        runTest {
            val local = listOf(entity("evt1", hasJson = false))
            coEvery { eventDao.getEventsByGroup(groupId) } returns local
            coEvery { nostrClient.fetchEvents(groupId, any(), any()) } returns emptyList()
            assertEquals(0, useCase(groupId))
        }

    @Test
    fun `counts only successful publishes`() =
        runTest {
            val local = listOf(entity("evt1"), entity("evt2"))
            coEvery { eventDao.getEventsByGroup(groupId) } returns local
            coEvery { nostrClient.fetchEvents(groupId, any(), any()) } returns emptyList()
            coEvery { nostrClient.publishJson(match { it.contains("evt1") }) } returns true
            coEvery { nostrClient.publishJson(match { it.contains("evt2") }) } returns false
            assertEquals(1, useCase(groupId))
        }

    @Test
    fun `skips events already on relay`() =
        runTest {
            val local = listOf(entity("evt1"), entity("evt2"))
            coEvery { eventDao.getEventsByGroup(groupId) } returns local
            val remoteEvt1 =
                com.splitfree.domain.crypto
                    .NostrEvent("evt1", "pub", 1700000000, 30078, emptyList(), "enc", "sig")
            coEvery { nostrClient.fetchEvents(groupId, any(), any()) } returns listOf(remoteEvt1)
            coEvery { nostrClient.publishJson(any()) } returns true
            assertEquals(1, useCase(groupId))
            coVerify(exactly = 1) { nostrClient.publishJson(any()) }
        }

    @Test
    fun `publishes all events in batches instead of capping at 200`() =
        runTest {
            val local = (1..210).map { entity("evt$it") }
            coEvery { eventDao.getEventsByGroup(groupId) } returns local
            coEvery { nostrClient.fetchEvents(groupId, any(), any()) } returns emptyList()
            coEvery { nostrClient.publishJson(any()) } returns true
            val result = useCase(groupId)
            assertEquals(210, result)
        }

    @Test
    fun `caps at ABSOLUTE_CAP for safety`() =
        runTest {
            val local = (1..SelfHealUseCase.ABSOLUTE_CAP + 50).map { entity("evt$it") }
            coEvery { eventDao.getEventsByGroup(groupId) } returns local
            coEvery { nostrClient.fetchEvents(groupId, any(), any()) } returns emptyList()
            coEvery { nostrClient.publishJson(any()) } returns true
            val result = useCase(groupId)
            assertEquals(SelfHealUseCase.ABSOLUTE_CAP, result)
        }

    @Test
    fun `skips events from removed members`() =
        runTest {
            val removedPub = "removed"
            val local = listOf(entity("evt1"), entity("evt2", pubkey = removedPub))
            coEvery { eventDao.getEventsByGroup(groupId) } returns local
            coEvery { nostrClient.fetchEvents(groupId, any(), any()) } returns emptyList()
            coEvery { nostrClient.publishJson(any()) } returns true
            assertEquals(1, useCase(groupId))
        }

    @Test
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }
}
