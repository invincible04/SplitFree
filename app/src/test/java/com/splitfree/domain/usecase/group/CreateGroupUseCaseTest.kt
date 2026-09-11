package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.util.RelayDefaults
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CreateGroupUseCaseTest {
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val encryption = mockk<GroupEncryption>()
    private val identity = mockk<IdentityContract>()
    private val signer = mockk<EventSigner>()
    private val settings = mockk<SettingsContract>(relaxed = true)
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)

    private lateinit var useCase: CreateGroupUseCase

    private val fakePubkey = "ab".repeat(32)
    private val fakeGroupKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val fakeEvent =
        NostrEvent(
            id = "evt1",
            pubkey = fakePubkey,
            createdAt = 1000L,
            kind = 30078,
            tags = emptyList(),
            content = "encrypted",
            sig = "sig"
        )

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        every { encryption.generateGroupKey() } returns fakeGroupKey
        every { identity.getPublicKeyHex() } returns fakePubkey
        every { encryption.encrypt(any(), any()) } returns "encrypted"
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns fakeEvent
        every { settings.displayName } returns ""
        useCase =
            CreateGroupUseCase(groupRepo, encryption, identity, signer, eventPublisher, settings)
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `invoke creates group with correct fields`() = runBlocking {
        val group = useCase("Trip to Goa")
        assertEquals("Trip to Goa", group.name)
        assertEquals(fakePubkey, group.createdBy)
        assertEquals(listOf(fakePubkey), group.members)
        assertEquals(RelayDefaults.DEFAULT_RELAYS, group.relays)
    }

    @Test
    fun `invoke derives group id from creator and createdAt`() = runBlocking {
        val before = System.currentTimeMillis() / 1000
        val group = useCase("Trip to Goa")
        val after = System.currentTimeMillis() / 1000

        assertTrue(group.createdAt in before..after)
        assertEquals(GroupIdentity.derive(fakePubkey, group.createdAt), group.id)
        assertTrue(GroupIdentity.matches(group.id, fakePubkey, group.createdAt))
        coVerify { groupRepo.save(match { it.id == GroupIdentity.derive(fakePubkey, it.createdAt) }, fakeGroupKey) }
    }

    @Test
    fun `invoke includes creator display name when available`() = runBlocking {
        every { settings.displayName } returns "Alice"
        val group = useCase("Trip to Goa")
        assertEquals(mapOf(fakePubkey to "Alice"), group.memberNames)
        coVerify { groupRepo.save(match { it.memberNames[fakePubkey] == "Alice" }, fakeGroupKey) }
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
        coVerify { eventPublisher.publishDirect(any(), any(), any(), any()) }
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
        assertTrue(RelayDefaults.DEFAULT_RELAYS.all { it.startsWith("wss://") })
        assertTrue(RelayDefaults.DEFAULT_RELAYS.size >= 3)
    }

    @Test
    fun `MAX_GROUP_MEMBERS is 50`() {
        assertEquals(50, RelayDefaults.MAX_GROUP_MEMBERS)
    }
}
