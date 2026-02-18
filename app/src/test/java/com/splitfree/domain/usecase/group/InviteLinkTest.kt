package com.splitfree.domain.usecase.group

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for invite link v2 compact format and v1 legacy parsing.
 * Uses java.util.Base64 instead of android.util.Base64 (same encoding).
 */
class InviteLinkTest {
    companion object {
        // Must match JoinGroupUseCase.KNOWN_RELAYS exactly
        private val KNOWN_RELAYS =
            listOf(
                "wss://relay.damus.io",
                "wss://nos.lol",
                "wss://relay.primal.net",
                "wss://relay.snort.social",
                "wss://relay.nostr.net"
            )
    }

    // --- v2 compact format helpers (mirrors production code using java.util.Base64) ---

    private fun createCompactLink(
        groupId: String,
        groupKey: String,
        relays: List<String>,
        name: String,
        expiry: Long = System.currentTimeMillis() / 1000 + 7 * 86400
    ): String {
        val uuid = UUID.fromString(groupId)
        val keyBytes = groupKey.toByteArray(Charsets.UTF_8)
        val nameBytes = name.toByteArray(Charsets.UTF_8)

        var relayBitmap = 0
        val customRelays = mutableListOf<String>()
        for (relay in relays) {
            val idx = KNOWN_RELAYS.indexOf(relay)
            if (idx >= 0) {
                relayBitmap = relayBitmap or (1 shl idx)
            } else {
                customRelays.add(relay)
            }
        }
        val customRelayBytes = customRelays.joinToString(",").toByteArray(Charsets.UTF_8)

        val buf = ByteArrayOutputStream()
        buf.write(2)
        for (shift in listOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            buf.write((uuid.mostSignificantBits ushr shift).toInt() and 0xFF)
        }
        for (shift in listOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            buf.write((uuid.leastSignificantBits ushr shift).toInt() and 0xFF)
        }
        buf.write(keyBytes.size)
        buf.write(keyBytes)
        buf.write(relayBitmap and 0xFF)
        buf.write(customRelayBytes.size)
        if (customRelayBytes.isNotEmpty()) buf.write(customRelayBytes)
        val expInt = (expiry and 0xFFFFFFFFL).toInt()
        buf.write((expInt ushr 24) and 0xFF)
        buf.write((expInt ushr 16) and 0xFF)
        buf.write((expInt ushr 8) and 0xFF)
        buf.write(expInt and 0xFF)
        buf.write(nameBytes, 0, nameBytes.size.coerceAtMost(100))

        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(buf.toByteArray())
        return "splitfree://join?d=$payload"
    }

    private fun decodeCompactLink(fragment: String): Map<String, String> {
        val data = Base64.getUrlDecoder().decode(fragment)
        if (data.isEmpty() || data[0].toInt() != 2) return emptyMap()
        var pos = 1

        if (data.size < pos + 16) return emptyMap()
        var msb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (data[pos + i].toLong() and 0xFF)
        var lsb = 0L
        for (i in 0 until 8) lsb = (lsb shl 8) or (data[pos + 8 + i].toLong() and 0xFF)
        val groupId = UUID(msb, lsb).toString()
        pos += 16

        if (data.size < pos + 1) return emptyMap()
        val keyLen = data[pos].toInt() and 0xFF
        pos++
        if (data.size < pos + keyLen) return emptyMap()
        val groupKey = String(data, pos, keyLen, Charsets.UTF_8)
        pos += keyLen

        if (data.size < pos + 1) return emptyMap()
        val bitmap = data[pos].toInt() and 0xFF
        pos++
        val relays = mutableListOf<String>()
        for (i in KNOWN_RELAYS.indices) {
            if (bitmap and (1 shl i) != 0) relays.add(KNOWN_RELAYS[i])
        }

        if (data.size < pos + 1) return emptyMap()
        val customLen = data[pos].toInt() and 0xFF
        pos++
        if (customLen > 0 && data.size >= pos + customLen) {
            relays.addAll(String(data, pos, customLen, Charsets.UTF_8).split(",").filter { it.isNotBlank() })
            pos += customLen
        }

        if (data.size < pos + 4) return emptyMap()
        val exp =
            ((data[pos].toLong() and 0xFF) shl 24) or
                ((data[pos + 1].toLong() and 0xFF) shl 16) or
                ((data[pos + 2].toLong() and 0xFF) shl 8) or
                (data[pos + 3].toLong() and 0xFF)
        pos += 4

        val name = if (pos < data.size) String(data, pos, data.size - pos, Charsets.UTF_8) else "Group"

        return mapOf(
            "groupId" to groupId,
            "groupKey" to groupKey,
            "relays" to relays.joinToString(","),
            "name" to name,
            "exp" to exp.toString()
        )
    }

