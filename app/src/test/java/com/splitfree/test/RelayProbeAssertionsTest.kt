package com.splitfree.test

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.util.toHex
import fr.acinq.secp256k1.Secp256k1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProbeAssertionsTest {
    private fun event(content: String = "offline fixture", auxiliaryByte: Byte = 0): NostrEvent {
        val privateKey = ByteArray(32).also { it[31] = 1 }
        try {
            val unsigned = NostrEvent(
                pubkey = NostrEvent.pubkeyFromPrivkey(privateKey),
                createdAt = 1_700_000_000,
                kind = 30078,
                tags = listOf(listOf("d", "offline-fixture")),
                content = content
            )
            val id = unsigned.computeId()
            return unsigned.copy(
                id = id.toHex(),
                sig = Secp256k1.signSchnorr(id, privateKey, ByteArray(32) { auxiliaryByte }).toHex()
            )
        } finally {
            privateKey.fill(0)
        }
    }

    @Test
    fun `positive publication acknowledgement passes`() {
        RelayProbeAssertions.assertAccepted(true)
    }

    @Test
    fun `rejected publication fails`() {
        assertThrows(AssertionError::class.java) { RelayProbeAssertions.assertAccepted(false) }
    }

    @Test
    fun `empty expectation fails even with received events`() {
        assertThrows(AssertionError::class.java) {
            RelayProbeAssertions.requireEvents(emptyList(), listOf(event()))
        }
    }

    @Test
    fun `empty receive fails`() {
        assertThrows(AssertionError::class.java) {
            RelayProbeAssertions.requireEvents(listOf(event()), emptyList())
        }
    }

    @Test
    fun `partially received expectation fails`() {
        val first = event("first")
        assertThrows(AssertionError::class.java) {
            RelayProbeAssertions.requireEvents(listOf(first, event("missing")), listOf(first))
        }
    }

    @Test
    fun `unrelated valid event cannot stand in for expected event`() {
        assertThrows(AssertionError::class.java) {
            RelayProbeAssertions.requireEvents(listOf(event()), listOf(event("wrong ID")))
        }
    }

    @Test
    fun `invalid expected fixture fails even when received unchanged`() {
        val invalid = event().copy(sig = "00".repeat(64))
        assertThrows(AssertionError::class.java) {
            RelayProbeAssertions.requireEvents(listOf(invalid), listOf(invalid))
        }
    }

    @Test
    fun `tampered expected content fails even when received unchanged`() {
        val invalid = event().copy(content = "tampered")
        assertThrows(AssertionError::class.java) {
            RelayProbeAssertions.requireEvents(listOf(invalid), listOf(invalid))
        }
    }

    @Test
    fun `tampering any received signed field fails`() {
        val expected = event()
        val tampered = listOf(
            expected.copy(content = "tampered"),
            expected.copy(pubkey = "00".repeat(32)),
            expected.copy(createdAt = expected.createdAt + 1),
            expected.copy(kind = 1),
            expected.copy(tags = emptyList()),
            expected.copy(sig = "00".repeat(64))
        )
        tampered.forEach { received ->
            assertThrows(AssertionError::class.java) {
                RelayProbeAssertions.requireEvents(listOf(expected), listOf(received))
            }
        }
    }

    @Test
    fun `valid but different signature is still an exact event mismatch`() {
        val expected = event()
        val resigned = event(auxiliaryByte = 1)
        assertEquals(expected.id, resigned.id)
        assertTrue(resigned.verify())
        assertTrue(expected.sig != resigned.sig)
        assertThrows(AssertionError::class.java) {
            RelayProbeAssertions.requireEvents(listOf(expected), listOf(resigned))
        }
    }

    @Test
    fun `valid duplicate cannot hide a tampered matching ID`() {
        val expected = event()
        assertThrows(AssertionError::class.java) {
            RelayProbeAssertions.requireEvents(
                listOf(expected),
                listOf(expected, expected.copy(content = "tampered"))
            )
        }
    }

    @Test
    fun `matching received events are returned in expected order allowing unrelated extras`() {
        val first = event("first")
        val second = event("second")
        val receivedFirst = first.copy()
        val unrelated = event("unrelated").copy(sig = "invalid")
        val matches = RelayProbeAssertions.requireEvents(
            listOf(first, second),
            listOf(unrelated, second, receivedFirst, first)
        )
        assertEquals(listOf(first, second), matches)
        assertTrue(matches.first() === receivedFirst)
    }
}
