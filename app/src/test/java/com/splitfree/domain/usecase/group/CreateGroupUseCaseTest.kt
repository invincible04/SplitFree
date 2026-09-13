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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        every { signer.createSignedCommandEvent(any(), any(), any(), any(), any(), any(), any()) } returns fakeEvent
        every { settings.displayName } returns ""
        coEvery { groupRepo.getById(any()) } returns null
        coEvery { groupRepo.getGroupKeyForEpoch(any(), any()) } returns null
        coEvery { eventPublisher.publishCreatedGroup(any(), any(), any()) } returns true
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
        coVerify {
            eventPublisher.publishCreatedGroup(
                any(),
                match { it.id == GroupIdentity.derive(fakePubkey, it.createdAt) },
                fakeGroupKey
            )
        }
    }

    @Test
    fun `invoke includes creator display name when available`() = runBlocking {
        every { settings.displayName } returns "Alice"
        val group = useCase("Trip to Goa")
        assertEquals(mapOf(fakePubkey to "Alice"), group.memberNames)
        coVerify {
            eventPublisher.publishCreatedGroup(any(), match { it.memberNames[fakePubkey] == "Alice" }, fakeGroupKey)
        }
    }

    @Test
    fun `invoke persists the group row key and creation event as one command`() = runBlocking {
        useCase("Test")
        coVerify(exactly = 1) { eventPublisher.publishCreatedGroup(fakeEvent, any<Group>(), fakeGroupKey) }
        coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any()) }
    }

    @Test
    fun `invoke encrypts and publishes group_meta event addressed under the command`() = runBlocking {
        useCase("Test", commandId = "creation-1")
        verify { encryption.encrypt(any(), fakeGroupKey) }
        verify {
            signer.createSignedCommandEvent(
                any(),
                eq("group_meta"),
                eq("encrypted"),
                isNull(),
                eq("creation-1"),
                any(),
                any()
            )
        }
        coVerify { eventPublisher.publishCreatedGroup(fakeEvent, any(), fakeGroupKey) }
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

    // --- creation as a recoverable command ---

    private fun existingGroup(name: String = "Trip", relays: List<String> = RelayDefaults.DEFAULT_RELAYS) = Group(
        GroupIdentity.derive(fakePubkey, 1000),
        name,
        createdBy = fakePubkey,
        createdAt = 1000,
        members = listOf(fakePubkey),
        relays = relays
    )

    @Test
    fun `an explicit creation time determines the group id`() = runBlocking {
        val group = useCase("Trip", createdAt = 1000, expectedAuthorPubkey = fakePubkey)
        assertEquals(1000L, group.createdAt)
        assertEquals(GroupIdentity.derive(fakePubkey, 1000), group.id)
    }

    @Test
    fun `committed create reconciles without generating or replacing key`() = runBlocking {
        val existing = existingGroup()
        coEvery { groupRepo.getById(existing.id) } returns existing
        coEvery { groupRepo.getGroupKeyForEpoch(existing.id, 0) } returns fakeGroupKey
        coEvery { eventPublisher.hasCreatedGroupCommand(existing.id, fakePubkey, "creation-command") } returns true
        assertEquals(
            existing,
            useCase("Trip", createdAt = 1000, expectedAuthorPubkey = fakePubkey, commandId = "creation-command")
        )
        verify(exactly = 0) { encryption.generateGroupKey() }
        verify(exactly = 0) { encryption.encrypt(any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishCreatedGroup(any(), any(), any()) }
    }

    @Test
    fun `a retry that lost the creation race reconciles against the stored group`() = runBlocking {
        val existing = existingGroup()
        coEvery { groupRepo.getById(existing.id) } returnsMany listOf(null, existing)
        coEvery { eventPublisher.publishCreatedGroup(any(), any(), any()) } returns false
        coEvery { groupRepo.getGroupKeyForEpoch(existing.id, 0) } returnsMany listOf(null, fakeGroupKey)
        coEvery { eventPublisher.hasCreatedGroupCommand(existing.id, fakePubkey, "creation-command") } returns true
        assertEquals(
            existing,
            useCase("Trip", createdAt = 1000, expectedAuthorPubkey = fakePubkey, commandId = "creation-command")
        )
    }

    @Test
    fun `a retry after a rolled back attempt reuses the key it already stored`() = runBlocking {
        val id = GroupIdentity.derive(fakePubkey, 1000)
        coEvery { groupRepo.getGroupKeyForEpoch(id, 0) } returns "orphaned-key"
        useCase("Trip", createdAt = 1000, expectedAuthorPubkey = fakePubkey, commandId = "creation-command")
        verify(exactly = 0) { encryption.generateGroupKey() }
        coVerify { eventPublisher.publishCreatedGroup(any(), match { it.id == id }, "orphaned-key") }
    }

    @Test(expected = IllegalStateException::class)
    fun `same second creation with different details refuses overwrite`() = runBlocking {
        val existing = existingGroup(name = "Original")
        coEvery { groupRepo.getById(existing.id) } returns existing
        coEvery { eventPublisher.hasCreatedGroupCommand(existing.id, fakePubkey, any()) } returns true
        useCase("Different", createdAt = 1000, expectedAuthorPubkey = fakePubkey)
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun `a retry with different relays refuses to claim the stored group`() = runBlocking {
        val existing = existingGroup()
        coEvery { groupRepo.getById(existing.id) } returns existing
        coEvery { eventPublisher.hasCreatedGroupCommand(existing.id, fakePubkey, "creation-command") } returns true
        useCase(
            "Trip",
            listOf("wss://other.relay"),
            createdAt = 1000,
            expectedAuthorPubkey = fakePubkey,
            commandId = "creation-command"
        )
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun `restored create cannot switch identities`() = runBlocking {
        useCase("Trip", createdAt = 1000, expectedAuthorPubkey = "another-author")
        Unit
    }

    @Test
    fun `restored create with another identity writes nothing`() = runBlocking {
        runCatching { useCase("Trip", createdAt = 1000, expectedAuthorPubkey = "another-author") }
        verify(exactly = 0) { encryption.generateGroupKey() }
        coVerify(exactly = 0) { eventPublisher.publishCreatedGroup(any(), any(), any()) }
    }

    @Test(expected = IllegalStateException::class)
    fun `unrelated same second identical create is not mistaken for retry`() = runBlocking {
        val existing = existingGroup()
        coEvery { groupRepo.getById(existing.id) } returns existing
        coEvery { eventPublisher.hasCreatedGroupCommand(existing.id, fakePubkey, "unrelated") } returns false
        useCase("Trip", createdAt = 1000, expectedAuthorPubkey = fakePubkey, commandId = "unrelated")
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun `reconciled group without its key is refused`() = runBlocking {
        val existing = existingGroup()
        coEvery { groupRepo.getById(existing.id) } returns existing
        coEvery { groupRepo.getGroupKeyForEpoch(existing.id, 0) } returns null
        coEvery { eventPublisher.hasCreatedGroupCommand(existing.id, fakePubkey, "creation-command") } returns true
        useCase("Trip", createdAt = 1000, expectedAuthorPubkey = fakePubkey, commandId = "creation-command")
        Unit
    }

    @Test
    fun `distinct commands at distinct seconds create distinct groups`() = runBlocking {
        val first = useCase("Trip", createdAt = 1000, commandId = "one")
        val second = useCase("Trip", createdAt = 1001, commandId = "two")
        assertNotEquals(first.id, second.id)
        coVerify(exactly = 2) { eventPublisher.publishCreatedGroup(any(), any(), fakeGroupKey) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects a blank command id`() = runBlocking {
        useCase("Trip", commandId = " ")
        Unit
    }

    @Test
    fun `currentAuthor exposes the identity a draft pins`() {
        assertEquals(fakePubkey, useCase.currentAuthor())
    }
}
