package com.splitfree.domain.usecase

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UseCaseSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- KeyRevocation ---

    @Test
    fun `KeyRevocation round-trip`() {
        val kr = KeyRevocation(oldPubkey = "aaa", newPubkey = "bbb", reason = "compromised")
        val s = json.encodeToString(KeyRevocation.serializer(), kr)
        val d = json.decodeFromString<KeyRevocation>(s)
        assertEquals(kr, d)
    }

    @Test
    fun `KeyRevocation defaults`() {
        val kr = KeyRevocation(oldPubkey = "aaa")
        assertEquals("", kr.newPubkey)
        assertEquals("", kr.reason)
    }

    @Test
    fun `KeyRevocation deserializes with missing optional fields`() {
        val d = json.decodeFromString<KeyRevocation>("""{"oldPubkey":"x"}""")
        assertEquals("x", d.oldPubkey)
        assertEquals("", d.newPubkey)
    }

    // --- GroupMigration ---

    @Test
    fun `GroupMigration round-trip`() {
        val gm = GroupMigration(
            newGroupId = "new-id",
            encryptedKeys = mapOf("pub1" to "enc1", "pub2" to "enc2"),
            members = listOf("pub1", "pub2"),
            removedMember = "pub3"
        )
        val s = json.encodeToString(GroupMigration.serializer(), gm)
        val d = json.decodeFromString<GroupMigration>(s)
        assertEquals(gm, d)
    }

    @Test
    fun `GroupMigration preserves encrypted keys map`() {
        val gm = GroupMigration("id", mapOf("a" to "x", "b" to "y"), listOf("a", "b"), "c")
        val d = json.decodeFromString<GroupMigration>(json.encodeToString(GroupMigration.serializer(), gm))
        assertEquals(2, d.encryptedKeys.size)
        assertEquals("x", d.encryptedKeys["a"])
        assertEquals("y", d.encryptedKeys["b"])
    }

    // --- BalanceSnapshot ---

    @Test
    fun `BalanceSnapshot round-trip`() {
        val snap = BalanceSnapshot(
            id = "snap-1",
            as_of_event_count = 100,
            as_of_timestamp = 1700000000L,
            balances = listOf(
                SnapshotBalance("alice", 500, "INR"),
                SnapshotBalance("bob", -500, "INR")
            ),
            event_hashes = listOf("hash1", "hash2")
        )
        val s = json.encodeToString(BalanceSnapshot.serializer(), snap)
        val d = json.decodeFromString<BalanceSnapshot>(s)
        assertEquals(snap, d)
    }

    @Test
    fun `BalanceSnapshot empty balances and hashes`() {
        val snap = BalanceSnapshot("id", 0, 0, emptyList(), emptyList())
        val d = json.decodeFromString<BalanceSnapshot>(json.encodeToString(BalanceSnapshot.serializer(), snap))
        assertTrue(d.balances.isEmpty())
        assertTrue(d.event_hashes.isEmpty())
    }

    // --- SnapshotBalance ---

    @Test
    fun `SnapshotBalance default currency is INR`() {
        val sb = SnapshotBalance("alice", 100)
        assertEquals("INR", sb.currency)
    }

    @Test
    fun `SnapshotBalance round-trip with custom currency`() {
        val sb = SnapshotBalance("bob", -200, "USD")
        val d = json.decodeFromString<SnapshotBalance>(json.encodeToString(SnapshotBalance.serializer(), sb))
        assertEquals("USD", d.currency)
        assertEquals(-200L, d.net)
    }

    // --- BalanceResult (non-serializable data class) ---

    @Test
    fun `BalanceResult holds balances and exclusions`() {
        val br = BalanceResult(
            balances = listOf(com.splitfree.domain.model.Balance("a", 100, "INR")),
            excludedExpenseUuids = setOf("uuid-1", "uuid-2")
        )
        assertEquals(1, br.balances.size)
        assertEquals(2, br.excludedExpenseUuids.size)
        assertTrue("uuid-1" in br.excludedExpenseUuids)
    }
}