    // v1 legacy helper
    private fun parseV1Uri(uri: String): Map<String, String> {
        val query = uri.substringAfter("?", "")
        return query.split("&").filter { it.contains("=") }.associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }
    }

    // --- v2 round-trip tests ---

    @Test
    fun `v2 invite link round-trip preserves all fields`() {
        val groupId = "56a0833d-a77c-418f-ac2d-64150216af31"
        val groupKey = "oOptOxcjIjJ4avhfwNc8gkFutNgKjGjmr0RtG4XzA0c="
        val relays = listOf("wss://relay.damus.io", "wss://nos.lol")
        val name = "Goa Trip 2026"
        val exp = System.currentTimeMillis() / 1000 + 7 * 86400

        val link = createCompactLink(groupId, groupKey, relays, name, exp)
        assertTrue(link.startsWith("splitfree://join?d="))

        val fragment = link.substringAfter("d=")
        val decoded = decodeCompactLink(fragment)
        assertEquals(groupId, decoded["groupId"])
        assertEquals(groupKey, decoded["groupKey"])
        assertEquals("wss://relay.damus.io,wss://nos.lol", decoded["relays"])
        assertEquals(name, decoded["name"])
        assertEquals(exp.toString(), decoded["exp"])
    }

    @Test
    fun `v2 link with all 5 known relays uses bitmap only`() {
        val link =
            createCompactLink(
                "56a0833d-a77c-418f-ac2d-64150216af31",
                "key1",
                KNOWN_RELAYS,
                "Test"
            )
        val fragment = link.substringAfter("d=")
        // No relay URLs in the link — all encoded as bitmap
        assertFalse(link.contains("wss://"))
        val decoded = decodeCompactLink(fragment)
        assertEquals(KNOWN_RELAYS.joinToString(","), decoded["relays"])
    }

    @Test
    fun `v2 link with custom relay includes it in payload`() {
        val relays = listOf("wss://relay.damus.io", "wss://custom.relay.example")
        val link = createCompactLink("56a0833d-a77c-418f-ac2d-64150216af31", "key1", relays, "Test")
        val decoded = decodeCompactLink(link.substringAfter("d="))
        val parsedRelays = decoded["relays"]!!.split(",")
        assertEquals(2, parsedRelays.size)
        assertTrue(parsedRelays.contains("wss://relay.damus.io"))
        assertTrue(parsedRelays.contains("wss://custom.relay.example"))
    }

    @Test
    fun `v2 link with unicode group name`() {
        val link =
            createCompactLink("56a0833d-a77c-418f-ac2d-64150216af31", "key1", listOf("wss://nos.lol"), "गोवा ट्रिप 🏖️")
        val decoded = decodeCompactLink(link.substringAfter("d="))
        assertEquals("गोवा ट्रिप 🏖️", decoded["name"])
    }

    @Test
    fun `v2 link is compact — under 150 chars with known relays`() {
        val link =
            createCompactLink(
                "56a0833d-a77c-418f-ac2d-64150216af31",
                "oOptOxcjIjJ4avhfwNc8gkFutNgKjGjmr0RtG4XzA0c=",
                KNOWN_RELAYS,
                "Goa Trip 2026"
            )
        assertTrue("Link too long: ${link.length} chars", link.length < 150)
    }

    @Test
    fun `v2 link fragment contains no URL-breaking characters`() {
        val link = createCompactLink("56a0833d-a77c-418f-ac2d-64150216af31", "key1", KNOWN_RELAYS, "Test")
        val fragment = link.substringAfter("d=")
        assertFalse("Fragment contains +", fragment.contains("+"))
        assertFalse("Fragment contains /", fragment.contains("/"))
        assertFalse("Fragment contains space", fragment.contains(" "))
    }

    @Test
    fun `v2 decode rejects version 1 payload`() {
        val data = byteArrayOf(1, 0, 0) // version 1
        val fragment = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
        val result = decodeCompactLink(fragment)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `v2 decode rejects truncated payload`() {
        val data = byteArrayOf(2, 0, 0, 0) // version 2 but too short
        val fragment = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
        val result = decodeCompactLink(fragment)
        assertTrue(result.isEmpty())
    }

    // --- v1 legacy format tests ---

    @Test
    fun `v1 legacy link round-trip preserves fields`() {
        val g = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "56a0833d-a77c-418f-ac2d-64150216af31".toByteArray()
        )
        val k = Base64.getUrlEncoder().withoutPadding().encodeToString("key123".toByteArray())
        val uri = "splitfree://join?g=$g&k=$k&r=wss://relay.damus.io,wss://nos.lol&n=Test+Group&exp=1771623333"
        val params = parseV1Uri(uri)
        assertEquals(g, params["g"])
        assertEquals(k, params["k"])
        assertEquals("wss://relay.damus.io,wss://nos.lol", params["r"])
        assertEquals("Test+Group", params["n"])
        assertEquals("1771623333", params["exp"])
    }

    @Test
    fun `v1 parseUri handles empty query`() {
        val params = parseV1Uri("splitfree://join?")
        assertTrue(params.isEmpty())
    }

    @Test
    fun `v1 parseUri handles value with equals sign`() {
        val params = parseV1Uri("splitfree://join?g=abc==&k=def==&r=wss://r.io&n=Test")
        assertEquals("abc==", params["g"])
        assertEquals("def==", params["k"])
    }

    @Test
    fun `group key survives base64 round-trip`() {
        val originalKey = "oOptOxcjIjJ4avhfwNc8gkFutNgKjGjmr0RtG4XzA0c="
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(originalKey.toByteArray())
        val decoded = String(Base64.getUrlDecoder().decode(encoded))
        assertEquals(originalKey, decoded)
    }

    @Test
    fun `group id survives base64 round-trip`() {
        val id = "56a0833d-a77c-418f-ac2d-64150216af31"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray())
        val decoded = String(Base64.getUrlDecoder().decode(encoded))
        assertEquals(id, decoded)
    }
}
