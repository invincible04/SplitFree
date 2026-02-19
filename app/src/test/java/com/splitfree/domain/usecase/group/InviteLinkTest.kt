package com.splitfree.domain.usecase.group

import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import java.security.SecureRandom
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for invite link encoding/decoding (NIP-44 encrypted key in URL).
 */
class InviteLinkTest {

    private val testPubkey = "aa".repeat(32)

    private fun validSenderPrivKey(): ByteArray {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        while (!fr.acinq.secp256k1.Secp256k1.secKeyVerify(key)) {
            SecureRandom().nextBytes(key)
        }
        return key
    }

    private fun testGroup(
        id: String = "550e8400-e29b-41d4-a716-446655440000",
        name: String = "Trip",
        relays: List<String> = listOf("wss://relay.damus.io", "wss://nos.lol")
    ) = Group(id, name, "", testPubkey, 1000, listOf(testPubkey), relays)

    @Test
    fun `encode produces link with encrypted key — key not visible in URL`() {
        val link = InviteLinkCodec.encode(testGroup(), "key123", validSenderPrivKey())
        assertTrue(link.startsWith("splitfree://join?d="))
        assertFalse("Link must not contain plaintext group key", link.contains("key123"))
    }

    @Test
    fun `round-trip preserves all fields and decrypts group key`() {
        val groupKey = "supersecretgroupkey"
        val group = testGroup(name = "Goa Trip 2026")
        val link = InviteLinkCodec.encode(group, groupKey, validSenderPrivKey())

        val invite = InviteLinkCodec.decode(link)
        assertEquals(group.id, invite.groupId)
        assertEquals("Goa Trip 2026", invite.name)
        assertEquals(groupKey, invite.groupKey)
        assertTrue(invite.relays.contains("wss://relay.damus.io"))
        assertTrue(invite.relays.contains("wss://nos.lol"))
        assertTrue(invite.expiry > System.currentTimeMillis() / 1000)
    }

    @Test
    fun `link with custom relay includes it in payload`() {
        val relays = listOf("wss://relay.damus.io", "wss://custom.relay.example")
        val link = InviteLinkCodec.encode(testGroup(relays = relays), "key", validSenderPrivKey())
        val invite = InviteLinkCodec.decode(link)
        assertEquals(2, invite.relays.size)
        assertTrue(invite.relays.contains("wss://relay.damus.io"))
        assertTrue(invite.relays.contains("wss://custom.relay.example"))
    }

    @Test
    fun `link with unicode group name`() {
        val link = InviteLinkCodec.encode(testGroup(name = "गोवा ट्रिप 🏖️"), "key", validSenderPrivKey())
        assertEquals("गोवा ट्रिप 🏖️", InviteLinkCodec.decode(link).name)
    }

    @Test
    fun `link fits in QR code — under 400 chars with known relays`() {
        val link = InviteLinkCodec.encode(testGroup(name = "Goa Trip 2026"), "key123", validSenderPrivKey())
        assertTrue("Link too long: ${link.length} chars", link.length < 400)
    }

    @Test
    fun `link payload contains no URL-breaking characters`() {
        val link = InviteLinkCodec.encode(testGroup(), "key", validSenderPrivKey())
        val payload = link.substringAfter("d=")
        assertFalse("Payload contains +", payload.contains("+"))
        assertFalse("Payload contains /", payload.contains("/"))
        assertFalse("Payload contains space", payload.contains(" "))
    }

    @Test
    fun `different sender keys produce different encrypted blobs`() {
        val group = testGroup()
        val link1 = InviteLinkCodec.encode(group, "key", validSenderPrivKey())
        val link2 = InviteLinkCodec.encode(group, "key", validSenderPrivKey())
        assertNotEquals("Links should differ due to ephemeral keys", link1, link2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects truncated payload`() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(10))
        InviteLinkCodec.decode("splitfree://join?d=$payload")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects missing d parameter`() {
        InviteLinkCodec.decode("splitfree://join?foo=bar")
    }
}
