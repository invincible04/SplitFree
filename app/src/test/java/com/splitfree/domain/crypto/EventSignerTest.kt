package com.splitfree.domain.crypto

import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.hexToBytes
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for EventSigner: tag structure, NIP-09 deletion, signing correctness.
 * Cannot test with real IdentityManager (needs Android context), so we test
 * the NostrEvent-level behavior that EventSigner delegates to.
 */
class EventSignerTest {
    private val privKey = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".hexToBytes()
    private val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
    private val identity = mockk<IdentityContract>()
    private lateinit var signer: EventSigner

    @Before
    fun setup() {
        every { identity.getPrivateKeyBytes() } answers { privKey.copyOf() }
        every { identity.getPublicKeyHex() } returns pubHex
        signer = EventSigner(identity)
    }

    @After
    fun teardown() = unmockkAll()

    // --- Kind 30078 addressable event tag structure ---

    @Test
    fun `kind 30078 event with unique d-tag per expense`() {
        val groupId = "test-group-123"
        val expenseUuid = "exp-uuid-456"
        val dTagValue = "$groupId:expense:command-1"

        val event =
            NostrEvent(
                pubkey = pubHex,
                createdAt = System.currentTimeMillis() / 1000,
                kind = 30078,
                tags =
                listOf(
                    listOf("d", dTagValue),
                    listOf("g", groupId),
                    listOf("t", "expense"),
                    listOf("x", expenseUuid)
                ),
                content = "encrypted"
            ).sign(privKey)

        assertTrue(event.verify())
        // d-tag is the command's relay address (groupId:type:commandId)
        val dTag = event.tags.find { it[0] == "d" }!!
        assertTrue("d-tag must contain groupId", dTag[1].startsWith(groupId))
        assertEquals(EventSigner.relayAddress(groupId, "expense", "command-1"), dTag[1])
        // g-tag for group filtering
        assertEquals(groupId, event.tags.find { it[0] == "g" }!![1])
        // t-tag for event type
        assertEquals("expense", event.tags.find { it[0] == "t" }!![1])
        // x-tag for expense UUID
        assertEquals(expenseUuid, event.tags.find { it[0] == "x" }!![1])
    }

    @Test
    fun `different commands in same group get different d-tags`() {
        val groupId = "group-1"
        val d1 = EventSigner.relayAddress(groupId, "expense", "command-1")
        val d2 = EventSigner.relayAddress(groupId, "expense", "command-2")
        assertNotEquals("d-tags must differ per command", d1, d2)
        assertNotEquals(
            "d-tags must differ per type for one command",
            d1,
            EventSigner.relayAddress(groupId, "expense_correction", "command-1")
        )
    }

    // --- NIP-09 Deletion Event ---

