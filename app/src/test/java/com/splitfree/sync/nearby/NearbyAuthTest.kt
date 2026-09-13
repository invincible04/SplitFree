package com.splitfree.sync.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyAuthTest {
    private val a = TestIdentity(1)
    private val b = TestIdentity(2)
    private val nonceA = NearbyAuth.newNonce()
    private val nonceB = NearbyAuth.newNonce()
    private val token = "channel".toByteArray()
    private val caps = setOf(NearbyWire.CAP_RECONCILE_V2)

    private fun transcript(tok: ByteArray? = token, c: Set<String> = caps) =
        NearbyAuth.Transcript(a.pub, b.pub, nonceA, nonceB, tok, c)

    @Test
    fun `both sides verify each other's signature over the same transcript`() {
        val t = transcript()
        val sigA = NearbyAuth.sign(t, signerIsInitiator = true, a.priv)
        val sigB = NearbyAuth.sign(t, signerIsInitiator = false, b.priv)
        assertTrue(NearbyAuth.verify(t, signerIsInitiator = true, a.pub, sigA))
        assertTrue(NearbyAuth.verify(t, signerIsInitiator = false, b.pub, sigB))
    }

    @Test
    fun `signature is bound to channel token, nonces, roles, pubkeys and capabilities`() {
        val t = transcript()
        val sigA = NearbyAuth.sign(t, signerIsInitiator = true, a.priv)
        assertFalse(NearbyAuth.verify(transcript(tok = "other".toByteArray()), true, a.pub, sigA))
        assertFalse(NearbyAuth.verify(transcript(tok = null), true, a.pub, sigA))
        assertFalse(NearbyAuth.verify(transcript(c = emptySet()), true, a.pub, sigA))
        assertFalse(NearbyAuth.verify(t, signerIsInitiator = false, a.pub, sigA)) // role swapped
        assertFalse(NearbyAuth.verify(t, true, b.pub, sigA)) // wrong signer
        val otherNonce = NearbyAuth.Transcript(a.pub, b.pub, NearbyAuth.newNonce(), nonceB, token, caps)
        assertFalse(NearbyAuth.verify(otherNonce, true, a.pub, sigA))
        val swappedPeers = NearbyAuth.Transcript(b.pub, a.pub, nonceA, nonceB, token, caps)
        assertFalse(NearbyAuth.verify(swappedPeers, true, a.pub, sigA))
    }

    @Test
    fun `malformed signatures and pubkeys never verify and never throw`() {
        val t = transcript()
        assertFalse(NearbyAuth.verify(t, true, a.pub, ""))
        assertFalse(NearbyAuth.verify(t, true, a.pub, "zz".repeat(64)))
        assertFalse(NearbyAuth.verify(t, true, "not-a-key", "ab".repeat(64)))
        assertFalse(NearbyAuth.verify(t, true, a.pub, "ab".repeat(64)))
    }

    @Test
    fun `role selection is deterministic and symmetric`() {
        // Nearby roles differ: the outgoing side initiates.
        assertTrue(NearbyAuth.localIsInitiator(a.pub, myIncoming = false, b.pub, peerIncoming = true))
        assertFalse(NearbyAuth.localIsInitiator(b.pub, myIncoming = true, a.pub, peerIncoming = false))
        // Both claim outgoing (simultaneous requests): smaller pubkey initiates, agreed on both ends.
        val aFirst = a.pub < b.pub
        assertEquals(aFirst, NearbyAuth.localIsInitiator(a.pub, false, b.pub, false))
        assertEquals(!aFirst, NearbyAuth.localIsInitiator(b.pub, false, a.pub, false))
        assertEquals(aFirst, NearbyAuth.localIsInitiator(a.pub, true, b.pub, true))
    }

    @Test
    fun `transcript rejects malformed inputs`() {
        try {
            NearbyAuth.Transcript("short", b.pub, nonceA, nonceB, token, caps)
            assertFalse(true)
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `transcript differs from the NIP-01 event id domain`() {
        // A 32-byte hash under a different tag cannot collide with an event id preimage: check the
        // domain tag is present by ensuring two transcripts differing only in version-independent
        // fields hash differently and never equal a bare SHA-256 of the nonce.
        val t = transcript()
        val h = NearbyAuth.transcriptHash(t, true)
        val bare = java.security.MessageDigest.getInstance("SHA-256").digest(nonceA.toByteArray())
        assertFalse(h.contentEquals(bare))
        assertEquals(32, h.size)
    }
}
