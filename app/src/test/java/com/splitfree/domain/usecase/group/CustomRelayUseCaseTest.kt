package com.splitfree.domain.usecase.group

import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.domain.util.RelayDefaults
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Offline relay-usecase checks: real crypto, mocked persistence, transport, publisher and self-heal. */
class CustomRelayUseCaseTest {
    private val relayA = "wss://nos.lol"
    private val relayB = "wss://purplerelay.com"
    private val encryption = GroupEncryption(CompressionUtil)
    private val json = Json { ignoreUnknownKeys = true }
    private val privKey = ByteArray(32).also { it[31] = 1 }
    private val pubKey = NostrEvent.pubkeyFromPrivkey(privKey)
    private val identity = mockk<IdentityContract>()
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val client = mockk<NostrClientContract>(relaxed = true)
    private val selfHeal = mockk<SelfHealUseCase>(relaxed = true)
    private val signer = EventSigner(identity)
    private val useCase = UpdateGroupRelaysUseCase(
        groupRepo,
        encryption,
        signer,
        eventPublisher,
        client,
        selfHeal,
        identity
    )
    private val group = Group(
        id = "offline-relay-update",
        name = "UseCaseTest",
        createdBy = pubKey,
        createdAt = 1000L,
        members = listOf(pubKey),
        relays = listOf(relayA),
        memberNames = mapOf(pubKey to "Tester")
    )

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { identity.getPublicKeyHex() } returns pubKey
        every { identity.getPrivateKeyBytes() } answers { privKey.copyOf() }
        coEvery { groupRepo.getById(group.id) } returns group
        coEvery { groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch) } returns encryption.generateGroupKey()
        coEvery {
            groupRepo.applyAuthenticatedMeta(any(), any(), any(), any(), any(), any(), any())
        } returns true
    }

    @After
    fun teardown() {
        privKey.fill(0)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `non-creator cannot change relays or trigger publishing and transfer`() {
        every { identity.getPublicKeyHex() } returns "cc".repeat(32)

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { useCase(group.id, listOf(relayB)) }
        }

        assertTrue(error.message.orEmpty().contains("creator"))
        coVerify(exactly = 0) { eventPublisher.publishDirect(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.connect(any()) }
        coVerify(exactly = 0) { selfHeal(any()) }
        coVerify(exactly = 0) {
            groupRepo.applyAuthenticatedMeta(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `relay probe endpoints belong to the configured defaults`() {
        val allDefaults = RelayDefaults.DEFAULT_RELAYS + RelayDefaults.FALLBACK_RELAYS
        assertTrue("relayA should be a known relay", relayA in allDefaults)
        assertTrue("relayB should be a known relay", relayB in allDefaults)
    }

    @Test
    fun `relay update passes a signed encrypted metadata event to the mocked publisher`() = runBlocking {
        val groupKey = encryption.generateGroupKey()
        coEvery { groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch) } returns groupKey
        val newRelays = listOf(relayA, relayB)
        val published = slot<NostrEvent>()
        val encrypted = slot<String>()

        useCase(group.id, newRelays)

        coVerify(exactly = 1) {
            eventPublisher.publishDirect(capture(published), group.id, capture(encrypted), "group_meta")
        }
        val event = published.captured
        assertTrue("Metadata signature must verify", event.verify())
        assertEquals(pubKey, event.pubkey)
        assertEquals(encrypted.captured, event.content)
        assertEquals(30078, event.kind)
        assertTrue(listOf("g", group.id) in event.tags)
        assertTrue(listOf("t", "group_meta") in event.tags)
        val meta = json.decodeFromString<GroupMeta>(encryption.decrypt(event.content, groupKey))
        assertEquals(
            GroupMeta(
                name = group.name,
                description = group.description,
                createdBy = group.createdBy,
                createdAt = group.createdAt,
                members = group.members,
                relays = newRelays,
                memberNames = group.memberNames
            ),
            meta
        )
        coVerify(exactly = 1) {
            groupRepo.applyAuthenticatedMeta(
                groupId = group.id,
                meta = meta,
                author = pubKey,
                timestamp = event.createdAt,
                eventId = event.id,
                epoch = group.keyEpoch,
                expectedGroup = group
            )
        }
        coVerify(exactly = 1) { client.connect(newRelays) }
        coVerify(exactly = 1) { selfHeal(group.id) }
    }
}
