package com.splitfree.data.local.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
                receivedAt = 1
            )
        assertNull(e.expenseUuid)
        assertNull(e.originalEventJson)
        assertEquals(0, e.keyEpoch)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, e.applyState)
    }

    @Test
    fun `EventEntity apply state constants are distinct and match the SQL literals`() {
        // The DAO queries hard-code `applyState = 0` / `applyState = 1`; keep the constants in step.
        assertEquals(0, EventEntity.APPLY_STATE_APPLIED)
        assertEquals(1, EventEntity.APPLY_STATE_PENDING)
        val pending =
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
                applyState = EventEntity.APPLY_STATE_PENDING
            )
        assertEquals(EventEntity.APPLY_STATE_PENDING, pending.applyState)
        assertNotEquals(pending, pending.copy(applyState = EventEntity.APPLY_STATE_APPLIED))
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
                eventType = "settlement",
                expenseUuid = "uuid",
                sig = "sig",
                receivedAt = 2,
                originalEventJson = "{}"
            )
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
                relays = "[]"
            )
        assertEquals("", g.description)
        assertEquals(0L, g.lastSyncTimestamp)
        assertEquals(0L, g.lastMetaTimestamp)
        assertEquals(0, g.keyEpoch)
        assertEquals("", g.lastMetaEventId)
        assertEquals("{}", g.memberClocks)
    }

    @Test
    fun `GroupEntity equality`() {
        val a = GroupEntity("g", "Test", "", "p", 1, "[]", "[]")
        val b = a.copy()
        assertEquals(a, b)
        assertNotEquals(a, a.copy(lastMetaEventId = "e"))
        assertNotEquals(a, a.copy(memberClocks = """{"p":"1:e"}"""))
    }

    // --- DeliveryEntity ---

    @Test
    fun `DeliveryEntity constants match the SQL literals used by DeliveryDao`() {
        assertEquals(0, DeliveryEntity.STATE_AVAILABLE)
        assertEquals(1, DeliveryEntity.STATE_CONSUMED)
        assertEquals(0, DeliveryEntity.SOURCE_AUTHORED)
        assertEquals(1, DeliveryEntity.SOURCE_CARRIED)
        assertEquals("gift_wrap", DeliveryEntity.TYPE_GIFT_WRAP)
        assertEquals("key_rotation", DeliveryEntity.TYPE_KEY_ROTATION)
    }

    @Test
    fun `DeliveryEntity equality and nullable payload`() {
        val d =
            DeliveryEntity(
                envelopeId = "env",
                groupId = "g",
                recipient = "bob",
                eventId = null,
                envelopeJson = "{}",
                eventType = DeliveryEntity.TYPE_KEY_ROTATION,
                state = DeliveryEntity.STATE_AVAILABLE,
                source = DeliveryEntity.SOURCE_CARRIED,
                createdAt = 1,
                receivedAt = 2,
                sizeBytes = 2
            )
        assertEquals(d, d.copy())
        assertNull(d.eventId)
        val consumed = d.copy(state = DeliveryEntity.STATE_CONSUMED, envelopeJson = null)
        assertNull(consumed.envelopeJson)
        assertNotEquals(d, consumed)
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
