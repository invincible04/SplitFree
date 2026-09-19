package com.splitfree.test

import com.splitfree.domain.crypto.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

object RelayProbeAssertions {
    fun assertAccepted(accepted: Boolean) {
        assertTrue("Relay rejected the event or did not acknowledge publication", accepted)
    }

    fun requireEvents(expected: List<NostrEvent>, received: List<NostrEvent>): List<NostrEvent> {
        assertTrue("A relay probe must expect at least one event", expected.isNotEmpty())
        return expected.map { event ->
            assertTrue("Expected event ${event.id} must have a valid ID and signature", event.verify())
            val matches = received.filter { it.id == event.id }
            assertTrue("Relay did not return expected event ${event.id}", matches.isNotEmpty())
            matches.forEach { match ->
                assertTrue("Received event ${event.id} has an invalid ID or signature", match.verify())
                assertEquals("Relay changed expected event ${event.id}", event, match)
            }
            matches.first()
        }
    }
}
