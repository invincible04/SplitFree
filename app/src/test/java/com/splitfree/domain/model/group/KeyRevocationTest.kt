package com.splitfree.domain.model.group

import com.splitfree.domain.crypto.NostrEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRevocationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val groupId = "group-1"
    private val oldKey = ByteArray(32) { 1 }
    private val old = NostrEvent.pubkeyFromPrivkey(oldKey)
    private val newKey = ByteArray(32) { 2 }
    private val new = NostrEvent.pubkeyFromPrivkey(newKey)

    @Test
    fun `only the retired identity may author its own revocation`() {
        val revocation = KeyRevocation(old, new)
        assertTrue(revocation.isAuthorizedBy(old))
        assertFalse(revocation.isAuthorizedBy(new))
        assertFalse(KeyRevocation(old, old).isAuthorizedBy(old))
        assertFalse(KeyRevocation(old, "not-a-key").isAuthorizedBy(old))
        assertTrue(KeyRevocation(old, "").isAuthorizedBy(old))
    }

    @Test
    fun `a proof signed by the successor's key verifies for exactly that group and pair`() {
        val proof = KeyRevocation.proveSuccessor(groupId, old, new, newKey)
        val revocation = KeyRevocation(old, new, "Key compromised", successorProof = proof)

        assertTrue(revocation.provesSuccessor(groupId))
        assertFalse(revocation.provesSuccessor("group-2"))
        assertFalse(revocation.copy(oldPubkey = new, newPubkey = old).provesSuccessor(groupId))
    }

    @Test
    fun `a proof signed by any other key, or no proof, does not prove the successor`() {
        val byOldKey = KeyRevocation.proveSuccessor(groupId, old, new, oldKey)
        assertFalse(KeyRevocation(old, new, successorProof = byOldKey).provesSuccessor(groupId))
        assertFalse(KeyRevocation(old, new).provesSuccessor(groupId))
        assertFalse(KeyRevocation(old, new, successorProof = "zz").provesSuccessor(groupId))
        assertFalse(KeyRevocation(old, "", successorProof = byOldKey).provesSuccessor(groupId))
    }

    @Test
    fun `the proof travels in the payload and a legacy payload without it still decodes`() {
        val proof = KeyRevocation.proveSuccessor(groupId, old, new, newKey)
        val encoded = json.encodeToString(KeyRevocation(old, new, "Key compromised", successorProof = proof))
        assertTrue(json.decodeFromString<KeyRevocation>(encoded).provesSuccessor(groupId))

        val legacy = json.decodeFromString<KeyRevocation>("""{"oldPubkey":"$old","newPubkey":"$new"}""")
        assertEquals("", legacy.successorProof)
        assertFalse(legacy.provesSuccessor(groupId))
    }
}
