package com.splitfree.domain.usecase.group

import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the version-2 invite link codec (creator-bound group id, raw epoch key).
 */
class InviteLinkTest {

    private val creatorPubkey = "aa".repeat(32)
    private val otherPubkey = "bb".repeat(32)
    private val createdAt = 1_700_000_000L
    private val groupId = GroupIdentity.derive(creatorPubkey, createdAt)
    private val testKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    /** Byte offsets in the decoded payload (see [InviteLinkCodec] layout). */
    private val versionPos = 0
    private val creatorPos = 1 + 16
    private val expiryPosNoCustomRelays = 1 + 16 + 32 + 8 + 2 + 32 + 1 + 1

    private fun testGroup(
        id: String = groupId,
        name: String = "Trip",
        relays: List<String> = listOf("wss://purplerelay.com", "wss://nos.lol"),
        creator: String = creatorPubkey,
        created: Long = createdAt,
        keyEpoch: Int = 0
    ) = Group(id, name, "", creator, created, listOf(creator), relays, keyEpoch = keyEpoch)

    private fun payloadBytes(link: String): ByteArray = Base64.getUrlDecoder().decode(link.substringAfter("d="))

    private fun linkFrom(data: ByteArray): String =
        "splitfree://join?d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun assertRejected(expectedMessage: String, block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException containing \"$expectedMessage\"")
        } catch (e: IllegalArgumentException) {
            assertTrue("Unexpected message: ${e.message}", e.message.orEmpty().contains(expectedMessage))
        }
    }

    @Test
    fun `encode produces splitfree join link`() {
        val link = InviteLinkCodec.encode(testGroup(), testKey)
        assertTrue(link.startsWith("splitfree://join?d="))
    }

    @Test
    fun `round-trip preserves all fields`() {
        val group = testGroup(name = "Goa Trip 2026")
        val link = InviteLinkCodec.encode(group, testKey)

        val invite = InviteLinkCodec.decode(link)
        assertEquals(group.id, invite.groupId)
        assertEquals("Goa Trip 2026", invite.name)
        assertEquals(testKey, invite.groupKey)
        assertTrue(invite.relays.contains("wss://purplerelay.com"))
        assertTrue(invite.relays.contains("wss://nos.lol"))
        assertTrue(invite.expiry > System.currentTimeMillis() / 1000)
        assertEquals(creatorPubkey, invite.creatorPubkey)
        assertEquals(createdAt, invite.createdAt)
        assertEquals(0, invite.keyEpoch)
    }

    @Test
    fun `round-trip with epoch 3 preserves epoch and key`() {
        val epochKey = Base64.getEncoder().encodeToString(ByteArray(32) { (0x40 + it).toByte() })
        val link = InviteLinkCodec.encode(testGroup(keyEpoch = 3), epochKey)

        val invite = InviteLinkCodec.decode(link)
        assertEquals(3, invite.keyEpoch)
        assertEquals(epochKey, invite.groupKey)
        assertEquals(groupId, invite.groupId)
    }

    @Test
    fun `round-trip preserves maximum 16-bit epoch`() {
        val invite = InviteLinkCodec.decode(InviteLinkCodec.encode(testGroup(keyEpoch = 0xFFFF), testKey))
        assertEquals(0xFFFF, invite.keyEpoch)
    }

    @Test
    fun `link with custom relay includes it in payload`() {
        val relays = listOf("wss://purplerelay.com", "wss://custom.relay.example")
        val link = InviteLinkCodec.encode(testGroup(relays = relays), testKey)
        val invite = InviteLinkCodec.decode(link)
        assertEquals(2, invite.relays.size)
        assertTrue(invite.relays.contains("wss://purplerelay.com"))
        assertTrue(invite.relays.contains("wss://custom.relay.example"))
    }

    @Test
    fun `link with unicode group name`() {
        val link = InviteLinkCodec.encode(testGroup(name = "गोवा ट्रिप 🏖️"), testKey)
        assertEquals("गोवा ट्रिप 🏖️", InviteLinkCodec.decode(link).name)
    }

    @Test
    fun `link fits in QR code, under 400 chars with known relays`() {
        val link = InviteLinkCodec.encode(testGroup(name = "Goa Trip 2026"), testKey)
        assertTrue("Link too long: ${link.length} chars", link.length < 400)
    }

    @Test
    fun `link payload contains no URL-breaking characters`() {
        val link = InviteLinkCodec.encode(testGroup(), testKey)
        val payload = link.substringAfter("d=")
        assertFalse("Payload contains +", payload.contains("+"))
        assertFalse("Payload contains /", payload.contains("/"))
        assertFalse("Payload contains space", payload.contains(" "))
    }

    // --- Creator binding ---

    @Test
    fun `decode rejects link whose creator pubkey was swapped`() {
        val data = payloadBytes(InviteLinkCodec.encode(testGroup(), testKey))
        val forged = otherPubkey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        forged.copyInto(data, destinationOffset = creatorPos)

        assertRejected("does not match its claimed creator") { InviteLinkCodec.decode(linkFrom(data)) }
    }

    @Test
    fun `decode rejects link whose createdAt was altered`() {
        val data = payloadBytes(InviteLinkCodec.encode(testGroup(), testKey))
        // Flip the low byte of createdAt (offset: version + uuid + creatorPub + 7).
        val createdAtLowBytePos = 1 + 16 + 32 + 7
        data[createdAtLowBytePos] = (data[createdAtLowBytePos].toInt() xor 0x01).toByte()

        assertRejected("does not match its claimed creator") { InviteLinkCodec.decode(linkFrom(data)) }
    }

    @Test
    fun `encode rejects group whose id is not derived from its creator`() {
        val mismatched = testGroup(id = "550e8400-e29b-41d4-a716-446655440000")
        assertRejected("does not match its creator") { InviteLinkCodec.encode(mismatched, testKey) }
    }

    @Test
    fun `encode rejects group with empty creator`() {
        assertRejected("creator pubkey") { InviteLinkCodec.encode(testGroup(creator = ""), testKey) }
    }

    // --- Version and bounds ---

    @Test
    fun `decode rejects wrong version byte`() {
        val data = payloadBytes(InviteLinkCodec.encode(testGroup(), testKey))
        data[versionPos] = 0x01

        assertRejected("Unsupported invite link version") { InviteLinkCodec.decode(linkFrom(data)) }
    }

    @Test
    fun `decode rejects expired link`() {
        val data = payloadBytes(InviteLinkCodec.encode(testGroup(), testKey))
        val pastExp = ((System.currentTimeMillis() / 1000 - 3600) and 0xFFFFFFFFL).toInt()
        data[expiryPosNoCustomRelays] = ((pastExp ushr 24) and 0xFF).toByte()
        data[expiryPosNoCustomRelays + 1] = ((pastExp ushr 16) and 0xFF).toByte()
        data[expiryPosNoCustomRelays + 2] = ((pastExp ushr 8) and 0xFF).toByte()
        data[expiryPosNoCustomRelays + 3] = (pastExp and 0xFF).toByte()

        assertRejected("expired") { InviteLinkCodec.decode(linkFrom(data)) }
    }

    @Test
    fun `encode rejects group key that is not 32 bytes`() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))
        assertRejected("32 bytes") { InviteLinkCodec.encode(testGroup(), shortKey) }
    }

    @Test
    fun `encode rejects epoch that does not fit in 16 bits`() {
        assertRejected("epoch") { InviteLinkCodec.encode(testGroup(keyEpoch = 0x10000), testKey) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects truncated payload`() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(10))
        InviteLinkCodec.decode("splitfree://join?d=$payload")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects empty payload`() {
        InviteLinkCodec.decode("splitfree://join?d=")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects missing d parameter`() {
        InviteLinkCodec.decode("splitfree://join?foo=bar")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects custom relay that is not wss`() {
        InviteLinkCodec.decode(InviteLinkCodec.encode(testGroup(relays = listOf("http://evil.example")), testKey))
    }

    // --- Name sanitisation ---

    @Test
    fun `decode strips control characters from name`() {
        val hostile = "Tr\u0000ip\u001F \u007F\u200E\u200F\u202A\u202E\u2066\u2069to\u000AGoa"
        val data = payloadBytes(InviteLinkCodec.encode(testGroup(name = "placeholder"), testKey))
        // Replace the name bytes (everything after expiry) with the hostile name so we exercise decode.
        val header = data.copyOfRange(0, expiryPosNoCustomRelays + 4)
        val tampered = header + hostile.toByteArray(Charsets.UTF_8)

        assertEquals("Trip toGoa", InviteLinkCodec.decode(linkFrom(tampered)).name)
    }

    @Test
    fun `encode strips control characters from name`() {
        val link = InviteLinkCodec.encode(testGroup(name = "Goa\u202Epirt\u0007"), testKey)
        assertEquals("Goapirt", InviteLinkCodec.decode(link).name)
    }

    @Test
    fun `decode truncates name longer than 100 bytes`() {
        val data = payloadBytes(InviteLinkCodec.encode(testGroup(name = "x"), testKey))
        val header = data.copyOfRange(0, expiryPosNoCustomRelays + 4)
        val tampered = header + "A".repeat(250).toByteArray(Charsets.UTF_8)

        val name = InviteLinkCodec.decode(linkFrom(tampered)).name
        assertEquals(100, name.toByteArray(Charsets.UTF_8).size)
        assertEquals("A".repeat(100), name)
    }

    @Test
    fun `truncation does not split a multi-byte character`() {
        // 34 x 3-byte chars = 102 bytes; must truncate to 33 chars (99 bytes), not cut char 34 in half.
        val name = "ग".repeat(34)
        val decoded = InviteLinkCodec.decode(InviteLinkCodec.encode(testGroup(name = name), testKey)).name
        assertEquals("ग".repeat(33), decoded)
        assertFalse(decoded.contains('\uFFFD'))
    }

    @Test
    fun `blank name falls back to default`() {
        val data = payloadBytes(InviteLinkCodec.encode(testGroup(name = "x"), testKey))
        val header = data.copyOfRange(0, expiryPosNoCustomRelays + 4)

        assertEquals("Group", InviteLinkCodec.decode(linkFrom(header)).name)
        assertEquals("Group", InviteLinkCodec.decode(linkFrom(header + "  \u0000 ".toByteArray())).name)
    }
}
