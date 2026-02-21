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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private fun validSenderPrivKey(): ByteArray {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        while (!fr.acinq.secp256k1.Secp256k1.secKeyVerify(key)) {
            SecureRandom().nextBytes(key)
        }
        return key
    }

    /** Encode an invite link — group key is encrypted in the URL, no relay needed. */
    private fun buildInviteUri(
        groupId: String = java.util.UUID.randomUUID().toString(),
        groupKey: String = "testkey123",
        relays: List<String> = listOf("wss://relay.damus.io"),
        name: String = "TestGroup"
    ): String {
        val group = Group(groupId, name, "", pubkey, 1000, listOf(pubkey), relays)
        return InviteLinkCodec.encode(group, groupKey, validSenderPrivKey())
    }

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

    @Test
    fun `invoke creates group from valid invite link`() = runBlocking {
        val inviteUri = buildInviteUri()
        val invite = InviteLinkCodec.decode(inviteUri)
        val group = useCase(inviteUri)
        assertEquals("TestGroup", group.name)
        assertEquals(listOf(pubkey), group.members)
        assertEquals(invite.inviterPubkey, group.createdBy)
        coVerify { groupRepo.save(any(), any()) }
    }

    @Test
    fun `invoke returns existing group if already joined`() = runBlocking {
        val groupId = java.util.UUID.randomUUID().toString()
        val existing = Group(groupId, "Existing", "", pubkey, 1000, listOf(pubkey), listOf("wss://relay.damus.io"))
        coEvery { groupRepo.getById(groupId) } returns existing
        val result = useCase(buildInviteUri(groupId = groupId))
        assertEquals("Existing", result.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects invalid group ID format`() = runBlocking {
        useCase(buildInviteUri(groupId = "not-a-uuid"))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invoke rejects expired invite link`() = runBlocking {
        val groupId = java.util.UUID.randomUUID().toString()
        val group = Group(groupId, "Test", "", pubkey, 1000, listOf(pubkey), listOf("wss://relay.damus.io"))
        val link = InviteLinkCodec.encode(group, "key", validSenderPrivKey())

        // Tamper with the expiry bytes to set it in the past
        // New layout: uuid(16) + ephPriv(32) + senderPub(32) + bitmap(1) + customLen(1) = 82
        val payload = link.substringAfter("d=")
        val data = java.util.Base64.getUrlDecoder().decode(payload)
        val expPos = 82
        val pastExp = ((System.currentTimeMillis() / 1000 - 3600) and 0xFFFFFFFFL).toInt()
        data[expPos] = ((pastExp ushr 24) and 0xFF).toByte()
        data[expPos + 1] = ((pastExp ushr 16) and 0xFF).toByte()
        data[expPos + 2] = ((pastExp ushr 8) and 0xFF).toByte()
        data[expPos + 3] = (pastExp and 0xFF).toByte()
        val tamperedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)
        useCase("splitfree://join?d=$tamperedPayload")
        Unit
    }

    @Test
    fun `invoke runs initial sync via syncEngine`() = runBlocking {
        val groupId = java.util.UUID.randomUUID().toString()
        every { nostrClient.isConnected } returns false
        coEvery { syncEngine.pullEvents(any(), any(), any(), lenientTimestamp = true) } returns 3

        useCase(buildInviteUri(groupId = groupId))
        coVerify { syncEngine.pullEvents(groupId, 0, any(), lenientTimestamp = true) }
    }

    @Test
    fun `invoke handles initial sync failure gracefully`() = runBlocking {
        every { nostrClient.isConnected } returns false
        coEvery { syncEngine.pullEvents(any(), any(), any(), lenientTimestamp = any()) } throws
            RuntimeException("sync failed")

        val group = useCase(buildInviteUri())
        assertNotNull(group)
    }

    @Test
    fun `invoke with non-expired link succeeds`() = runBlocking {
        assertNotNull(useCase(buildInviteUri()))
    }
}
