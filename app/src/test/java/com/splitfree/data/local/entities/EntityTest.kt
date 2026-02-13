package com.splitfree.data.local.entities

import org.junit.Assert.*
import org.junit.Test

class EntityTest {
    // --- EventEntity ---

    @Test
    fun `EventEntity defaults`() {
        val e =
            EventEntity(
                eventId = "id",
                groupId = "g",
                pubkey = "p",
                createdAt = 1,
                kind = 30078,
                contentEncrypted = "enc",
                eventType = "expense",
                sig = "sig",
                receivedAt = 1,
            )
        assertNull(e.contentDecrypted)
        assertNull(e.expenseUuid)
        assertEquals("[]", e.syncedToRelays)
        assertNull(e.originalEventJson)
    }

    @Test
    fun `EventEntity with all fields`() {
        val e =
            EventEntity(
                eventId = "id",
                groupId = "g",
                pubkey = "p",
                createdAt = 1,
                kind = 30078,
                contentEncrypted = "enc",
                contentDecrypted = "dec",
                eventType = "settlement",
                expenseUuid = "uuid",
                sig = "sig",
                syncedToRelays = """["wss://r"]""",
                receivedAt = 2,
                originalEventJson = "{}",
            )
        assertEquals("dec", e.contentDecrypted)
        assertEquals("uuid", e.expenseUuid)
        assertEquals("{}", e.originalEventJson)
    }

    @Test
    fun `EventEntity equality`() {
        val a = EventEntity("id", "g", "p", 1, 30078, "enc", eventType = "expense", sig = "sig", receivedAt = 1)
        val b = a.copy()
        assertEquals(a, b)
        assertNotEquals(a, a.copy(eventId = "other"))
    }

    // --- GroupEntity ---

    @Test
    fun `GroupEntity defaults`() {
        val g =
            GroupEntity(
                groupId = "g",
                name = "Test",
                createdBy = "p",
                createdAt = 1,
                members = "[]",
                relays = "[]",
                groupKey = "",
            )
        assertEquals("", g.description)
        assertEquals(0L, g.lastSyncTimestamp)
        assertEquals(0L, g.lastMetaTimestamp)
    }

    @Test
    fun `GroupEntity equality`() {
        val a = GroupEntity("g", "Test", "", "p", 1, "[]", "[]", "")
        val b = a.copy()
        assertEquals(a, b)
    }

    // --- OutboxEntity ---

    @Test
    fun `OutboxEntity defaults`() {
        val o = OutboxEntity(eventId = "e", eventJson = "{}", createdAt = 1)
        assertEquals(0, o.retryCount)
        assertNull(o.lastRetryAt)
    }

    @Test
    fun `OutboxEntity with retries`() {
        val o = OutboxEntity("e", "{}", 1, retryCount = 3, lastRetryAt = 100)
        assertEquals(3, o.retryCount)
        assertEquals(100L, o.lastRetryAt)
    }
}
