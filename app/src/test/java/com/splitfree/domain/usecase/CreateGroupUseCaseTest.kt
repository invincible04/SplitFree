package com.splitfree.domain.usecase

import com.splitfree.data.local.OutboxDao
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.Group
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CreateGroupUseCaseTest {

    private val groupRepo = mockk<GroupRepository>(relaxed = true)
    private val encryption = mockk<GroupEncryption>()
    private val identity = mockk<IdentityManager>()
    private val signer = mockk<EventSigner>()
    private val outboxDao = mockk<OutboxDao>(relaxed = true)
    private val throttler = mockk<EventThrottler>(relaxed = true)

    private lateinit var useCase: CreateGroupUseCase

    private val fakePubkey = "ab".repeat(32)
    private val fakeGroupKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val fakeEvent = NostrEvent(
        id = "evt1", pubkey = fakePubkey, createdAt = 1000L,
        kind = 30078, tags = emptyList(), content = "encrypted", sig = "sig"
    )

    @Before
    fun setup() {
        every { encryption.generateGroupKey() } returns fakeGroupKey
        every { identity.getPublicKeyHex() } returns fakePubkey
        every { encryption.encrypt(any(), any()) } returns "encrypted"
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns fakeEvent
        useCase = CreateGroupUseCase(groupRepo, encryption, identity, signer, outboxDao, throttler)
    }

    @Test
    fun `invoke creates group with correct fields`() = runBlocking {
        val group = useCase("Trip to Goa")
        assertEquals("Trip to Goa", group.name)
        assertEquals(fakePubkey, group.createdBy)
        assertEquals(listOf(fakePubkey), group.members)
        assertEquals(CreateGroupUseCase.DEFAULT_RELAYS, group.relays)
    }

    @Test
    fun `invoke saves group to repository`() = runBlocking {
        useCase("Test")
        coVerify { groupRepo.save(any<Group>(), fakeGroupKey) }
    }

    @Test
    fun `invoke encrypts and publishes group_meta event`() = runBlocking {
        useCase("Test")
        verify { encryption.encrypt(any(), fakeGroupKey) }
        verify { signer.createSignedEvent(any(), eq("group_meta"), eq("encrypted"), isNull()) }
        coVerify { outboxDao.insert(any<OutboxEntity>()) }
        coVerify { throttler.enqueue(fakeEvent) }
    }

    @Test
    fun `invoke with custom relays uses them`() = runBlocking {
        val relays = listOf("wss://custom.relay")
        val group = useCase("Test", relays)
        assertEquals(relays, group.relays)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects blank name`() = runBlocking {
        useCase("   ")
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects name over 100 chars`() = runBlocking {
        useCase("A".repeat(101))
        Unit
    }

    @Test
    fun `invoke accepts name at 100 chars`() = runBlocking {
        val group = useCase("A".repeat(100))
        assertEquals(100, group.name.length)
    }

    @Test
    fun `DEFAULT_RELAYS contains expected relay URLs`() {
        assertTrue(CreateGroupUseCase.DEFAULT_RELAYS.all { it.startsWith("wss://") })
        assertTrue(CreateGroupUseCase.DEFAULT_RELAYS.size >= 3)
    }

    @Test
    fun `MAX_GROUP_MEMBERS is 50`() {
        assertEquals(50, CreateGroupUseCase.MAX_GROUP_MEMBERS)
    }
}
