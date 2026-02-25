package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
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

class UpdateGroupRelaysUseCaseTest {
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val encryption = mockk<GroupEncryption>()
    private val signer = mockk<EventSigner>()
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)
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
        coEvery { groupRepo.getGroupKey("g1") } returns fakeGroupKey

        useCase =
            UpdateGroupRelaysUseCase(
                groupRepo,
                encryption,
                signer,
                eventPublisher,
                mockk(relaxed = true),
                mockk(relaxed = true),
                identity
            )
    }

    @After
    fun teardown() = unmockkStatic(android.util.Log::class)

    @Test
    fun `invoke updates relays locally and publishes group_meta`() = runBlocking {
        val newRelays = listOf("wss://new.relay", "wss://another.relay")

        useCase("g1", newRelays)

        coVerify {
            groupRepo.updateFromMeta(
                "g1",
                "Trip",
                listOf(pubkey),
                newRelays,
                0,
                "",
                match { it[pubkey] == "Alice" }
            )
        }
        verify { encryption.encrypt(match { it.contains("new.relay") && it.contains("another.relay") }, fakeGroupKey) }
        coVerify { eventPublisher.publishDirect(fakeEvent, "g1", "encrypted", "group_meta") }
    }

    @Test
    fun `invoke preserves group name members and memberNames`() = runBlocking {
        useCase("g1", listOf("wss://x.relay"))

        coVerify {
            groupRepo.updateFromMeta(
                "g1",
                "Trip",
                listOf(pubkey),
                listOf("wss://x.relay"),
                0,
                "",
                mapOf(pubkey to "Alice")
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
        coEvery { groupRepo.getGroupKey("g1") } returns null
        useCase("g1", listOf("wss://r"))
        Unit
    }

    @Test
    fun `invoke with single relay works`() = runBlocking {
        useCase("g1", listOf("wss://solo.relay"))

        coVerify {
            groupRepo.updateFromMeta("g1", any(), any(), eq(listOf("wss://solo.relay")), any(), any(), any())
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
}
