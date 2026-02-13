package com.splitfree.domain.usecase

import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * Tests for invite link creation and parsing logic.
 * Uses java.util.Base64 instead of android.util.Base64 (same encoding).
 */
class InviteLinkTest {
    // Mirror the createInviteLink logic using java.util.Base64
    private fun createInviteLink(
        groupId: String,
        groupKey: String,
        relays: List<String>,
        name: String,
    ): String {
        val g = Base64.getUrlEncoder().withoutPadding().encodeToString(groupId.toByteArray())
        val k = Base64.getUrlEncoder().withoutPadding().encodeToString(groupKey.toByteArray())
        val r = relays.joinToString(",")
        val n = URLEncoder.encode(name, "UTF-8")
        return "splitfree://join?g=$g&k=$k&r=$r&n=$n"
    }

    // Mirror the parseUri logic
    private fun parseUri(uri: String): Map<String, String> {
        val query = uri.substringAfter("?", "")
        return query
            .split("&")
            .filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                k to v
            }
    }

    private fun decodeParam(value: String): String = String(Base64.getUrlDecoder().decode(value))

    // --- Round-trip tests ---

    @Test
    fun `invite link round-trip preserves all fields`() {
        val link =
            createInviteLink(
                groupId = "56a0833d-a77c-418f-ac2d-64150216af31",
                groupKey = "oOptOxcjIjJ4avhfwNc8gkFutNgKjGjmr0RtG4XzA0c=",
                relays = listOf("wss://relay.damus.io", "wss://nos.lol"),
                name = "Goa Trip 2026",
            )

        assertTrue(link.startsWith("splitfree://join?"))

        val params = parseUri(link)
        assertEquals("56a0833d-a77c-418f-ac2d-64150216af31", decodeParam(params["g"]!!))
        assertEquals("oOptOxcjIjJ4avhfwNc8gkFutNgKjGjmr0RtG4XzA0c=", decodeParam(params["k"]!!))
        assertEquals("wss://relay.damus.io,wss://nos.lol", params["r"])
        assertEquals("Goa Trip 2026", URLDecoder.decode(params["n"], "UTF-8"))
    }

    @Test
    fun `invite link with unicode group name`() {
        val link = createInviteLink("g1", "key1", listOf("wss://r.io"), "गोवा ट्रिप 🏖️")
        val params = parseUri(link)
        assertEquals("गोवा ट्रिप 🏖️", URLDecoder.decode(params["n"], "UTF-8"))
    }

    @Test
    fun `invite link with 5 relays`() {
        val relays =
            listOf(
                "wss://relay.damus.io",
                "wss://nos.lol",
                "wss://relay.nostr.band",
                "wss://relay.snort.social",
                "wss://nostr.wine",
            )
        val link = createInviteLink("g1", "key1", relays, "Test")
        val params = parseUri(link)
        val parsed = params["r"]!!.split(",")
        assertEquals(5, parsed.size)
        assertEquals(relays, parsed)
    }

    @Test
    fun `invite link with special characters in name`() {
        val link = createInviteLink("g1", "key1", listOf("wss://r.io"), "Alice & Bob's Trip (2026)")
        val params = parseUri(link)
        assertEquals("Alice & Bob's Trip (2026)", URLDecoder.decode(params["n"], "UTF-8"))
    }

    // --- Parsing edge cases ---

    @Test
    fun `parseUri handles empty query`() {
        val params = parseUri("splitfree://join?")
        assertTrue(params.isEmpty())
    }

    @Test
    fun `parseUri handles missing name`() {
        val params = parseUri("splitfree://join?g=abc&k=def&r=wss://r.io")
        assertEquals("abc", params["g"])
        assertEquals("def", params["k"])
        assertNull(params["n"])
    }

    @Test
    fun `parseUri handles value with equals sign`() {
        // Base64 can contain = padding
        val params = parseUri("splitfree://join?g=abc==&k=def==&r=wss://r.io&n=Test")
        assertEquals("abc==", params["g"])
        assertEquals("def==", params["k"])
    }

    // --- Validation ---

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

    @Test
    fun `relay URLs are not base64 encoded`() {
        val link = createInviteLink("g1", "k1", listOf("wss://relay.damus.io"), "T")
        // Relays should be plain text in the link, not base64
        assertTrue(link.contains("wss://relay.damus.io"))
    }
}
