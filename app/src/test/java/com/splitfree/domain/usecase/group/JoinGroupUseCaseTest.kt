package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.domain.util.hexToBytes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JoinGroupUseCaseTest {
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val identity = mockk<IdentityContract>()
    private val nostrClient = mockk<NostrClientContract>(relaxed = true)
    private val signer = mockk<EventSigner>(relaxed = true)
    private val encryption = mockk<GroupEncryption>(relaxed = true)
    private val eventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val selfHeal = mockk<SelfHealUseCase>(relaxed = true)
    private val syncEngine = mockk<SyncEngineContract>(relaxed = true)

    private lateinit var useCase: JoinGroupUseCase
    private val pubkey = "aa".repeat(32)

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0

        every { identity.getPublicKeyHex() } returns pubkey
        coEvery { groupRepo.getById(any()) } returns null

        useCase =
            JoinGroupUseCase(groupRepo, identity, nostrClient, signer, encryption, eventPublisher, selfHeal, syncEngine)
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    private fun buildUri(
        groupId: String =
            java.util.UUID
                .randomUUID()
                .toString(),
        groupKey: String = "testkey123",
        relays: String = "wss://relay.test",
        name: String = "TestGroup",
        exp: Long? = null
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
    fun `invoke creates group from valid invite link`() = runBlocking {
        val uri = buildUri()
        val group = useCase(uri)
        assertEquals("TestGroup", group.name)
        assertEquals(listOf(pubkey), group.members)
        coVerify { groupRepo.save(any(), any()) }
    }

    @Test
    fun `invoke returns existing group if already joined`() = runBlocking {
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
    fun `invoke rejects missing g parameter`() = runBlocking {
        useCase("splitfree://join?k=abc")
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects missing k parameter`() = runBlocking {
        val g =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("test".toByteArray())
        useCase("splitfree://join?g=$g")
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects invalid group ID format`() = runBlocking {
        useCase(buildUri(groupId = "not-a-uuid"))
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun `invoke rejects expired invite link`() = runBlocking {
        val expired = System.currentTimeMillis() / 1000 - 3600
        useCase(buildUri(exp = expired))
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun `invoke rejects empty relays`() = runBlocking {
        useCase(buildUri(relays = ""))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects too many relays`() = runBlocking {
        val relays = (1..11).joinToString(",") { "wss://relay$it.test" }
        useCase(buildUri(relays = relays))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects non-wss relay`() = runBlocking {
        useCase(buildUri(relays = "ws://insecure.relay"))
        Unit
    }

    // --- createInviteLink ---

    @Test
    fun `createInviteLink produces valid v2 URI when no sender key`() {
        val group =
            Group(
                "550e8400-e29b-41d4-a716-446655440000",
                "Trip",
                "",
                pubkey,
                1000,
                listOf(pubkey),
                listOf("wss://relay.damus.io", "wss://nos.lol")
            )
        val (link, event) = InviteLinkCodec.encode(group, "key123")
        assertTrue(link.startsWith("splitfree://join?d="))
        assertNull("v2 link should not produce key delivery event", event)
        assertTrue("Link too long: ${link.length}", link.length < 150)
        val payload = link.substringAfter("d=")
        assertFalse(payload.contains("+"))
        assertFalse(payload.contains("/"))
    }

    @Test
    fun `createInviteLink v3 produces link and key delivery event`() {
        val group =
            Group(
                "550e8400-e29b-41d4-a716-446655440000",
                "Trip",
                "",
                pubkey,
                1000,
                listOf(pubkey),
                listOf("wss://relay.damus.io", "wss://nos.lol")
            )
        val senderPriv = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        while (!fr.acinq.secp256k1.Secp256k1
                .secKeyVerify(senderPriv)
        ) {
            java.security.SecureRandom().nextBytes(senderPriv)
        }
        val (link, event) = InviteLinkCodec.encode(group, "key123", senderPriv)
        assertTrue(link.startsWith("splitfree://join?d="))
        assertNotNull("v3 link must produce key delivery event", event)
        assertEquals(1059, event!!.kind)
        // Link should NOT contain the group key
        assertFalse("v3 link must not contain group key", link.contains("key123"))
        assertTrue("Link too long: ${link.length}", link.length < 200)
    }

    @Test
    fun `v3 round-trip - key delivery event is decryptable with ephemeral key from link`() {
        val group =
            Group(
                "550e8400-e29b-41d4-a716-446655440000",
                "Trip",
                "",
                pubkey,
                1000,
                listOf(pubkey),
                listOf("wss://relay.damus.io", "wss://nos.lol")
            )
        val groupKey = "supersecretgroupkey"
        val senderPriv = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        while (!fr.acinq.secp256k1.Secp256k1
                .secKeyVerify(senderPriv)
        ) {
            java.security.SecureRandom().nextBytes(senderPriv)
        }
        val (link, giftWrap) = InviteLinkCodec.encode(group, groupKey, senderPriv)
        assertNotNull(giftWrap)

        // Extract ephemeral private key from the link (simulating what decodeCompactLink does)
        val payload = link.substringAfter("d=")
        val data =
            java.util.Base64
                .getUrlDecoder()
                .decode(payload)
        assertEquals("version must be 3", 3, data[0].toInt() and 0xFF)
        // Skip version(1) + uuid(16) = 17 bytes to get ephPriv(32)
        val ephPriv = data.copyOfRange(17, 49)

        // Unwrap gift wrap: decrypt with ephemeral key
        val ephPubHex =
            com.splitfree.domain.crypto.NostrEvent
                .pubkeyFromPrivkey(ephPriv)
        val wrapConvKey =
            com.splitfree.domain.crypto.nip.Nip44
                .getConversationKey(ephPriv, giftWrap!!.pubkey.hexToBytes())
        val sealJson =
            com.splitfree.domain.crypto.nip.Nip44
                .decrypt(giftWrap.content, wrapConvKey)
        val seal =
            com.splitfree.domain.crypto.NostrEvent
                .fromJson(sealJson)
        assertNotNull(seal)
        assertEquals(13, seal!!.kind)

        // Unwrap seal: decrypt with ephemeral key + seal author
        val sealConvKey =
            com.splitfree.domain.crypto.nip.Nip44
                .getConversationKey(ephPriv, seal.pubkey.hexToBytes())
        val rumorJson =
            com.splitfree.domain.crypto.nip.Nip44
                .decrypt(seal.content, sealConvKey)
        val rumor =
            com.splitfree.domain.crypto.NostrEvent
                .fromJson(rumorJson)
        assertNotNull(rumor)

        // Verify rumor contains the group key
        assertEquals(groupKey, rumor!!.content)
        val gTag = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.get(1)
        val tTag = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "t" }?.get(1)
        assertEquals(group.id, gTag)
        assertEquals("key_delivery", tTag)
    }

    // --- initialSync branches ---

    @Test
    fun `invoke runs initial sync via syncEngine`() = runBlocking {
        val groupId =
            java.util.UUID
                .randomUUID()
                .toString()
        every { nostrClient.isConnected } returns false
        coEvery { syncEngine.pullEvents(any(), any(), any(), lenientTimestamp = true) } returns 3

        useCase(buildUri(groupId = groupId))
        coVerify { syncEngine.pullEvents(groupId, 0, any(), lenientTimestamp = true) }
    }

    @Test
    fun `invoke handles initial sync failure gracefully`() = runBlocking {
        val groupId =
            java.util.UUID
                .randomUUID()
                .toString()
        every { nostrClient.isConnected } returns false
        coEvery { syncEngine.pullEvents(any(), any(), any(), lenientTimestamp = any()) } throws
            RuntimeException("sync failed")

        val group = useCase(buildUri(groupId = groupId))
        // Should not throw — sync failure is caught
        assertNotNull(group)
    }

    @Test
    fun `invoke with non-expired link succeeds`() = runBlocking {
        val future = System.currentTimeMillis() / 1000 + 3600
        val group = useCase(buildUri(exp = future))
        assertNotNull(group)
    }

    @Test
    fun `invoke with no exp parameter succeeds`() = runBlocking {
        val group = useCase(buildUri())
        assertNotNull(group)
    }

    @Test
    fun `invoke with no name defaults to Group`() = runBlocking {
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
