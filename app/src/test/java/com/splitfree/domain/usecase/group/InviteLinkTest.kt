package com.splitfree.domain.usecase.group

import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.util.RelayDefaults
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the version-3 invite link codec (creator-bound group id, raw epoch key, length-prefixed custom
 * relays).
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
    private val customRelayLenPos = 1 + 16 + 32 + 8 + 2 + 32 + 2
    private val expiryPosNoCustomRelays = customRelayLenPos + 1

    /** The longest custom relay URL one link can carry: the region's 255 bytes minus the entry's length byte. */
    private val maximalRelay = "wss://" + "a".repeat(240) + ".example"

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

    /**
     * A well-formed payload (one known relay, no custom relays) whose custom-relay region is replaced by
     * [region], declared as [declaredLen] bytes long.
     */
    private fun payloadWithCustomRegion(region: ByteArray, declaredLen: Int = region.size): ByteArray {
        val data =
            payloadBytes(InviteLinkCodec.encode(testGroup(relays = listOf(RelayDefaults.KNOWN_RELAYS[0])), testKey))
        assertEquals(0, data[customRelayLenPos].toInt())
        val head = data.copyOfRange(0, customRelayLenPos)
        val tail = data.copyOfRange(customRelayLenPos + 1, data.size)
        return head + byteArrayOf(declaredLen.toByte()) + region + tail
    }

    private fun entry(relay: String): ByteArray {
        val bytes = relay.toByteArray(Charsets.UTF_8)
        return byteArrayOf(bytes.size.toByte()) + bytes
    }

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

    // --- Relay bitmap and budget ---

    @Test
    fun `known relays beyond the eighth are bitmap bits, not custom bytes`() {
        val relays = listOf(RelayDefaults.KNOWN_RELAYS[8], RelayDefaults.KNOWN_RELAYS.last())
        val link = InviteLinkCodec.encode(testGroup(relays = relays), testKey)

        assertEquals(0, payloadBytes(link)[customRelayLenPos].toInt())
        assertEquals(relays, InviteLinkCodec.decode(link).relays)
    }

    @Test
    fun `ten known relays round-trip in known order`() {
        val relays = RelayDefaults.KNOWN_RELAYS.take(10)
        val link = InviteLinkCodec.encode(testGroup(relays = relays), testKey)

        assertEquals(0, payloadBytes(link)[customRelayLenPos].toInt())
        assertEquals(relays, InviteLinkCodec.decode(link).relays)
    }

    @Test
    fun `fitsInviteLink caps the relay count at MAX_RELAYS`() {
        assertTrue(InviteLinkCodec.fitsInviteLink(RelayDefaults.KNOWN_RELAYS.take(InviteLinkCodec.MAX_RELAYS)))
        assertFalse(InviteLinkCodec.fitsInviteLink(RelayDefaults.KNOWN_RELAYS.take(InviteLinkCodec.MAX_RELAYS + 1)))
    }

    @Test
    fun `fitsInviteLink caps the custom relays at 255 bytes`() {
        val relay = { label: Char -> "wss://" + label.toString().repeat(120) + ".example" }
        // Two 134-byte URLs, each behind its length byte: 270 bytes.
        assertFalse(InviteLinkCodec.fitsInviteLink(listOf(relay('a'), relay('b'))))
        // One fits on its own, and known relays cost no custom bytes.
        assertTrue(InviteLinkCodec.fitsInviteLink(listOf(relay('a')) + RelayDefaults.KNOWN_RELAYS.take(9)))
    }

    @Test
    fun `encode refuses a relay list that does not fit`() {
        val oversized = listOf("wss://" + "a".repeat(250) + ".example", "wss://b.example")
        assertRejected("do not fit") { InviteLinkCodec.encode(testGroup(relays = oversized), testKey) }
        assertRejected("do not fit") {
            InviteLinkCodec.encode(testGroup(relays = RelayDefaults.KNOWN_RELAYS.take(11)), testKey)
        }
    }

    // --- Custom relay region: [len:1][utf8:len] entries ---

    @Test
    fun `custom relays with URL punctuation round-trip byte-identical alongside known relays`() {
        val custom = listOf(
            "wss://relay.example/room,a",
            "wss://relay.example/?a=1&b=2",
            "wss://relay.example/a%2Fb",
            "wss://relay.example:8443/x",
            "wss://relay.example/TeamA/Room"
        )
        val relays = listOf(RelayDefaults.KNOWN_RELAYS[0]) + custom + listOf(RelayDefaults.KNOWN_RELAYS[11])

        val decoded = InviteLinkCodec.decode(InviteLinkCodec.encode(testGroup(relays = relays), testKey)).relays

        // Known relays come first, in bitmap order; custom relays follow in list order, untouched.
        assertEquals(listOf(RelayDefaults.KNOWN_RELAYS[0], RelayDefaults.KNOWN_RELAYS[11]) + custom, decoded)
    }

    @Test
    fun `two custom relays round-trip in order`() {
        val custom = listOf("wss://b.example/second,first", "wss://a.example")
        val decoded = InviteLinkCodec.decode(InviteLinkCodec.encode(testGroup(relays = custom), testKey)).relays
        assertEquals(custom, decoded)
    }

    @Test
    fun `a 254-byte custom relay is the maximum and a 255-byte one is refused`() {
        assertEquals(254, maximalRelay.toByteArray(Charsets.UTF_8).size)
        val relays = listOf(RelayDefaults.KNOWN_RELAYS[0], maximalRelay)
        assertTrue(InviteLinkCodec.fitsInviteLink(relays))
        assertEquals(relays, InviteLinkCodec.decode(InviteLinkCodec.encode(testGroup(relays = relays), testKey)).relays)

        val oneByteTooLong = "wss://" + "a".repeat(241) + ".example"
        assertEquals(255, oneByteTooLong.toByteArray(Charsets.UTF_8).size)
        assertFalse(InviteLinkCodec.fitsInviteLink(listOf(oneByteTooLong)))
        assertRejected("do not fit") { InviteLinkCodec.encode(testGroup(relays = listOf(oneByteTooLong)), testKey) }
    }

    @Test
    fun `the maximal custom relay leaves no room for a second one`() {
        // 1 + 254 bytes fill the region; even the shortest wss URL beside it overflows 255.
        assertFalse(InviteLinkCodec.fitsInviteLink(listOf(maximalRelay, "wss://a")))
    }

    @Test
    fun `an empty custom relay does not fit`() {
        assertFalse(InviteLinkCodec.fitsInviteLink(listOf(RelayDefaults.KNOWN_RELAYS[0], "")))
    }

    // --- relayFits: the per-relay rule shared with import and group_meta ---

    @Test
    fun `relayFits accepts every known relay`() {
        RelayDefaults.KNOWN_RELAYS.forEach { assertTrue(it, InviteLinkCodec.relayFits(it)) }
    }

    @Test
    fun `relayFits accepts a 254-byte custom relay and refuses a 255-byte one`() {
        assertTrue(InviteLinkCodec.relayFits(maximalRelay))

        val oneByteTooLong = "wss://" + "a".repeat(241) + ".example"
        assertEquals(255, oneByteTooLong.toByteArray(Charsets.UTF_8).size)
        assertFalse(InviteLinkCodec.relayFits(oneByteTooLong))
    }

    @Test
    fun `relayFits refuses an empty relay and a relay that is not wss`() {
        assertFalse(InviteLinkCodec.relayFits(""))
        assertFalse(InviteLinkCodec.relayFits("ws://plain.example"))
        assertFalse(InviteLinkCodec.relayFits("https://not-a-relay.example"))
    }

    @Test
    fun `relayFits measures a multi-byte URL in UTF-8 bytes not chars`() {
        // 6 + 130 x 2 bytes = 266 bytes in 136 chars: too long for the region even though it is short in chars.
        val accented = "wss://" + "é".repeat(130)
        assertEquals(136, accented.length)
        assertEquals(266, accented.toByteArray(Charsets.UTF_8).size)
        assertFalse(InviteLinkCodec.relayFits(accented))

        // 6 + 124 x 2 bytes = 254 bytes: exactly the maximum.
        val maximalAccented = "wss://" + "é".repeat(124)
        assertEquals(254, maximalAccented.toByteArray(Charsets.UTF_8).size)
        assertTrue(InviteLinkCodec.relayFits(maximalAccented))
    }

    @Test
    fun `fitsInviteLink applies relayFits to every entry`() {
        assertFalse(InviteLinkCodec.fitsInviteLink(listOf(RelayDefaults.KNOWN_RELAYS[0], "http://evil.example")))
        assertFalse(InviteLinkCodec.fitsInviteLink(listOf("wss://" + "é".repeat(130))))
    }

    @Test
    fun `decode rejects a custom entry whose length overruns the region`() {
        val data = payloadWithCustomRegion(byteArrayOf(0x10) + "wss://x".toByteArray(Charsets.UTF_8))
        assertRejected("overruns its region") { InviteLinkCodec.decode(linkFrom(data)) }
    }

    @Test
    fun `decode rejects a custom entry of length zero`() {
        assertRejected("empty custom relay") {
            InviteLinkCodec.decode(linkFrom(payloadWithCustomRegion(byteArrayOf(0))))
        }
        assertRejected("empty custom relay") {
            InviteLinkCodec.decode(linkFrom(payloadWithCustomRegion(entry("wss://x") + byteArrayOf(0))))
        }
    }

    @Test
    fun `decode rejects a custom entry that is not a wss URL`() {
        val data = payloadWithCustomRegion(entry("wss://ok.example") + entry("http://evil.example"))
        assertRejected("Invalid relay URL") { InviteLinkCodec.decode(linkFrom(data)) }
    }

    @Test
    fun `decode rejects a custom region that declares more bytes than the payload holds`() {
        val data = payloadWithCustomRegion(entry("wss://x"), declaredLen = 200)
        assertRejected("truncated custom relays") { InviteLinkCodec.decode(linkFrom(data)) }
    }

    @Test
    fun `decode reads a hand-built custom region exactly as encode would write it`() {
        val data = payloadWithCustomRegion(entry("wss://relay.example/room,a") + entry("wss://b.example"))
        assertEquals(
            listOf(RelayDefaults.KNOWN_RELAYS[0], "wss://relay.example/room,a", "wss://b.example"),
            InviteLinkCodec.decode(linkFrom(data)).relays
        )
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
    fun `encode writes version 3`() {
        assertEquals(0x03, payloadBytes(InviteLinkCodec.encode(testGroup(), testKey))[versionPos].toInt())
    }

    @Test
    fun `decode rejects wrong version byte`() {
        val data = payloadBytes(InviteLinkCodec.encode(testGroup(), testKey))
        data[versionPos] = 0x01

        assertRejected("Unsupported invite link version") { InviteLinkCodec.decode(linkFrom(data)) }
    }

    @Test
    fun `decode rejects a version 2 header without any fallback`() {
        val data = payloadBytes(InviteLinkCodec.encode(testGroup(), testKey))
        data[versionPos] = 0x02

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

    @Test
    fun `encode refuses a custom relay that is not wss`() {
        assertRejected("do not fit") {
            InviteLinkCodec.encode(testGroup(relays = listOf("http://evil.example")), testKey)
        }
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