    @Test
    fun `kind 5 deletion event has correct structure`() {
        val eventIds = listOf("abc123", "def456", "ghi789")
        val event =
            NostrEvent(
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
        val event =
            NostrEvent(
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
        val event =
            NostrEvent(
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

    // --- Mock-based tests ---
    @Test
    fun `createSignedEvent produces valid signed event`() {
        val event = signer.createSignedEvent("group1", "expense", "encrypted-content")
        assertTrue(event.verify())
        assertEquals(30078, event.kind)
        assertEquals(pubHex, event.pubkey)
        assertEquals("encrypted-content", event.content)
    }

    @Test
    fun `createSignedEvent includes correct tags`() {
        val event = signer.createSignedEvent("group1", "expense", "enc", "uuid1")
        val gTag = event.tags.find { it[0] == "g" }
        val tTag = event.tags.find { it[0] == "t" }
        val eTag = event.tags.find { it[0] == "x" }
        val dTag = event.tags.find { it[0] == "d" }
        assertEquals("group1", gTag!![1])
        assertEquals("expense", tTag!![1])
        assertEquals("uuid1", eTag!![1])
        // The address never embeds the logical expense id: revisions of one expense need distinct addresses.
        assertTrue(dTag!![1].startsWith("group1:expense:"))
        assertFalse(dTag[1].contains("uuid1"))
    }

    @Test
    fun `createSignedEvent without expenseUuid has random d-tag`() {
        val event = signer.createSignedEvent("group1", "expense", "enc")
        val dTag = event.tags.find { it[0] == "d" }!!
        assertTrue(dTag[1].startsWith("group1:expense:"))
        assertNull(event.tags.find { it[0] == "x" })
    }

    @Test
    fun `two createSignedEvent calls for one expense never share an address`() {
        val first = signer.createSignedEvent("group1", "expense", "enc", "uuid1")
        val second = signer.createSignedEvent("group1", "expense", "enc", "uuid1")
        assertNotEquals(first.tags.single { it[0] == "d" }, second.tags.single { it[0] == "d" })
    }

    @Test
    fun `createSignedEvent omits the p tag by default`() {
        val event = signer.createSignedEvent("group1", "expense", "enc", "uuid1")
        assertNull(event.tags.find { it[0] == "p" })
    }

    @Test
    fun `createSignedEvent adds a p tag for the recipient and still verifies`() {
        val recipient = "cd".repeat(32)
        val event = signer.createSignedEvent("group1", "key_rotation", "enc", recipientPubkey = recipient)
        assertTrue(event.verify())
        assertEquals(listOf("p", recipient), event.tags.find { it[0] == "p" })
        // Only one p tag, and the other tags are unchanged.
        assertEquals(1, event.tags.count { it[0] == "p" })
        assertEquals("group1", event.tags.find { it[0] == "g" }!![1])
        assertEquals("key_rotation", event.tags.find { it[0] == "t" }!![1])
        assertNull(event.tags.find { it[0] == "x" })
    }

    @Test
    fun `verify delegates to NostrEvent verify`() {
        val event = signer.createSignedEvent("g", "t", "c")
        assertTrue(signer.verify(event))
    }

    @Test
    fun `verify returns false for tampered event`() {
        val event = signer.createSignedEvent("g", "t", "c")
        val tampered = event.copy(content = "tampered")
        assertFalse(signer.verify(tampered))
    }

    @Test
    fun `createAuthEvent produces kind 22242`() {
        val event = signer.createAuthEvent("challenge123", "wss://relay.test")
        assertTrue(event.verify())
        assertEquals(22242, event.kind)
        assertEquals("challenge123", event.tags.find { it[0] == "challenge" }!![1])
        assertEquals("wss://relay.test", event.tags.find { it[0] == "relay" }!![1])
    }

    @Test
    fun `createDeletionEvent produces kind 5`() {
        val event = signer.createDeletionEvent(listOf("evt1", "evt2"), "spam")
        assertTrue(event.verify())
        assertEquals(5, event.kind)
        assertEquals("spam", event.content)
        assertEquals(2, event.tags.size)
        assertEquals("evt1", event.tags[0][1])
        assertEquals("evt2", event.tags[1][1])
    }

    @Test
    fun `createDeletionEvent with empty reason`() {
        val event = signer.createDeletionEvent(listOf("evt1"))
        assertEquals("", event.content)
    }

    // --- Immutable relay address per command ---

    @Test
    fun `original and correction publish to distinct relay addresses`() {
        val original = signer.createSignedEvent("group1", "expense", "original", "expense-1")
        val corrected = signer.createSignedEvent("group1", "expense_correction", "corrected", "expense-1")
        assertTrue(original.verify())
        assertTrue(corrected.verify())
        assertEquals(original.pubkey, corrected.pubkey)
        assertEquals(30078, original.kind)
        assertEquals(original.kind, corrected.kind)
        // Same author, kind and logical expense, yet the addressable slot differs: neither evicts the other.
        assertNotEquals(original.tags.single { it[0] == "d" }, corrected.tags.single { it[0] == "d" })
        assertEquals(listOf("x", "expense-1"), original.tags.single { it[0] == "x" })
        assertEquals(listOf("x", "expense-1"), corrected.tags.single { it[0] == "x" })
        assertNotEquals(original.id, corrected.id)
    }

    @Test
    fun `every revision and the deletion of one expense have unique addresses`() {
        val events = listOf("expense", "expense_correction", "expense_correction", "expense_delete").map {
            signer.createSignedEvent("group1", it, "enc", "uuid1")
        }
        assertEquals(4, events.map { it.tags.single { tag -> tag.first() == "d" } }.distinct().size)
        assertTrue(events.all { it.tags.single { tag -> tag.first() == "x" } == listOf("x", "uuid1") })
        assertTrue(events.all { it.verify() })
    }

    @Test
    fun `a command id gives a stable address and a stable event id for the same created_at`() {
        val first = signer.createSignedCommandEvent("group1", "expense_correction", "enc", "uuid1", "command1", 1000)
        val retry = signer.createSignedCommandEvent("group1", "expense_correction", "enc", "uuid1", "command1", 1000)
        assertEquals(first.id, retry.id)
        assertEquals(listOf("d", "group1:expense_correction:command1"), first.tags.first())
        assertEquals(1000L, first.createdAt)
        assertTrue(first.verify() && retry.verify())
    }

    @Test
    fun `a command event carries the recipient tag when given one`() {
        val recipient = "cd".repeat(32)
        val event = signer.createSignedCommandEvent(
            "group1",
            "key_rotation",
            "enc",
            commandId = "rotation-1",
            recipientPubkey = recipient
        )
        assertEquals(listOf("p", recipient), event.tags.single { it[0] == "p" })
        assertEquals(listOf("d", "group1:key_rotation:rotation-1"), event.tags.first())
    }

    @Test
    fun `createSignedEvent honours an explicit created_at`() {
        val event = signer.createSignedEvent("group1", "group_meta", "enc", createdAt = 4242)
        assertEquals(4242L, event.createdAt)
        assertTrue(event.verify())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank command id is refused`() {
        signer.createSignedCommandEvent("group1", "expense", "enc", commandId = " ")
    }
}
