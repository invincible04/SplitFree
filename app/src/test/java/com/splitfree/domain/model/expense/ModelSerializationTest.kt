package com.splitfree.domain.model.expense

import com.splitfree.domain.model.group.GroupMeta
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for domain model serialization — ensures events survive JSON round-trips
 * through relays and local storage.
 */
class ModelSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    // --- Expense ---

    @Test
    fun `Expense serialization round-trip`() {
        val expense =
            Expense(
                id = "exp-123",
                amount = 50000,
                currency = "INR",
                description = "Dinner at restaurant",
                paidBy = "alice-pubkey",
                splitType = SplitType.EQUAL,
                splitAmong =
                listOf(
                    SplitEntry("alice-pubkey", 25000),
                    SplitEntry("bob-pubkey", 25000)
                ),
                timestamp = 1700000000,
                category = "food"
            )
        val serialized = json.encodeToString(Expense.serializer(), expense)
        val deserialized = json.decodeFromString<Expense>(serialized)
        assertEquals(expense, deserialized)
    }

    @Test
    fun `Expense JSON uses snake_case per design doc`() {
        val expense =
            Expense(
                id = "1",
                amount = 100,
                currency = "INR",
                description = "test",
                paidBy = "alice",
                splitType = SplitType.EXACT,
                splitAmong = listOf(SplitEntry("alice", 100)),
                timestamp = 1
            )
        val serialized = json.encodeToString(Expense.serializer(), expense)
        assertTrue("Must use paid_by", serialized.contains("\"paid_by\""))
        assertTrue("Must use split_type", serialized.contains("\"split_type\""))
        assertTrue("Must use split_among", serialized.contains("\"split_among\""))
        assertFalse("Must NOT use paidBy", serialized.contains("\"paidBy\""))
    }

    @Test
    fun `Expense with all split types serializes correctly`() {
        for (type in SplitType.entries) {
            val expense =
                Expense(
                    id = "1",
                    amount = 100,
                    currency = "INR",
                    description = "test",
                    paidBy = "a",
                    splitType = type,
                    splitAmong = listOf(SplitEntry("a", 100)),
                    timestamp = 1
                )
            val s = json.encodeToString(Expense.serializer(), expense)
            val d = json.decodeFromString<Expense>(s)
            assertEquals(type, d.splitType)
        }
    }

    @Test
    fun `Expense with empty category defaults correctly`() {
        val jsonStr =
            """{"id":"1","amount":100,"currency":"INR","description":"test",""" +
                """"paid_by":"a","split_type":"equal",""" +
                """"split_among":[{"pubkey":"a","share":100}],"timestamp":1}"""
        val expense = json.decodeFromString<Expense>(jsonStr)
        assertEquals("", expense.category) // default
    }

    @Test
    fun `Expense with unicode description`() {
        val expense =
            Expense(
                id = "1",
                amount = 500,
                currency = "INR",
                description = "Dinner 🍕 at café — ₹500",
                paidBy = "a",
                splitType = SplitType.EQUAL,
                splitAmong = listOf(SplitEntry("a", 500)),
                timestamp = 1
            )
        val d = json.decodeFromString<Expense>(json.encodeToString(Expense.serializer(), expense))
        assertEquals("Dinner 🍕 at café — ₹500", d.description)
    }

    // --- Settlement ---

    @Test
    fun `Settlement serialization round-trip`() {
        val settlement =
            Settlement(
                id = "settle-1",
                from = "bob",
                to = "alice",
                amount = 25000,
                currency = "INR",
                method = "upi",
                timestamp = 1700000000
            )
        val s = json.encodeToString(Settlement.serializer(), settlement)
        val d = json.decodeFromString<Settlement>(s)
        assertEquals(settlement, d)
    }

    @Test
    fun `Settlement default method is cash`() {
        val jsonStr = """{"id":"1","from":"a","to":"b","amount":100,"currency":"INR","timestamp":1}"""
        val settlement = json.decodeFromString<Settlement>(jsonStr)
        assertEquals("cash", settlement.method)
    }

    // --- GroupMeta ---

    @Test
    fun `GroupMeta serialization round-trip`() {
        val meta =
            GroupMeta(
                name = "Goa Trip 2026",
                description = "February trip expenses",
                createdBy = "alice-pubkey",
                createdAt = 1700000000,
                members = listOf("alice-pubkey", "bob-pubkey", "charlie-pubkey"),
                relays = listOf("wss://relay.damus.io", "wss://nos.lol")
            )
        val s = json.encodeToString(GroupMeta.serializer(), meta)
        val d = json.decodeFromString<GroupMeta>(s)
        assertEquals(meta, d)
    }

    @Test
    fun `GroupMeta JSON uses snake_case`() {
        val meta = GroupMeta(name = "Test", createdBy = "abc", createdAt = 1)
        val s = json.encodeToString(GroupMeta.serializer(), meta)
        assertTrue(s.contains("\"created_by\""))
        assertTrue(s.contains("\"created_at\""))
    }

    @Test
    fun `GroupMeta with empty fields deserializes with defaults`() {
        val jsonStr = """{"name":"Test"}"""
        val meta = json.decodeFromString<GroupMeta>(jsonStr)
        assertEquals("Test", meta.name)
        assertEquals("", meta.description)
        assertEquals("", meta.createdBy)
        assertEquals(0L, meta.createdAt)
        assertEquals(emptyList<String>(), meta.members)
        assertEquals(emptyList<String>(), meta.relays)
    }

    // --- SplitType enum ---

    @Test
    fun `SplitType enum values serialize to lowercase`() {
        assertEquals("\"equal\"", json.encodeToString(SplitType.serializer(), SplitType.EQUAL))
        assertEquals("\"exact\"", json.encodeToString(SplitType.serializer(), SplitType.EXACT))
        assertEquals("\"percentage\"", json.encodeToString(SplitType.serializer(), SplitType.PERCENTAGE))
        assertEquals("\"shares\"", json.encodeToString(SplitType.serializer(), SplitType.SHARES))
    }

    @Test
    fun `SplitType deserializes from lowercase`() {
        assertEquals(SplitType.EQUAL, json.decodeFromString<SplitType>("\"equal\""))
        assertEquals(SplitType.SHARES, json.decodeFromString<SplitType>("\"shares\""))
    }

    // --- Cross-layer: Expense through NIP-44 encryption ---

    @Test
    fun `Expense survives serialize-encrypt-decrypt-deserialize`() {
        val encryption =
            com.splitfree.domain.crypto
                .GroupEncryption(com.splitfree.data.util.CompressionUtil)
        val key = encryption.generateGroupKey()
        val expense =
            Expense(
                id = "cross-layer-test",
                amount = 99999,
                currency = "USD",
                description = "Cross-layer test with special chars: \"quotes\" & \\backslash",
                paidBy = "alice",
                splitType = SplitType.PERCENTAGE,
                splitAmong = listOf(SplitEntry("alice", 33333), SplitEntry("bob", 33333), SplitEntry("charlie", 33333)),
                timestamp = 1700000000,
                category = "test"
            )
        val plaintext = json.encodeToString(Expense.serializer(), expense)
        val encrypted = encryption.encrypt(plaintext, key)
        val decrypted = encryption.decrypt(encrypted, key)
        val recovered = json.decodeFromString<Expense>(decrypted)
        assertEquals(expense, recovered)
    }
}
