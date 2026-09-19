package com.splitfree.domain.usecase.group

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.test.RelayProbeAssertions.assertAccepted
import com.splitfree.test.RelayProbeAssertions.requireEvents
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Join-usecase test with a separate live group-meta round-trip and real crypto.
 * Repositories, event publishing, sync, self-heal, identity storage and settings are mocked;
 * this does not exercise app persistence, join-announcement delivery or the full sync pipeline.
 * Opt in with `-DREAL_RELAY_TEST=true`; relay rejection or incomplete/missing history fails.
 */
class RealRelayIntegrationTest {
    private lateinit var relayScope: CoroutineScope
    private var logMocked = false

    // Independent relay pools in one JVM; no physical devices.
    private lateinit var phone1Client: NostrClient
    private lateinit var phone2Client: NostrClient

    private val phone1Encryption = GroupEncryption(CompressionUtil)
    private val phone2Encryption = GroupEncryption(CompressionUtil)

    private lateinit var phone1PrivKey: ByteArray
    private lateinit var phone1PubKey: String
    private lateinit var phone2PrivKey: ByteArray
    private lateinit var phone2PubKey: String

    // The join uses mocked application boundaries; the metadata probe uses real relay clients.
    private val phone1Identity = mockk<IdentityManager>()
    private val phone2Identity = mockk<IdentityManager>()
    private val phone2Repo = mockk<GroupRepositoryContract>(relaxed = true)
    private val phone2EventPublisher = mockk<EventPublisherContract>(relaxed = true)
    private val phone2SelfHeal = mockk<SelfHealUseCase>(relaxed = true)
    private val phone2SyncEngine = mockk<SyncEngineContract>(relaxed = true)

    private lateinit var phone1Signer: EventSigner
    private lateinit var phone2Signer: EventSigner

    private val relays = listOf("wss://relay.snort.social", "wss://nos.lol")

    @Before
    fun setup() {
        Assume.assumeTrue(
            "Skipped: set -DREAL_RELAY_TEST=true to run real relay tests",
            System.getProperty("REAL_RELAY_TEST") == "true"
        )

        mockkStatic(android.util.Log::class)
        logMocked = true
        relayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0

        // Fresh identities keep disposable relay events isolated between runs.
        phone1PrivKey = generateValidPrivateKey()
        phone1PubKey = NostrEvent.pubkeyFromPrivkey(phone1PrivKey)
        phone2PrivKey = generateValidPrivateKey()
        phone2PubKey = NostrEvent.pubkeyFromPrivkey(phone2PrivKey)

        println("Phone 1 pubkey: ${phone1PubKey.take(16)}...")
        println("Phone 2 pubkey: ${phone2PubKey.take(16)}...")

        // Signers wipe returned key bytes; each call must receive a copy.
        every { phone1Identity.getPublicKeyHex() } returns phone1PubKey
        every { phone1Identity.getPrivateKeyBytes() } answers { phone1PrivKey.copyOf() }
        every { phone1Identity.hasIdentity() } returns true
        every { phone2Identity.getPublicKeyHex() } returns phone2PubKey
        every { phone2Identity.getPrivateKeyBytes() } answers { phone2PrivKey.copyOf() }
        every { phone2Identity.hasIdentity() } returns true

        phone1Signer = EventSigner(phone1Identity)
        phone2Signer = EventSigner(phone2Identity)

        phone1Client = NostrClient(relayScope)
        phone2Client = NostrClient(relayScope)

        coEvery { phone2Repo.getById(any()) } returns null
    }

    @After
    fun teardown() {
        if (::phone1Client.isInitialized) phone1Client.disconnect()
        if (::phone2Client.isInitialized) phone2Client.disconnect()
        if (::relayScope.isInitialized) relayScope.cancel()
        if (::phone1PrivKey.isInitialized) phone1PrivKey.fill(0)
        if (::phone2PrivKey.isInitialized) phone2PrivKey.fill(0)
        if (logMocked) unmockkStatic(android.util.Log::class)
    }

    private fun generateValidPrivateKey(): ByteArray {
        val random = java.security.SecureRandom()
        val key = ByteArray(32)
        do {
            random.nextBytes(key)
        } while (!fr.acinq.secp256k1.Secp256k1
                .secKeyVerify(key)
        )
        return key
    }

