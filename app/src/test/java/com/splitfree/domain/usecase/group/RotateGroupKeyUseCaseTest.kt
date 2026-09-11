package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SecureStorageException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RotateGroupKeyUseCaseTest {
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val encryption = mockk<GroupEncryption>()
    private val identity = mockk<IdentityContract>()
    private val signer = mockk<EventSigner>()
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)

    private lateinit var useCase: RotateGroupKeyUseCase

    // Real secp256k1 keys so NIP-44 conversation-key derivation works end to end.
    private val creatorPriv = ByteArray(32).also { it[31] = 1 }
    private val creatorPub = NostrEvent.pubkeyFromPrivkey(creatorPriv)
    private val peerPub = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 2 })
    private val removedPub = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 3 })

    private val groupId = "group-1"
    private val newKey = "new-epoch-key"
    private val group =
        Group(
            groupId,
            "Trip",
            "",
            creatorPub,
            1000,
            listOf(creatorPub, peerPub, removedPub),
            listOf("wss://r"),
            memberNames = mapOf(creatorPub to "Alice", peerPub to "Bob", removedPub to "Mallory"),
            keyEpoch = 0
        )
    private val fakeEvent = NostrEvent("evt1", creatorPub, 1000, 30078, emptyList(), "enc", "sig")

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0

        every { identity.getPublicKeyHex() } returns creatorPub
        every { identity.getPrivateKeyBytes() } answers { creatorPriv.copyOf() }
        every { encryption.generateGroupKey() } returns newKey
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns fakeEvent
        coEvery { groupRepo.getById(groupId) } returns group

        useCase = RotateGroupKeyUseCase(groupRepo, encryption, identity, signer, eventPublisher)
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `persists new epoch key before publishing the first rotation event`() = runBlocking {
        useCase(groupId, removedPub)

        coVerifyOrder {
            groupRepo.saveGroupKeyForEpoch(groupId, 1, newKey)
            eventPublisher.publishDirect(any(), groupId, any(), "key_rotation", any())
        }
    }

    @Test
    fun `publishes one rotation event per remaining member and advances epoch after publishing`() = runBlocking {
        useCase(groupId, removedPub)

        coVerify(exactly = 2) { eventPublisher.publishDirect(any(), groupId, any(), "key_rotation", any()) }
        coVerifyOrder {
            eventPublisher.publishDirect(any(), groupId, any(), "key_rotation", any())
            groupRepo.updateKeyEpoch(groupId, 1)
        }
        coVerify {
            groupRepo.updateFromMeta(
                groupId,
                "Trip",
                match { removedPub !in it && creatorPub in it && peerPub in it },
                listOf("wss://r"),
                any(),
                any(),
                match { removedPub !in it && it[creatorPub] == "Alice" && it[peerPub] == "Bob" }
            )
        }
    }

    @Test
    fun `failed key write aborts before anything is published or the epoch changes`() = runBlocking {
        coEvery { groupRepo.saveGroupKeyForEpoch(groupId, 1, newKey) } throws
            SecureStorageException("keystore unavailable")

        val failure = runCatching { useCase(groupId, removedPub) }.exceptionOrNull()

        assertTrue("expected SecureStorageException, got $failure", failure is SecureStorageException)
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateKeyEpoch(any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rejects rotation by non-creator`() = runBlocking {
        every { identity.getPublicKeyHex() } returns peerPub
        every { identity.getPrivateKeyBytes() } answers { ByteArray(32).also { it[31] = 2 } }

        val failure = runCatching { useCase(groupId, removedPub) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rejects removing self`() = runBlocking {
        val failure = runCatching { useCase(groupId, creatorPub) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
    }

    @Test
    fun `rejects removing non-member`() = runBlocking {
        val outsider = NostrEvent.pubkeyFromPrivkey(ByteArray(32).also { it[31] = 9 })
        val failure = runCatching { useCase(groupId, outsider) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
    }
}
