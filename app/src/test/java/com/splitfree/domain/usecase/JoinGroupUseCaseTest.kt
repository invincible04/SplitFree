package com.splitfree.domain.usecase

import android.util.Base64
import com.splitfree.data.local.EventDao
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.Group
import com.splitfree.sync.EventProcessor
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class JoinGroupUseCaseTest {
    private val groupRepo = mockk<GroupRepository>(relaxed = true)
    private val identity = mockk<IdentityManager>()
    private val nostrClient = mockk<NostrClient>(relaxed = true)
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val eventProcessor = mockk<EventProcessor>(relaxed = true)
    private val signer = mockk<EventSigner>(relaxed = true)

    private lateinit var useCase: JoinGroupUseCase
    private val pubkey = "aa".repeat(32)

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0

        mockkStatic(Base64::class)
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64
                .getUrlDecoder()
                .decode(firstArg<String>())
        }
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(firstArg<ByteArray>())
        }

        every { identity.getPublicKeyHex() } returns pubkey
        coEvery { groupRepo.getById(any()) } returns null

        useCase = JoinGroupUseCase(groupRepo, identity, nostrClient, eventDao, eventProcessor, signer)
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
        unmockkStatic(Base64::class)
    }

    private fun buildUri(
        groupId: String =
            java.util.UUID
                .randomUUID()
                .toString(),
        groupKey: String = "testkey123",
        relays: String = "wss://relay.test",
        name: String = "TestGroup",
        exp: Long? = null,
    ): String {
        val g =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(groupId.toByteArray())
        val k =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(groupKey.toByteArray())
        val n = java.net.URLEncoder.encode(name, "UTF-8")
        var uri = "splitfree://join?g=$g&k=$k&r=$relays&n=$n"
        if (exp != null) uri += "&exp=$exp"
        return uri
    }

    @Test
    fun `invoke creates group from valid invite link`() =
        runBlocking {
            val uri = buildUri()
            val group = useCase(uri)
            assertEquals("TestGroup", group.name)
            assertEquals(listOf(pubkey), group.members)
            coVerify { groupRepo.save(any(), any()) }
        }

    @Test
    fun `invoke returns existing group if already joined`() =
        runBlocking {
            val groupId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val existing = Group(groupId, "Existing", "", pubkey, 1000, listOf(pubkey), listOf("wss://r"))
            coEvery { groupRepo.getById(groupId) } returns existing
            val result = useCase(buildUri(groupId = groupId))
            assertEquals("Existing", result.name)
        }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects missing g parameter`() =
        runBlocking {
            useCase("splitfree://join?k=abc")
            Unit
        }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects missing k parameter`() =
        runBlocking {
            val g =
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("test".toByteArray())
            useCase("splitfree://join?g=$g")
            Unit
        }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects invalid group ID format`() =
        runBlocking {
            useCase(buildUri(groupId = "not-a-uuid"))
            Unit
        }

    @Test(expected = IllegalStateException::class)
    fun `invoke rejects expired invite link`() =
        runBlocking {
            val expired = System.currentTimeMillis() / 1000 - 3600
            useCase(buildUri(exp = expired))
            Unit
        }

    @Test(expected = IllegalStateException::class)
    fun `invoke rejects empty relays`() =
        runBlocking {
            useCase(buildUri(relays = ""))
            Unit
        }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects too many relays`() =
        runBlocking {
            val relays = (1..11).joinToString(",") { "wss://relay$it.test" }
            useCase(buildUri(relays = relays))
            Unit
        }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects non-wss relay`() =
        runBlocking {
            useCase(buildUri(relays = "ws://insecure.relay"))
            Unit
        }

    // --- createInviteLink ---

    @Test
    fun `createInviteLink produces valid URI`() {
        val group = Group("g1", "Trip", "", pubkey, 1000, listOf(pubkey), listOf("wss://r"))
        val link = JoinGroupUseCase.createInviteLink(group, "key123")
        assertTrue(link.startsWith("splitfree://join?"))
        assertTrue(link.contains("g="))
        assertTrue(link.contains("k="))
        assertTrue(link.contains("r="))
        assertTrue(link.contains("n="))
        assertTrue(link.contains("exp="))
    }

    // --- initialSync branches ---

    @Test
    fun `invoke runs initial sync and stores fetched events`() =
        runBlocking {
            val groupId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            every { nostrClient.isConnected } returns false
            val fakeEvent =
                com.splitfree.domain.crypto.NostrEvent(
                    id = "evt1",
                    pubkey = pubkey,
                    createdAt = 1000,
                    kind = 30078,
                    tags = listOf(listOf("g", groupId)),
                    content = "enc",
                    sig = "sig",
                )
            coEvery { nostrClient.fetchEvents(groupId, 0) } returns listOf(fakeEvent)
            coEvery { eventDao.getEventIds(groupId) } returns emptyList()
            coEvery { eventProcessor.process(any(), any(), any(), lenientTimestamp = true) } returns
                EventProcessor.ProcessResult(stored = true)

            useCase(buildUri(groupId = groupId))
            coVerify { eventProcessor.process(fakeEvent, groupId, any(), lenientTimestamp = true) }
            coVerify { groupRepo.updateLastSync(groupId, any()) }
        }

    @Test
    fun `invoke skips already-existing events during initial sync`() =
        runBlocking {
            val groupId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            every { nostrClient.isConnected } returns true
            val fakeEvent =
                com.splitfree.domain.crypto.NostrEvent(
                    id = "existing-evt",
                    pubkey = pubkey,
                    createdAt = 1000,
                    kind = 30078,
                    tags = listOf(listOf("g", groupId)),
                    content = "enc",
                    sig = "sig",
                )
            coEvery { nostrClient.fetchEvents(groupId, 0) } returns listOf(fakeEvent)
            coEvery { eventDao.getEventIds(groupId) } returns listOf("existing-evt")

            useCase(buildUri(groupId = groupId))
            coVerify(exactly = 0) { eventProcessor.process(any(), any(), any(), lenientTimestamp = any()) }
        }

    @Test
    fun `invoke handles initial sync failure gracefully`() =
        runBlocking {
            val groupId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            every { nostrClient.isConnected } returns false
            coEvery { nostrClient.acquireConnection() } throws RuntimeException("connection failed")

            val group = useCase(buildUri(groupId = groupId))
            // Should not throw — sync failure is caught
            assertNotNull(group)
        }

    @Test
    fun `invoke with non-expired link succeeds`() =
        runBlocking {
            val future = System.currentTimeMillis() / 1000 + 3600
            val group = useCase(buildUri(exp = future))
            assertNotNull(group)
        }

    @Test
    fun `invoke with no exp parameter succeeds`() =
        runBlocking {
            val group = useCase(buildUri())
            assertNotNull(group)
        }

    @Test
    fun `invoke with no name defaults to Group`() =
        runBlocking {
            val groupId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val g =
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(groupId.toByteArray())
            val k =
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("key".toByteArray())
            val uri = "splitfree://join?g=$g&k=$k&r=wss://relay.test"
            val group = useCase(uri)
            assertEquals("Group", group.name)
        }
}
