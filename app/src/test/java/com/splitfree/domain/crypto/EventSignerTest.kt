package com.splitfree.domain.crypto

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for EventSigner — tag structure, NIP-09 deletion, signing correctness.
 * Cannot test with real IdentityManager (needs Android context), so we test
 * the NostrEvent-level behavior that EventSigner delegates to.
 */
class EventSignerTest {

    private val privKey = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".hexToBytes()
    private val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)

    // --- Kind 30078 addressable event tag structure ---

    @Test
    fun `kind 30078 event with unique d-tag per expense`() {
        val groupId = "test-group-123"
        val expenseUuid = "exp-uuid-456"
        val dTagValue = "$groupId:$expenseUuid"

        val event = NostrEvent(
            pubkey = pubHex,
            createdAt = System.currentTimeMillis() / 1000,
            kind = 30078,
            tags = listOf(
                listOf("d", dTagValue),
                listOf("g", groupId),
                listOf("t", "expense"),
                listOf("e", expenseUuid)
            ),
            content = "encrypted"
        ).sign(privKey)

        assertTrue(event.verify())
        // d-tag must be unique per event (groupId:uuid)
        val dTag = event.tags.find { it[0] == "d" }!!
        assertTrue("d-tag must contain groupId", dTag[1].startsWith(groupId))
        assertTrue("d-tag must contain expenseUuid", dTag[1].contains(expenseUuid))
        // g-tag for group filtering
        assertEquals(groupId, event.tags.find { it[0] == "g" }!![1])
        // t-tag for event type
        assertEquals("expense", event.tags.find { it[0] == "t" }!![1])
        // e-tag for expense UUID
        assertEquals(expenseUuid, event.tags.find { it[0] == "e" }!![1])
    }

    @Test
    fun `different expenses in same group get different d-tags`() {
        val groupId = "group-1"
        val d1 = "$groupId:expense-1"
        val d2 = "$groupId:expense-2"
        assertNotEquals("d-tags must differ per expense", d1, d2)
    }

    // --- NIP-09 Deletion Event ---

    @Test
    fun `kind 5 deletion event has correct structure`() {
        val eventIds = listOf("abc123", "def456", "ghi789")
        val event = NostrEvent(
            pubkey = pubHex,
            createdAt = System.currentTimeMillis() / 1000,
            kind = 5,
            tags = eventIds.map { listOf("e", it) },
            content = "spam"
        ).sign(privKey)

        assertTrue(event.verify())
        assertEquals(5, event.kind)
        assertEquals(3, event.tags.size)
        assertEquals("spam", event.content)
        // Each tag references an event to delete
        event.tags.forEachIndexed { i, tag ->
            assertEquals("e", tag[0])
            assertEquals(eventIds[i], tag[1])
        }
    }

    @Test
    fun `kind 5 deletion with empty reason`() {
        val event = NostrEvent(
            pubkey = pubHex,
            createdAt = System.currentTimeMillis() / 1000,
            kind = 5,
            tags = listOf(listOf("e", "target-event-id")),
            content = ""
        ).sign(privKey)

        assertTrue(event.verify())
        assertEquals("", event.content)
    }

    // --- NIP-42 AUTH Event ---

    @Test
    fun `kind 22242 AUTH event has challenge and relay tags`() {
        val challenge = "random-challenge-string"
        val relayUrl = "wss://relay.damus.io"
        val event = NostrEvent(
            pubkey = pubHex,
            createdAt = System.currentTimeMillis() / 1000,
            kind = 22242,
            tags = listOf(listOf("challenge", challenge), listOf("relay", relayUrl)),
            content = ""
        ).sign(privKey)

        assertTrue(event.verify())
        assertEquals(22242, event.kind)
        assertEquals(challenge, event.tags.find { it[0] == "challenge" }!![1])
        assertEquals(relayUrl, event.tags.find { it[0] == "relay" }!![1])
        assertEquals("", event.content)
    }

    // --- Private key zeroing pattern ---

    @Test
    fun `private key can be zeroed after signing`() {
        val key = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".hexToBytes()
        val pub = NostrEvent.pubkeyFromPrivkey(key)
        val event = NostrEvent(pubkey = pub, createdAt = 1, kind = 1, content = "test").sign(key)
        key.fill(0) // zero the key
        // Event should still verify (signature is self-contained)
        assertTrue(event.verify())
        // Key is zeroed
        assertTrue(key.all { it == 0.toByte() })
    }
}
