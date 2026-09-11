package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class UpdateDisplayNameUseCaseTest {
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val identity = mockk<IdentityContract>()
    private val encryption = mockk<GroupEncryption>()
    private val signer = mockk<EventSigner>()
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val settings = mockk<SettingsContract>(relaxed = true)

    private lateinit var useCase: UpdateDisplayNameUseCase

    private val pubkey = "aa".repeat(32)
    private val otherPubkey = "bb".repeat(32)
    private val fakeGroupKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val fakeEvent = NostrEvent(
        id = "evt1",
        pubkey = pubkey,
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
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0

        every { identity.getPublicKeyHex() } returns pubkey
        every { encryption.encrypt(any(), any()) } returns "encrypted"
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns fakeEvent

        useCase = UpdateDisplayNameUseCase(groupRepo, identity, encryption, signer, eventPublisher, settings)
    }

    @After
    fun teardown() = unmockkStatic(android.util.Log::class)

    @Test
    fun `invoke saves name locally and broadcasts to all groups`() = runBlocking {
        val group = Group("g1", "Trip", "", pubkey, 1000, listOf(pubkey), listOf("wss://r"))
        coEvery { groupRepo.getAll() } returns listOf(group)
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 0) } returns fakeGroupKey

        useCase("Alice")

        verify { settings.displayName = "Alice" }
        coVerify {
            groupRepo.updateFromMeta(
                "g1",
                "Trip",
                listOf(pubkey),
                listOf("wss://r"),
                0,
                "",
                match {
                    it[pubkey] ==
                        "Alice"
                }
            )
        }
        coVerify { eventPublisher.publishDirect(any(), "g1", "encrypted", "group_meta") }
    }

    @Test
    fun `invoke broadcasts to multiple groups`() = runBlocking {
        val g1 = Group("g1", "Trip", "", pubkey, 1000, listOf(pubkey), listOf("wss://r"))
        val g2 = Group("g2", "Rent", "", otherPubkey, 2000, listOf(pubkey, otherPubkey), listOf("wss://r2"))
        coEvery { groupRepo.getAll() } returns listOf(g1, g2)
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 0) } returns fakeGroupKey
        coEvery { groupRepo.getGroupKeyForEpoch("g2", 0) } returns fakeGroupKey

        useCase("Alice")

        coVerify { eventPublisher.publishDirect(any(), "g1", any(), "group_meta") }
        coVerify { eventPublisher.publishDirect(any(), "g2", any(), "group_meta") }
    }

    @Test
    fun `invoke skips group when key is missing`() = runBlocking {
        val group = Group("g1", "Trip", "", pubkey, 1000, listOf(pubkey), listOf("wss://r"))
        coEvery { groupRepo.getAll() } returns listOf(group)
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 0) } returns null

        useCase("Alice")

        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any()) }
        // Local update should still happen
        coVerify { groupRepo.updateFromMeta("g1", any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invoke with blank name removes name from map`() = runBlocking {
        val group = Group(
            "g1",
            "Trip",
            "",
            pubkey,
            1000,
            listOf(pubkey),
            listOf("wss://r"),
            memberNames = mapOf(pubkey to "OldName")
        )
        coEvery { groupRepo.getAll() } returns listOf(group)
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 0) } returns fakeGroupKey

        useCase("  ")

        coVerify {
            groupRepo.updateFromMeta(
                "g1",
                "Trip",
                listOf(pubkey),
                listOf("wss://r"),
                0,
                "",
                match { pubkey !in it }
            )
        }
    }

    @Test
    fun `invoke preserves other members names`() = runBlocking {
        val group = Group(
            "g1",
            "Trip",
            "",
            pubkey,
            1000,
            listOf(pubkey, otherPubkey),
            listOf("wss://r"),
            memberNames = mapOf(otherPubkey to "Bob")
        )
        coEvery { groupRepo.getAll() } returns listOf(group)
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 0) } returns fakeGroupKey

        useCase("Alice")

        coVerify {
            groupRepo.updateFromMeta(
                "g1",
                "Trip",
                any(),
                any(),
                0,
                "",
                match { it[pubkey] == "Alice" && it[otherPubkey] == "Bob" }
            )
        }
    }

    @Test
    fun `invoke continues to next group when one fails`() = runBlocking {
        val g1 = Group("g1", "Trip", "", pubkey, 1000, listOf(pubkey), listOf("wss://r"))
        val g2 = Group("g2", "Rent", "", pubkey, 2000, listOf(pubkey), listOf("wss://r2"))
        coEvery { groupRepo.getAll() } returns listOf(g1, g2)
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 0) } returns fakeGroupKey
        coEvery { groupRepo.getGroupKeyForEpoch("g2", 0) } returns fakeGroupKey
        coEvery { eventPublisher.publishDirect(any(), eq("g1"), any(), any()) } throws RuntimeException("network")

        useCase("Alice")

        // g2 should still be published despite g1 failure
        coVerify { eventPublisher.publishDirect(any(), "g2", "encrypted", "group_meta") }
    }

    @Test
    fun `invoke encrypts with the key for the group's current epoch`() = runBlocking {
        val rotated = Group("g1", "Trip", "", pubkey, 1000, listOf(pubkey), listOf("wss://r"), keyEpoch = 2)
        coEvery { groupRepo.getAll() } returns listOf(rotated)
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 2) } returns "epoch2key"

        useCase("Alice")

        verify { encryption.encrypt(any(), "epoch2key") }
        coVerify(exactly = 0) { groupRepo.getGroupKey(any()) }
    }

    @Test
    fun `invoke with no groups is a no-op beyond saving locally`() = runBlocking {
        coEvery { groupRepo.getAll() } returns emptyList()

        useCase("Alice")

        verify { settings.displayName = "Alice" }
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any()) }
    }
}
