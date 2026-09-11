package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RevokeKeyUseCaseTest {
    private val identity = mockk<IdentityContract>()
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val encryption = mockk<GroupEncryption>()
    private val signer = mockk<EventSigner>()
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)

    private lateinit var useCase: RevokeKeyUseCase

    private val oldPubkey = "aa".repeat(32)
    private val newPubkey = "bb".repeat(32)
    private val groupId = "group-1"
    private val groupKey = "key1"

    private val group =
        Group(
            groupId,
            "Test",
            "",
            oldPubkey,
            1000,
            listOf(oldPubkey, "cc".repeat(32)),
            listOf("wss://r"),
            memberNames = mapOf(oldPubkey to "Alice", "cc".repeat(32) to "Bob")
        )
    private val fakeEvent = NostrEvent("evt1", oldPubkey, 1000, 30078, emptyList(), "enc", "sig")

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0

        every { identity.getPublicKeyHex() } returns oldPubkey
        every { identity.generatePendingKeyPair() } returns newPubkey
        every { identity.markRevocationStarted() } just Runs
        every { identity.commitPendingKeyPair() } just Runs
        every { identity.discardPendingKeyPair() } just Runs
        every { identity.hasPendingKeyPair() } returns false
        every { identity.setRevocationEventIds(any()) } just Runs
        every { identity.getRevocationEventIds() } returns emptyList()
        every { identity.getRevocationStartTime() } returns 0L
        every { encryption.encrypt(any(), any()) } returns "encrypted"
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns fakeEvent
        coEvery { groupRepo.getAll() } returns listOf(group)
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey

        useCase = RevokeKeyUseCase(identity, groupRepo, encryption, signer, eventPublisher)
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    // --- invoke ---

    @Test
    fun `invoke returns new pubkey`() = runBlocking {
        val result = useCase()
        assertEquals(newPubkey, result)
    }

    @Test
    fun `invoke publishes revocation and meta events per group`() = runBlocking {
        useCase()
        // 2 events per group: key_revocation + group_meta
        verify(exactly = 2) { signer.createSignedEvent(any(), any(), any(), any()) }
        coVerify(atLeast = 2) { eventPublisher.publishDirect(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invoke commits pending key on success`() = runBlocking {
        useCase()
        verify { identity.commitPendingKeyPair() }
        verify(exactly = 0) { identity.discardPendingKeyPair() }
    }

    @Test
    fun `invoke discards pending key on failure`() {
        every { encryption.encrypt(any(), any()) } throws RuntimeException("fail")
        try {
            runBlocking { useCase() }
        } catch (_: RuntimeException) {
        }
        verify { identity.discardPendingKeyPair() }
        verify(exactly = 0) { identity.commitPendingKeyPair() }
    }

    @Test
    fun `invoke updates member list replacing old pubkey`() = runBlocking {
        useCase()
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                "Test",
                match { newPubkey in it && oldPubkey !in it },
                any(),
                0,
                newPubkey,
                any()
            )
        }
    }

    @Test
    fun `invoke remaps display name from old pubkey to new pubkey`() = runBlocking {
        useCase()
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                any(),
                any(),
                any(),
                any(),
                any(),
                match { it[newPubkey] == "Alice" && oldPubkey !in it && it["cc".repeat(32)] == "Bob" }
            )
        }
    }

    @Test
    fun `handleRevocation remaps display name to new pubkey`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, newPubkey, "test"))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                any(),
                any(),
                any(),
                any(),
                any(),
                match { it[newPubkey] == "Alice" && oldPubkey !in it }
            )
        }
    }

    @Test
    fun `handleRevocation removes display name when newPubkey is empty`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, "", "compromised"))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                any(),
                any(),
                any(),
                any(),
                any(),
                match { oldPubkey !in it && it["cc".repeat(32)] == "Bob" }
            )
        }
    }

    @Test
    fun `invoke skips groups without key`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns null
        val result = useCase()
        assertEquals(newPubkey, result)
        verify(exactly = 0) { encryption.encrypt(any(), any()) }
    }

    // --- resumeIfNeeded ---

    @Test
    fun `resumeIfNeeded does nothing without pending key`() = runBlocking {
        every { identity.hasPendingKeyPair() } returns false
        useCase.resumeIfNeeded()
        verify(exactly = 0) { identity.commitPendingKeyPair() }
    }

    @Test
    fun `resumeIfNeeded commits when outbox is empty`() = runBlocking {
        every { identity.hasPendingKeyPair() } returns true
        every { identity.getPendingPublicKeyHex() } returns newPubkey
        every { identity.getRevocationEventIds() } returns listOf("evt1", "evt2")
        every { identity.getRevocationStartTime() } returns System.currentTimeMillis() / 1000
        coEvery { eventPublisher.hasOutboxEventsById(any()) } returns false
        useCase.resumeIfNeeded()
        verify { identity.commitPendingKeyPair() }
    }

    @Test
    fun `resumeIfNeeded waits when outbox has revocation events`() = runBlocking {
        every { identity.hasPendingKeyPair() } returns true
        every { identity.getPendingPublicKeyHex() } returns newPubkey
        every { identity.getRevocationEventIds() } returns listOf("evt1", "evt2")
        every { identity.getRevocationStartTime() } returns System.currentTimeMillis() / 1000
        coEvery { eventPublisher.hasOutboxEventsById(any()) } returns true
        useCase.resumeIfNeeded()
        verify(exactly = 0) { identity.commitPendingKeyPair() }
    }

    // --- handleRevocation ---

    @Test
    fun `handleRevocation replaces old pubkey with new`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, newPubkey, "test"))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify { groupRepo.updateFromMeta(groupId, "Test", match { newPubkey in it }, any(), 0, "", any()) }
    }

    @Test
    fun `handleRevocation rejects if signer does not match oldPubkey`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, newPubkey))
        useCase.handleRevocation(payload, "wrong-signer", groupId)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleRevocation ignores invalid JSON`() = runBlocking {
        useCase.handleRevocation("bad json", oldPubkey, groupId)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleRevocation removes pubkey when newPubkey is empty`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, "", "compromised"))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify { groupRepo.updateFromMeta(groupId, "Test", match { oldPubkey !in it }, any(), 0, "", any()) }
    }

    @Test
    fun `invoke preserves createdBy when revoker is not creator`() = runBlocking {
        val otherCreator = "dd".repeat(32)
        val nonCreatorGroup = group.copy(createdBy = otherCreator)
        coEvery { groupRepo.getAll() } returns listOf(nonCreatorGroup)
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        useCase()
        coVerify { groupRepo.updateFromMeta(groupId, any(), any(), any(), 0, otherCreator, any()) }
    }

    @Test
    fun `resumeIfNeeded returns when getPendingPublicKeyHex is null`() = runBlocking {
        every { identity.hasPendingKeyPair() } returns true
        every { identity.getPendingPublicKeyHex() } returns null
        useCase.resumeIfNeeded()
        verify(exactly = 0) { identity.commitPendingKeyPair() }
    }

    @Test
    fun `resumeIfNeeded waits when outbox has group_meta events`() = runBlocking {
        every { identity.hasPendingKeyPair() } returns true
        every { identity.getPendingPublicKeyHex() } returns newPubkey
        every { identity.getRevocationEventIds() } returns listOf("evt1")
        every { identity.getRevocationStartTime() } returns System.currentTimeMillis() / 1000
        coEvery { eventPublisher.hasOutboxEventsById(any()) } returns true
        useCase.resumeIfNeeded()
        verify(exactly = 0) { identity.commitPendingKeyPair() }
    }

    @Test
    fun `handleRevocation ignores when group not found`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns null
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, newPubkey))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleRevocation ignores when oldPubkey not in members`() = runBlocking {
        val groupNoMember = group.copy(members = listOf("cc".repeat(32)))
        coEvery { groupRepo.getById(groupId) } returns groupNoMember
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, newPubkey))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resumeIfNeeded commits on timeout even with events in outbox`() = runBlocking {
        every { identity.hasPendingKeyPair() } returns true
        every { identity.getPendingPublicKeyHex() } returns newPubkey
        every { identity.getRevocationEventIds() } returns listOf("evt1")
        // Started 25 hours ago
        every { identity.getRevocationStartTime() } returns System.currentTimeMillis() / 1000 - 25 * 3600
        coEvery { eventPublisher.hasOutboxEventsById(any()) } returns true
        useCase.resumeIfNeeded()
        verify { identity.commitPendingKeyPair() }
    }

    @Test
    fun `invoke stores revocation event IDs`() = runBlocking {
        useCase()
        verify { identity.setRevocationEventIds(match { it.isNotEmpty() }) }
    }

    // --- crash windows (R2) ---

    @Test
    fun `invoke marks the revocation started immediately after generating the pending key`() = runBlocking {
        useCase()
        verifyOrder {
            identity.generatePendingKeyPair()
            identity.markRevocationStarted()
        }
        coVerifyOrder {
            identity.markRevocationStarted()
            eventPublisher.publishDirect(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `invoke applies local membership only after every publish and before committing`() = runBlocking {
        useCase()
        coVerifyOrder {
            eventPublisher.publishDirect(any(), groupId, any(), "key_revocation", any())
            eventPublisher.publishDirect(any(), groupId, any(), "group_meta", any())
            groupRepo.updateFromMeta(groupId, any(), any(), any(), any(), any(), any())
            identity.setRevocationEventIds(any())
            identity.commitPendingKeyPair()
        }
    }

    @Test
    fun `invoke failure while publishing leaves local membership untouched and discards the pending key`() {
        coEvery { eventPublisher.publishDirect(any(), any(), any(), any(), any()) } throws RuntimeException("offline")

        val failure = runCatching { runBlocking { useCase() } }.exceptionOrNull()

        assertTrue("expected RuntimeException, got $failure", failure is RuntimeException)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { identity.setRevocationEventIds(any()) }
        verify(exactly = 0) { identity.commitPendingKeyPair() }
        verify { identity.discardPendingKeyPair() }
    }

    @Test
    fun `invoke builds every event before publishing anything`() {
        // Second signer call (the group_meta) fails: nothing at all may have been published.
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns fakeEvent andThenThrows
            IllegalStateException("signer offline")

        runCatching { runBlocking { useCase() } }

        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
        verify { identity.discardPendingKeyPair() }
    }

    @Test
    fun `invoke warns once about groups without a key and still revokes the others`() = runBlocking {
        val keyedGroup = group.copy(id = "group-2")
        coEvery { groupRepo.getAll() } returns listOf(group, keyedGroup)
        coEvery { groupRepo.getGroupKey(groupId) } returns null
        coEvery { groupRepo.getGroupKey("group-2") } returns groupKey

        val result = useCase()

        assertEquals(newPubkey, result)
        coVerify(exactly = 2) { eventPublisher.publishDirect(any(), "group-2", any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), groupId, any(), any(), any()) }
        verify(exactly = 1) { android.util.Log.w(any<String>(), match<String> { "not published" in it }) }
        verify { identity.commitPendingKeyPair() }
    }

    @Test
    fun `resumeIfNeeded discards pending key when no event ids were recorded`() = runBlocking {
        every { identity.hasPendingKeyPair() } returns true
        every { identity.getPendingPublicKeyHex() } returns newPubkey
        every { identity.getRevocationEventIds() } returns emptyList()
        every { identity.getRevocationStartTime() } returns System.currentTimeMillis() / 1000
        useCase.resumeIfNeeded()
        verify { identity.discardPendingKeyPair() }
        verify(exactly = 0) { identity.commitPendingKeyPair() }
        coVerify(exactly = 0) { eventPublisher.hasOutboxEventsById(any()) }
    }

    @Test
    fun `resumeIfNeeded discards pending key when no start time was recorded`() = runBlocking {
        every { identity.hasPendingKeyPair() } returns true
        every { identity.getPendingPublicKeyHex() } returns newPubkey
        every { identity.getRevocationEventIds() } returns listOf("evt1")
        every { identity.getRevocationStartTime() } returns 0L
        coEvery { eventPublisher.hasOutboxEventsById(any()) } returns false
        useCase.resumeIfNeeded()
        verify { identity.discardPendingKeyPair() }
        verify(exactly = 0) { identity.commitPendingKeyPair() }
    }

    // --- handleRevocation validation (R2) ---

    @Test
    fun `handleRevocation rejects a malformed new pubkey`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, "not-a-pubkey"))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleRevocation rejects an uppercase hex new pubkey`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, "BB".repeat(32)))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleRevocation rejects a revocation naming itself as the new key`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, oldPubkey))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleRevocation does not duplicate a new pubkey that already joined`() = runBlocking {
        val alreadyJoined = group.copy(members = listOf(oldPubkey, newPubkey, "cc".repeat(32)))
        coEvery { groupRepo.getById(groupId) } returns alreadyJoined
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, newPubkey))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                "Test",
                listOf(newPubkey, "cc".repeat(32)),
                any(),
                0,
                "",
                any()
            )
        }
    }

    @Test
    fun `handleRevocation keeps the name the new pubkey already announced`() = runBlocking {
        val alreadyJoined = group.copy(
            members = listOf(oldPubkey, newPubkey, "cc".repeat(32)),
            memberNames = mapOf(oldPubkey to "Alice", newPubkey to "Alicia", "cc".repeat(32) to "Bob")
        )
        coEvery { groupRepo.getById(groupId) } returns alreadyJoined
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, newPubkey))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                any(),
                any(),
                any(),
                any(),
                any(),
                match { it[newPubkey] == "Alicia" && oldPubkey !in it && it["cc".repeat(32)] == "Bob" }
            )
        }
    }
}
