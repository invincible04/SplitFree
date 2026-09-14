package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.domain.util.RelayDefaults
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateGroupRelaysUseCaseTest {
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val encryption = mockk<GroupEncryption>()
    private val signer = mockk<EventSigner>()
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val nostrClient = mockk<NostrClientContract>(relaxed = true)
    private val selfHeal = mockk<SelfHealUseCase>(relaxed = true)
    private val identity = mockk<com.splitfree.domain.repository.IdentityContract>()

    private lateinit var useCase: UpdateGroupRelaysUseCase

    private val pubkey = "aa".repeat(32)
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
    private val group = Group(
        id = "g1",
        name = "Trip",
        createdBy = pubkey,
        createdAt = 1000L,
        members = listOf(pubkey),
        relays = listOf("wss://old.relay"),
        memberNames = mapOf(pubkey to "Alice")
    )

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0

        every { encryption.encrypt(any(), any()) } returns "encrypted"
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns fakeEvent
        every { identity.getPublicKeyHex() } returns pubkey

        coEvery { groupRepo.getById("g1") } returns group
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 0) } returns fakeGroupKey
        coEvery {
            groupRepo.updateFromMeta(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns true

        useCase =
            UpdateGroupRelaysUseCase(
                groupRepo,
                encryption,
                signer,
                eventPublisher,
                nostrClient,
                selfHeal,
                identity
            )
    }

    @After
    fun teardown() = unmockkStatic(android.util.Log::class)

    @Test
    fun `invoke updates relays locally and publishes group_meta`() = runBlocking {
        val newRelays = listOf("wss://new.relay", "wss://another.relay")

        useCase("g1", newRelays)

        // The local write is ordered by the published event's own (created_at, id) clock, with every
        // other field carried over from the stored group so the LWW update changes only the relays.
        coVerify {
            groupRepo.updateFromMeta(
                groupId = "g1",
                name = "Trip",
                members = listOf(pubkey),
                relays = newRelays,
                eventTimestamp = 1000,
                createdBy = pubkey,
                memberNames = match { it[pubkey] == "Alice" },
                description = "",
                eventId = "evt1"
            )
        }
        verify { encryption.encrypt(match { it.contains("new.relay") && it.contains("another.relay") }, fakeGroupKey) }
        coVerify { eventPublisher.publishDirect(fakeEvent, "g1", "encrypted", "group_meta") }
    }

    @Test
    fun `invoke builds and signs the group_meta before writing locally`() = runBlocking {
        useCase("g1", listOf("wss://x.relay"))

        coVerifyOrder {
            signer.createSignedEvent("g1", "group_meta", "encrypted", null)
            groupRepo.updateFromMeta(any(), any(), any(), any(), 1000, any(), any(), any(), "evt1", any())
            eventPublisher.publishDirect(fakeEvent, "g1", "encrypted", "group_meta")
        }
    }

    @Test
    fun `invoke preserves group name members creator description and memberNames`() = runBlocking {
        coEvery { groupRepo.getById("g1") } returns group.copy(description = "Ski week")

        useCase("g1", listOf("wss://x.relay"))

        coVerify {
            groupRepo.updateFromMeta(
                "g1",
                "Trip",
                listOf(pubkey),
                listOf("wss://x.relay"),
                1000,
                pubkey,
                mapOf(pubkey to "Alice"),
                "Ski week",
                "evt1"
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects empty relay list`() = runBlocking {
        useCase("g1", emptyList())
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects non-wss relay URLs`() = runBlocking {
        useCase("g1", listOf("ws://insecure.relay"))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects mixed wss and non-wss`() = runBlocking {
        useCase("g1", listOf("wss://good.relay", "http://bad.relay"))
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun `invoke throws when group not found`() = runBlocking {
        coEvery { groupRepo.getById("missing") } returns null
        useCase("missing", listOf("wss://r"))
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun `invoke throws when group key not found`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 0) } returns null
        useCase("g1", listOf("wss://r"))
        Unit
    }

    @Test
    fun `invoke with single relay works`() = runBlocking {
        useCase("g1", listOf("wss://solo.relay"))

        coVerify {
            groupRepo.updateFromMeta(
                "g1",
                any(),
                any(),
                eq(listOf("wss://solo.relay")),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
        coVerify { eventPublisher.publishDirect(any(), "g1", any(), "group_meta") }
    }

    @Test
    fun `invoke signs event with correct group id and event type`() = runBlocking {
        useCase("g1", listOf("wss://r"))

        verify { signer.createSignedEvent("g1", "group_meta", "encrypted", isNull()) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects non-creator`() = runBlocking {
        every { identity.getPublicKeyHex() } returns "bb".repeat(32)
        useCase("g1", listOf("wss://r"))
        Unit
    }

    @Test
    fun `invoke encrypts with the key for the group's current epoch`() = runBlocking {
        coEvery { groupRepo.getById("g1") } returns group.copy(keyEpoch = 3)
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 3) } returns "epoch3key"

        useCase("g1", listOf("wss://r"))

        verify { encryption.encrypt(any(), "epoch3key") }
        coVerify(exactly = 0) { groupRepo.getGroupKey(any()) }
    }

    @Test
    fun `invoke rejects overbudget drafts before signing writing or publishing`() = runBlocking {
        val overbudget = budgetRelays()
        assertEquals(256, overbudget.sumOf { 1 + it.toByteArray(Charsets.UTF_8).size })
        assertTrue(overbudget.all(InviteLinkCodec::relayFits))
        val invalidDrafts = listOf(
            overbudget,
            RelayDefaults.KNOWN_RELAYS.take(InviteLinkCodec.MAX_RELAYS + 1),
            listOf("wss://relay.example/" + "a".repeat(235)),
            listOf("wss://valid.example", "ws://invalid.example")
        )

        for (relays in invalidDrafts) {
            assertFalse(InviteLinkCodec.fitsInviteLink(relays))
            val failure = runCatching { useCase("g1", relays) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains("Relays do not fit in an invite link"))
        }

        coVerify { listOf(groupRepo, eventPublisher, nostrClient, selfHeal) wasNot Called }
        verify { listOf(encryption, signer) wasNot Called }
    }

    @Test
    fun `invoke accepts exactly 255 custom bytes alongside known relays`() = runBlocking {
        val custom = budgetRelays(secondPathLength = 106)
        val relays = custom + RelayDefaults.DEFAULT_RELAYS
        assertEquals(255, custom.sumOf { 1 + it.toByteArray(Charsets.UTF_8).size })
        assertTrue(InviteLinkCodec.fitsInviteLink(relays))

        useCase("g1", relays)

        coVerify {
            groupRepo.updateFromMeta(
                "g1", any(), any(), relays, any(), any(), any(), any(), any(), any(), any(), any()
            )
            eventPublisher.publishDirect(fakeEvent, "g1", "encrypted", "group_meta")
        }
    }

    @Test
    fun `invoke accepts ten known relays`() = runBlocking {
        val relays = RelayDefaults.KNOWN_RELAYS.take(InviteLinkCodec.MAX_RELAYS)

        useCase("g1", relays)

        coVerify {
            groupRepo.updateFromMeta(
                "g1", any(), any(), relays, any(), any(), any(), any(), any(), any(), any(), any()
            )
            eventPublisher.publishDirect(fakeEvent, "g1", "encrypted", "group_meta")
        }
    }

    @Test
    fun `a rejected local update throws instead of reconnecting publishing healing or reporting success`() =
        runBlocking {
            coEvery {
                groupRepo.updateFromMeta(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
                )
            } returns false

            val failure = runCatching { useCase("g1", listOf("wss://new.relay")) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("could be saved"))
            coVerify(exactly = 1) {
                groupRepo.updateFromMeta(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
                )
            }
            coVerify { listOf(eventPublisher, nostrClient, selfHeal) wasNot Called }
        }

    @Test
    fun `valid relay update can repair an already saved overbudget list`() = runBlocking {
        val savedRelays = budgetRelays()
        coEvery { groupRepo.getById("g1") } returns group.copy(relays = savedRelays)
        val replacement = listOf("wss://repaired.example")

        useCase("g1", replacement)

        coVerify {
            groupRepo.updateFromMeta(
                "g1", any(), any(), replacement, any(), any(), any(), any(), any(), any(), any(), any()
            )
            nostrClient.connect(savedRelays + replacement)
            eventPublisher.publishDirect(fakeEvent, "g1", "encrypted", "group_meta")
            selfHeal("g1")
        }
    }

    private fun budgetRelays(secondPathLength: Int = 107): List<String> = listOf(
        "wss://relay.example/" + "a".repeat(107),
        "wss://relay.example/" + "b".repeat(secondPathLength)
    )
}
