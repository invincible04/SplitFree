package com.splitfree.domain.usecase.export

import com.splitfree.domain.model.export.ExportedEvent
import com.splitfree.domain.model.export.SplitFreeExport
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                        originalEventJson = "{}"
                    ),
                    ExportedEvent(
                        eventId = "evt2",
                        pubkey = "pub2",
                        createdAt = 1700000001,
                        kind = 30078,
                        contentEncrypted = "enc2",
                        eventType = "settlement",
                        sig = "sig2"
                    )
                )
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
    fun `default version is the current version 2`() {
        val export = SplitFreeExport(groupId = "g", exportedAt = 0, events = emptyList())
        assertEquals(2, SplitFreeExport.CURRENT_VERSION)
        assertEquals(SplitFreeExport.CURRENT_VERSION, export.version)
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
        val jsonStr = """{"version":2,"groupId":"g","exportedAt":0,"events":[],"extra":"field"}"""
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
                originalEventJson = """{"test":true}"""
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
                    sig = "sig$i"
                )
            }
        val export = SplitFreeExport(groupId = "g", exportedAt = 0, events = events)
        val serialized = json.encodeToString(SplitFreeExport.serializer(), export)
        val deserialized = json.decodeFromString<SplitFreeExport>(serialized)
        assertEquals(1000, deserialized.events.size)
        assertEquals("evt1", deserialized.events.first().eventId)
        assertEquals("evt1000", deserialized.events.last().eventId)
    }

    // --- canonical body (the MAC input) ---

    private val sample = SplitFreeExport(
        groupId = "g",
        exportedAt = 42,
        events = listOf(ExportedEvent("e", "p", 1, 30078, "c", "expense", null, "s", null, 0)),
        hmac = "ab".repeat(32),
        groupName = "Trip",
        relays = listOf("wss://relay.test"),
        encryptedEpochKeys = mapOf("0" to "k0", "1" to "k1")
    )

    @Test
    fun `canonical body ignores the hmac field`() {
        assertEquals(sample.canonicalBody(), sample.copy(hmac = "").canonicalBody())
        assertEquals(sample.canonicalBody(), sample.copy(hmac = "ff".repeat(32)).canonicalBody())
        assertTrue(sample.canonicalBody().contains("\"hmac\":\"\""))
    }

    @Test
    fun `canonical body spells out defaults and is stable across a pretty-printed round trip`() {
        val minimal = SplitFreeExport(groupId = "g", exportedAt = 0, events = emptyList())
        val body = minimal.canonicalBody()
        // Defaults are present so an omitted field and an explicit default hash identically.
        assertTrue(body.contains("\"version\":2"))
        assertTrue(body.contains("\"groupName\":\"\""))
        assertTrue(body.contains("\"relays\":[]"))
        assertTrue(body.contains("\"encryptedEpochKeys\":{}"))
        assertFalse(body.contains("\n"))

        // What the exporter writes (pretty, defaults included) decodes back to the same canonical body.
        val pretty = Json {
            prettyPrint = true
            encodeDefaults = true
        }.encodeToString(SplitFreeExport.serializer(), sample)
        assertEquals(sample.canonicalBody(), json.decodeFromString<SplitFreeExport>(pretty).canonicalBody())
        // So does a minified file that omits every default.
        val minified = """{"groupId":"g","exportedAt":0,"events":[]}"""
        assertEquals(body, json.decodeFromString<SplitFreeExport>(minified).canonicalBody())
    }

    @Test
    fun `canonical body changes when any authenticated field changes`() {
        val base = sample.canonicalBody()
        assertNotEquals(base, sample.copy(version = 3).canonicalBody())
        assertNotEquals(base, sample.copy(groupId = "h").canonicalBody())
        assertNotEquals(base, sample.copy(exportedAt = 43).canonicalBody())
        assertNotEquals(base, sample.copy(events = emptyList()).canonicalBody())
        assertNotEquals(base, sample.copy(encryptedGroupKey = "x").canonicalBody())
        assertNotEquals(base, sample.copy(groupName = "Trap").canonicalBody())
        assertNotEquals(base, sample.copy(relays = emptyList()).canonicalBody())
        assertNotEquals(base, sample.copy(keyEpoch = 1).canonicalBody())
        assertNotEquals(base, sample.copy(encryptedEpochKeys = mapOf("0" to "k0")).canonicalBody())
        assertNotEquals(base, sample.copy(events = sample.events.map { it.copy(sig = "seal:x") }).canonicalBody())
    }
}
