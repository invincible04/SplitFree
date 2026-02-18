package com.splitfree.domain.usecase.group

import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.sync.event.EventPublisher
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RevokeKeyUseCaseTest {
    private val identity = mockk<IdentityManager>()
    private val groupRepo = mockk<GroupRepository>(relaxed = true)
    private val encryption = mockk<GroupEncryption>()
    private val signer = mockk<EventSigner>()
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val eventPublisher = mockk<EventPublisher>(relaxed = true)

    private lateinit var useCase: RevokeKeyUseCase

    private val oldPubkey = "aa".repeat(32)
    private val newPubkey = "bb".repeat(32)
    private val groupId = "group-1"
    private val groupKey = "key1"

    private val group =
        Group(groupId, "Test", "", oldPubkey, 1000, listOf(oldPubkey, "cc".repeat(32)), listOf("wss://r"))
    private val fakeEvent = NostrEvent("evt1", oldPubkey, 1000, 30078, emptyList(), "enc", "sig")

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0

        every { identity.getPublicKeyHex() } returns oldPubkey
        every { identity.generatePendingKeyPair() } returns Pair("privhex", newPubkey)
        every { identity.commitPendingKeyPair() } just Runs
        every { identity.discardPendingKeyPair() } just Runs
        every { identity.hasPendingKeyPair() } returns false
        every { encryption.encrypt(any(), any()) } returns "encrypted"
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns fakeEvent
        coEvery { groupRepo.getAll() } returns listOf(group)
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey

        useCase = RevokeKeyUseCase(identity, groupRepo, encryption, signer, eventDao, eventPublisher)
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
        coVerify { groupRepo.updateFromMeta(groupId, "Test", match { newPubkey in it && oldPubkey !in it }, any()) }
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
        coEvery { eventPublisher.hasOutboxMatching(any()) } returns false
        useCase.resumeIfNeeded()
        verify { identity.commitPendingKeyPair() }
    }

    @Test
    fun `resumeIfNeeded waits when outbox has revocation events`() = runBlocking {
        every { identity.hasPendingKeyPair() } returns true
        every { identity.getPendingPublicKeyHex() } returns newPubkey
        coEvery { eventPublisher.hasOutboxMatching(any()) } returns true
        useCase.resumeIfNeeded()
        verify(exactly = 0) { identity.commitPendingKeyPair() }
    }

    // --- handleRevocation ---

    @Test
    fun `handleRevocation replaces old pubkey with new`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, newPubkey, "test"))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify { groupRepo.updateFromMeta(groupId, "Test", match { newPubkey in it }, any()) }
    }

    @Test
    fun `handleRevocation rejects if signer does not match oldPubkey`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, newPubkey))
        useCase.handleRevocation(payload, "wrong-signer", groupId)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any()) }
    }

    @Test
    fun `handleRevocation ignores invalid JSON`() = runBlocking {
        useCase.handleRevocation("bad json", oldPubkey, groupId)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any()) }
    }

    @Test
    fun `handleRevocation removes pubkey when newPubkey is empty`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, "", "compromised"))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify { groupRepo.updateFromMeta(groupId, "Test", match { oldPubkey !in it }, any()) }
    }

    @Test
    fun `invoke preserves createdBy when revoker is not creator`() = runBlocking {
        val otherCreator = "dd".repeat(32)
        val nonCreatorGroup = group.copy(createdBy = otherCreator)
        coEvery { groupRepo.getAll() } returns listOf(nonCreatorGroup)
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        useCase()
        // createdBy should remain otherCreator, not be replaced
        coVerify { groupRepo.updateFromMeta(groupId, any(), any(), any()) }
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
        coEvery { eventPublisher.hasOutboxMatching(any()) } returns true
        useCase.resumeIfNeeded()
        verify(exactly = 0) { identity.commitPendingKeyPair() }
    }

    @Test
    fun `handleRevocation ignores when group not found`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns null
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, newPubkey))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any()) }
    }

    @Test
    fun `handleRevocation ignores when oldPubkey not in members`() = runBlocking {
        val groupNoMember = group.copy(members = listOf("cc".repeat(32)))
        coEvery { groupRepo.getById(groupId) } returns groupNoMember
        val payload = Json.encodeToString(KeyRevocation.serializer(), KeyRevocation(oldPubkey, newPubkey))
        useCase.handleRevocation(payload, oldPubkey, groupId)
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any()) }
    }
}