    @Test(timeout = 60_000)
    fun `join usecase with mocked storage and sync plus live metadata round-trip`() = runBlocking {
        // Build a creator-bound fixture without running CreateGroupUseCase.
        val groupKey = phone1Encryption.generateGroupKey()
        val createdAt = System.currentTimeMillis() / 1000
        val groupId = GroupIdentity.derive(phone1PubKey, createdAt)
        val groupName = "IntegrationTest-${System.currentTimeMillis()}"

        val phone1Group =
            Group(
                id = groupId,
                name = groupName,
                createdBy = phone1PubKey,
                createdAt = createdAt,
                members = listOf(phone1PubKey),
                relays = relays
            )

        println("\n=== PHONE 1: Creating group ===")
        println("Group ID: $groupId")
        println("Group name: $groupName")

        val metaJson =
            buildJsonObject {
                put("name", JsonPrimitive(groupName))
                put("description", JsonPrimitive(""))
                put("created_by", JsonPrimitive(phone1PubKey))
                put("created_at", JsonPrimitive(phone1Group.createdAt))
                putJsonArray("members") {
                    add(JsonPrimitive(phone1PubKey))
                }
                putJsonArray("relays") {
                    relays.forEach { add(JsonPrimitive(it)) }
                }
            }.toString()

        val encrypted = phone1Encryption.encrypt(metaJson, groupKey)
        println("Encrypted meta: ${encrypted.take(40)}...")

        val event =
            phone1Signer.createSignedEvent(
                groupId = groupId,
                eventType = "group_meta",
                encryptedContent = encrypted
            )
        assertTrue("Event signature should verify", event.verify())
        println("Signed event ID: ${event.id.take(16)}...")
        println("Event signature valid: ${event.verify()}")

        println("\n=== PHONE 1: Publishing to real relays ===")
        phone1Client.authSigner = { challenge, relayUrl ->
            phone1Signer.createAuthEvent(challenge, relayUrl)
        }
        phone1Client.connect(relays)
        withTimeout(15_000) { phone1Client.connectionState.first { it } }

        assertTrue("Phone 1 should be connected", phone1Client.isConnected)
        println("Phone 1 connected: ${phone1Client.isConnected}")

        assertAccepted(phone1Client.publish(event))

        val inviteLink = InviteLinkCodec.encode(phone1Group, groupKey)
        println("\n=== INVITE LINK ===")
        println("Link length: ${inviteLink.length} chars")

        // Connect an independent reader; the metadata fetch happens after the mocked join.
        println("\n=== PHONE 2: Joining via invite link ===")
        phone2Client.authSigner = { challenge, relayUrl ->
            phone2Signer.createAuthEvent(challenge, relayUrl)
        }
        phone2Client.connect(relays)
        withTimeout(15_000) { phone2Client.connectionState.first { it } }

        assertTrue("Phone 2 should be connected", phone2Client.isConnected)
        println("Phone 2 connected: ${phone2Client.isConnected}")

        // The invite carries the raw group key in a Base64 payload, not encrypted key exchange.
        val savedGroup = slot<Group>()
        val savedKey = slot<String>()
        coEvery { phone2Repo.save(capture(savedGroup), capture(savedKey)) } answers {
            coEvery { phone2Repo.getById(savedGroup.captured.id) } returns savedGroup.captured
        }

        val joinUseCase =
            JoinGroupUseCase(
                phone2Repo,
                phone2Identity,
                phone2Client,
                phone2Signer,
                phone2Encryption,
                phone2EventPublisher,
                phone2SelfHeal,
                phone2SyncEngine,
                mockk(relaxed = true),
                mockk(relaxed = true)
            )

        val joinedGroup = joinUseCase(inviteLink)

        // Fetch separately from the mocked initial sync and require the exact creator event.
        val fetched = phone2Client.fetchEvents(groupId, 0, phone2PubKey)
        assertTrue("Metadata fetch must complete on all requested relays", fetched.complete)
        val receivedMeta = requireEvents(listOf(event), fetched.events).single()
        val decrypted = phone2Encryption.decrypt(receivedMeta.content, groupKey)
        assertEquals("Metadata must survive the live relay round-trip", metaJson, decrypted)

        println("\n=== RESULTS ===")
        println("Phone 2 joined group: ${joinedGroup.id}")
        println("Group name: ${joinedGroup.name}")
        println("Group relays: ${joinedGroup.relays}")
        println("Phone 2 members: ${joinedGroup.members.map { it.take(8) }}")

        assertEquals("Group IDs must match", groupId, joinedGroup.id)
        assertEquals("Group names must match", groupName, joinedGroup.name)
        assertTrue("Phone 2 pubkey must be in members", phone2PubKey in joinedGroup.members)
        assertEquals("Group key must match", groupKey, savedKey.captured)
        assertEquals("Relays must match", relays.toSet(), joinedGroup.relays.toSet())

        // The join uses the member self-update API, not the creator metadata update API.
        coVerify {
            phone2Repo.applyMemberSelfUpdate(groupId, phone2PubKey, any(), any(), join = true, displayName = any())
        }
        coVerify(exactly = 0) {
            phone2Repo.updateFromMeta(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }

        println("\n✅ JOIN USECASE + LIVE METADATA ROUND-TRIP PASSED (mocked persistence and sync)")
        println("   Phone 1 (${phone1PubKey.take(8)}) created group '$groupName'")
        println("   Phone 1 received relay acceptance for group_meta")
        println("   Phone 2 (${phone2PubKey.take(8)}) ran the join usecase with mocked storage, publisher and sync")
        println("   Phone 2 verified relay round-trip for group_meta")
        println("   Both phones share group ID: ${groupId.take(8)}...")
    }
}
