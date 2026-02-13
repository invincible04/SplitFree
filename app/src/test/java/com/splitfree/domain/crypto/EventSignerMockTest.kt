package com.splitfree.domain.crypto

import io.mockk.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class EventSignerMockTest {

    private val identity = mockk<IdentityManager>()
    private lateinit var signer: EventSigner

    // Valid secp256k1 private key
    private val privKey = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".hexToBytes()
    private val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)

    @Before
    fun setup() {
        every { identity.getPrivateKeyBytes() } returns privKey.copyOf()
        every { identity.getPublicKeyHex() } returns pubHex
        signer = EventSigner(identity)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

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
        val eTag = event.tags.find { it[0] == "e" }
        val dTag = event.tags.find { it[0] == "d" }
        assertEquals("group1", gTag!![1])
        assertEquals("expense", tTag!![1])
        assertEquals("uuid1", eTag!![1])
        assertTrue(dTag!![1].startsWith("group1:uuid1"))
    }

    @Test
    fun `createSignedEvent without expenseUuid has random d-tag`() {
        val event = signer.createSignedEvent("group1", "expense", "enc")
        val dTag = event.tags.find { it[0] == "d" }!!
        assertTrue(dTag[1].startsWith("group1:"))
        assertNull(event.tags.find { it[0] == "e" })
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
}
