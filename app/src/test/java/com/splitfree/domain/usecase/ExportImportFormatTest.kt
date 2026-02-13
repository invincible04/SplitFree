package com.splitfree.domain.usecase

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ExportImportFormatTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    @Test
    fun `SplitFreeExport serializes and deserializes`() {
        val export =
            SplitFreeExport(
                groupId = "group123",
                exportedAt = 1700000000,
                events =
                    listOf(
                        ExportedEvent(
                            eventId = "evt1",
                            pubkey = "pub1",
                            createdAt = 1700000000,
                            kind = 30078,
                            contentEncrypted = "enc1",
                            eventType = "expense",
                            expenseUuid = "uuid1",
                            sig = "sig1",
                            originalEventJson = "{}",
                        ),
                        ExportedEvent(
                            eventId = "evt2",
                            pubkey = "pub2",
                            createdAt = 1700000001,
                            kind = 30078,
                            contentEncrypted = "enc2",
                            eventType = "settlement",
                            sig = "sig2",
                        ),
                    ),
            )
        val serialized = json.encodeToString(SplitFreeExport.serializer(), export)
        val deserialized = json.decodeFromString<SplitFreeExport>(serialized)
        assertEquals(export.version, deserialized.version)
        assertEquals(export.groupId, deserialized.groupId)
        assertEquals(export.exportedAt, deserialized.exportedAt)
        assertEquals(2, deserialized.events.size)
        assertEquals("evt1", deserialized.events[0].eventId)
        assertEquals("expense", deserialized.events[0].eventType)
        assertEquals("uuid1", deserialized.events[0].expenseUuid)
        assertNull(deserialized.events[1].expenseUuid)
    }

    @Test
    fun `default version is 1`() {
        val export = SplitFreeExport(groupId = "g", exportedAt = 0, events = emptyList())
        assertEquals(1, export.version)
    }

    @Test
    fun `empty events list serializes correctly`() {
        val export = SplitFreeExport(groupId = "g", exportedAt = 0, events = emptyList())
        val serialized = json.encodeToString(SplitFreeExport.serializer(), export)
        val deserialized = json.decodeFromString<SplitFreeExport>(serialized)
        assertTrue(deserialized.events.isEmpty())
    }

    @Test
    fun `deserialization ignores unknown fields`() {
        val jsonStr = """{"version":1,"groupId":"g","exportedAt":0,"events":[],"extra":"field"}"""
        val export = json.decodeFromString<SplitFreeExport>(jsonStr)
        assertEquals("g", export.groupId)
    }

    @Test
    fun `ExportedEvent preserves all fields`() {
        val event =
            ExportedEvent(
                eventId = "abc",
                pubkey = "def",
                createdAt = 123,
                kind = 30078,
                contentEncrypted = "ghi",
                eventType = "snapshot",
                expenseUuid = null,
                sig = "jkl",
                originalEventJson = """{"test":true}""",
            )
        val serialized = json.encodeToString(ExportedEvent.serializer(), event)
        val deserialized = json.decodeFromString<ExportedEvent>(serialized)
        assertEquals(event, deserialized)
    }

    @Test
    fun `large export with many events`() {
        val events =
            (1..1000).map { i ->
                ExportedEvent(
                    eventId = "evt$i",
                    pubkey = "pub",
                    createdAt = 1700000000L + i,
                    kind = 30078,
                    contentEncrypted = "enc$i",
                    eventType = "expense",
                    expenseUuid = "uuid$i",
                    sig = "sig$i",
                )
            }
        val export = SplitFreeExport(groupId = "g", exportedAt = 0, events = events)
        val serialized = json.encodeToString(SplitFreeExport.serializer(), export)
        val deserialized = json.decodeFromString<SplitFreeExport>(serialized)
        assertEquals(1000, deserialized.events.size)
        assertEquals("evt1", deserialized.events.first().eventId)
        assertEquals("evt1000", deserialized.events.last().eventId)
    }
}
