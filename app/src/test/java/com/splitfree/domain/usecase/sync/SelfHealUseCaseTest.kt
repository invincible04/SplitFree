package com.splitfree.domain.usecase.sync

import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.NostrClientContract
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SelfHealUseCaseTest {
    private val eventRepo = mockk<EventRepositoryContract>()
    private val nostrClient = mockk<NostrClientContract>()
    private val giftWrap = mockk<GiftWrapService>()
    private val identity = mockk<IdentityContract>()
    private val groupRepo = mockk<com.splitfree.domain.repository.GroupRepositoryContract>()

    private lateinit var useCase: SelfHealUseCase
    private val groupId = "group-123"
    private val memberPub = "pub"

    private fun entity(id: String, hasJson: Boolean = true, pubkey: String = memberPub, sig: String = "sig") =
        EventSnapshot(
            eventId = id,
            groupId = groupId,
            pubkey = pubkey,
            createdAt = 1700000000,
            kind = 30078,
            contentEncrypted = "enc",
            eventType = "expense",
            sig = sig,
            receivedAt = 1700000000,
            originalEventJson = if (hasJson) """{"id":"$id"}""" else null
        )

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { nostrClient.isConnected } returns true
        every { giftWrap.enabled } returns false
        every { identity.getPublicKeyHex() } returns memberPub
        coEvery { groupRepo.getById(groupId) } returns
            Group(
                groupId,
                "Test",
                "",
                memberPub,
                1000,
                listOf(memberPub),
                listOf("wss://r")
            )
        useCase = SelfHealUseCase(eventRepo, nostrClient, giftWrap, identity, groupRepo)
    }

    @Test
    fun `returns 0 for empty local events`() = runTest {
        coEvery { eventRepo.getEventsByGroup(groupId) } returns emptyList()
        assertEquals(0, useCase(groupId))
    }

    @Test
    fun `returns 0 when all events already on relay`() = runTest {
        val local = listOf(entity("evt1"), entity("evt2"))
        coEvery { eventRepo.getEventsByGroup(groupId) } returns local
        coEvery { nostrClient.fetchEventIds(groupId, any(), any()) } returns local.map { it.eventId }.toSet()
        assertEquals(0, useCase(groupId))
    }

    @Test
    fun `republishes missing events`() = runTest {
        val local = listOf(entity("evt1"), entity("evt2"))
        coEvery { eventRepo.getEventsByGroup(groupId) } returns local
        coEvery { nostrClient.fetchEventIds(groupId, any(), any()) } returns emptySet()
        coEvery { nostrClient.publishJson(any()) } returns true
        assertEquals(2, useCase(groupId))
    }

    @Test
    fun `skips events without originalEventJson`() = runTest {
        val local = listOf(entity("evt1", hasJson = false))
        coEvery { eventRepo.getEventsByGroup(groupId) } returns local
        coEvery { nostrClient.fetchEventIds(groupId, any(), any()) } returns emptySet()
        assertEquals(0, useCase(groupId))
    }

    @Test
    fun `counts only successful publishes`() = runTest {
        val local = listOf(entity("evt1"), entity("evt2"))
        coEvery { eventRepo.getEventsByGroup(groupId) } returns local
        coEvery { nostrClient.fetchEventIds(groupId, any(), any()) } returns emptySet()
        coEvery { nostrClient.publishJson(match { it.contains("evt1") }) } returns true
        coEvery { nostrClient.publishJson(match { it.contains("evt2") }) } returns false
        assertEquals(1, useCase(groupId))
    }

    @Test
    fun `skips events already on relay`() = runTest {
        val local = listOf(entity("evt1"), entity("evt2"))
        coEvery { eventRepo.getEventsByGroup(groupId) } returns local
        coEvery { nostrClient.fetchEventIds(groupId, any(), any()) } returns setOf("evt1")
        coEvery { nostrClient.publishJson(any()) } returns true
        assertEquals(1, useCase(groupId))
        coVerify(exactly = 1) { nostrClient.publishJson(any()) }
    }

    @Test
    fun `publishes all events in batches instead of capping at 200`() = runTest {
        val local = (1..210).map { entity("evt$it") }
        coEvery { eventRepo.getEventsByGroup(groupId) } returns local
        coEvery { nostrClient.fetchEventIds(groupId, any(), any()) } returns emptySet()
        coEvery { nostrClient.publishJson(any()) } returns true
        val result = useCase(groupId)
        assertEquals(210, result)
    }

    @Test
    fun `caps at ABSOLUTE_CAP for safety`() = runTest {
        val local = (1..SelfHealUseCase.ABSOLUTE_CAP + 50).map { entity("evt$it") }
        coEvery { eventRepo.getEventsByGroup(groupId) } returns local
        coEvery { nostrClient.fetchEventIds(groupId, any(), any()) } returns emptySet()
        coEvery { nostrClient.publishJson(any()) } returns true
        val result = useCase(groupId)
        assertEquals(SelfHealUseCase.ABSOLUTE_CAP, result)
    }

    @Test
    fun `skips events from removed members`() = runTest {
        val removedPub = "removed"
        val local = listOf(entity("evt1"), entity("evt2", pubkey = removedPub))
        coEvery { eventRepo.getEventsByGroup(groupId) } returns local
        coEvery { nostrClient.fetchEventIds(groupId, any(), any()) } returns emptySet()
        coEvery { nostrClient.publishJson(any()) } returns true
        assertEquals(1, useCase(groupId))
    }

    @Test
    fun `returns 0 immediately when gift wrap is enabled`() = runTest {
        every { giftWrap.enabled } returns true
        assertEquals(0, useCase(groupId))
        coVerify(exactly = 0) { eventRepo.getEventsByGroup(any()) }
    }

    @Test
    fun `skips seal-signed rumors and unsigned rows that relays cannot verify`() = runTest {
        val local = listOf(
            entity("signed"),
            entity("sealed", sig = EventSnapshot.SEAL_SIG_PREFIX + "ab".repeat(64)),
            entity("unsigned", sig = "")
        )
        coEvery { eventRepo.getEventsByGroup(groupId) } returns local
        coEvery { nostrClient.fetchEventIds(groupId, any(), any()) } returns emptySet()
        coEvery { nostrClient.publishJson(any()) } returns true

        assertEquals(1, useCase(groupId))
        coVerify(exactly = 1) { nostrClient.publishJson(any()) }
        coVerify(exactly = 1) { nostrClient.publishJson("""{"id":"signed"}""") }
    }

    @Test
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }
}
