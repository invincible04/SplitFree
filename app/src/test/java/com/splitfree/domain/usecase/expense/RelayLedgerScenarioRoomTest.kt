package com.splitfree.domain.usecase.expense

import android.app.Application
import com.splitfree.data.nostr.NostrClient
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.sync.FetchResult
import com.splitfree.domain.model.sync.FlushResult
import com.splitfree.domain.model.sync.HistoryRange
import com.splitfree.test.RelayLedgerScenario
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Offline runs of the shared relay fixture, including failure checks for rejected or missing history. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RelayLedgerScenarioRoomTest {
    /** Exchanges serialized events, never database state; the shared fixture also fakes key storage. */
    private class Network {
        val rows = linkedMapOf<String, String>()
        var rejectPublish = false
        var dropAccepted = false
        var hideHistory = false
        var complete = true
        var incompleteNextFetch = false
        var corruptHistory = false

        fun client(scope: CoroutineScope): NostrClient = mockk<NostrClient>(relaxed = true).also { client ->
            val connected = MutableStateFlow(false)
            var relays = emptyList<String>()
            every { client.connectionState } returns connected
            every { client.isConnected } answers { connected.value }
            every { client.currentRelayUrls() } answers { relays }
            coEvery { client.connect(any()) } coAnswers {
                relays = firstArg()
                connected.value = true
            }
            coEvery { client.publishJson(any()) } coAnswers { publish(firstArg()) }
            coEvery { client.publish(any()) } coAnswers { publish(firstArg<NostrEvent>().toJson()) }
            coEvery { client.fetchEventsByRelay(any(), any(), any()) } coAnswers {
                fetch(firstArg(), secondArg<Map<String, Long>>().keys)
            }
            coEvery { client.fetchHistory(any(), any(), any()) } coAnswers {
                fetch(firstArg(), secondArg<Map<String, List<HistoryRange>>>().keys)
            }
            coEvery { client.fetchEventIds(any(), any(), any()) } coAnswers {
                history(firstArg()).filter { it.kind == 30078 }.map { it.id }.toSet()
            }
        }

        private fun fetch(groupId: String, relays: Set<String>): FetchResult {
            val finished = complete && !incompleteNextFetch
            incompleteNextFetch = false
            return FetchResult(history(groupId), finished, if (finished) relays else emptySet())
        }

        private fun publish(serialized: String): Boolean {
            val event = checkNotNull(NostrEvent.fromJson(serialized))
            check(event.verify())
            if (rejectPublish) return false
            if (!dropAccepted) rows[event.id] = serialized
            return true
        }

        private fun history(groupId: String): List<NostrEvent> {
            if (hideHistory) return emptyList()
            val events = rows.values.map { checkNotNull(NostrEvent.fromJson(it)) }
                .filter { it.tags.any { tag -> tag == listOf("g", groupId) } }.reversed()
            return if (corruptHistory) events.map { it.copy(content = "tampered") } else events
        }
    }

    @Test
    fun `direct live scenario runs through real create join outbox engine and mutations offline`() = runBlocking {
        val network = Network()
        RelayLedgerScenario(false, listOf("wss://relay.test"), network::client).use { it.mutations() }
    }

    @Test
    fun `gift wrapped live scenario and mutations run through independent production stores offline`() = runBlocking {
        val network = Network()
        RelayLedgerScenario(true, listOf("wss://relay.test"), network::client).use { it.mutations() }
    }

    @Test
    fun `relay rejection fails the scenario and preserves the exact pending rows for retry`() = runBlocking {
        val network = Network()
        RelayLedgerScenario(true, listOf("wss://relay.test"), network::client).use { scenario ->
            scenario.createAndJoin()
            scenario.add(scenario.alice, "dinner", 1_000, 500)
            val pending = scenario.alice.pending()
            network.rejectPublish = true
            assertThrows(AssertionError::class.java) {
                runBlocking { scenario.exchange(scenario.alice, scenario.bob, 500) }
            }
            assertEquals(pending, scenario.alice.pending())
            assertTrue(scenario.bob.net(scenario.groupId).isEmpty())
            assertTrue(scenario.alice.db.outboxDao().getAll().all { it.retryCount == 1 })
            network.rejectPublish = false
            scenario.exchange(scenario.alice, scenario.bob, 500)
        }
    }

    @Test
    fun `a relay ACK without delivery cannot satisfy the live scenario`() = runBlocking {
        val network = Network()
        RelayLedgerScenario(true, listOf("wss://relay.test"), network::client).use { scenario ->
            scenario.createAndJoin()
            scenario.add(scenario.alice, "dinner", 1_000, 500)
            network.dropAccepted = true
            assertThrows(AssertionError::class.java) {
                runBlocking { scenario.exchange(scenario.alice, scenario.bob, 500) }
            }
            assertEquals(0, scenario.alice.db.outboxDao().count())
            assertTrue(scenario.bob.net(scenario.groupId).isEmpty())
        }
    }

    @Test
    fun `empty history cannot masquerade as successful group join`(): Unit = runBlocking {
        val network = Network().apply { hideHistory = true }
        RelayLedgerScenario(true, listOf("wss://relay.test"), network::client).use { scenario ->
            assertThrows(AssertionError::class.java) { runBlocking { scenario.createAndJoin() } }
        }
    }

    @Test
    fun `incomplete history cannot pass despite all expected records arriving`() = runBlocking {
        val network = Network()
        RelayLedgerScenario(false, listOf("wss://relay.test"), network::client).use { scenario ->
            scenario.createAndJoin()
            scenario.add(scenario.alice, "dinner", 1_000, 500)
            network.complete = false
            assertThrows(AssertionError::class.java) {
                runBlocking { scenario.exchange(scenario.alice, scenario.bob, 500) }
            }
            assertEquals(scenario.alice.ids(scenario.groupId), scenario.bob.ids(scenario.groupId))
        }
    }

    @Test
    fun `tampered history cannot pass the receiver ledger assertions`() = runBlocking {
        val network = Network()
        RelayLedgerScenario(true, listOf("wss://relay.test"), network::client).use { scenario ->
            scenario.createAndJoin()
            scenario.add(scenario.alice, "dinner", 1_000, 500)
            network.corruptHistory = true
            assertThrows(AssertionError::class.java) {
                runBlocking { scenario.exchange(scenario.alice, scenario.bob, 500) }
            }
            assertFalse(scenario.alice.ids(scenario.groupId) == scenario.bob.ids(scenario.groupId))
            assertTrue(scenario.bob.net(scenario.groupId).isEmpty())
        }
    }

    @Test
    fun `empty outbox cannot make an asserted flush silently pass`(): Unit = runBlocking {
        val network = Network()
        RelayLedgerScenario(false, listOf("wss://relay.test"), network::client).use { scenario ->
            assertEquals(FlushResult(0, 0), scenario.alice.engine.flushOutbox())
            assertThrows(AssertionError::class.java) { runBlocking { scenario.alice.flush() } }
        }
    }

    @Test
    fun `a partial initial join fetch cannot be masked by later successful pulls`() = runBlocking {
        val network = Network().apply { incompleteNextFetch = true }
        RelayLedgerScenario(true, listOf("wss://relay.test"), network::client).use { scenario ->
            assertThrows(AssertionError::class.java) { runBlocking { scenario.createAndJoin() } }
            assertFalse(network.incompleteNextFetch)
            assertTrue(scenario.bob.ids(scenario.groupId).isNotEmpty())
            assertEquals(0, scenario.bob.db.outboxDao().count())
        }
    }

    @Test
    fun `gift wrap scenario rejects accidental direct wire output`() = runBlocking {
        val network = Network()
        RelayLedgerScenario(true, listOf("wss://relay.test"), network::client).use { scenario ->
            scenario.createAndJoin()
            scenario.alice.settings.giftWrapEnabled = false
            scenario.add(scenario.alice, "dinner", 1_000, 500)
            assertThrows(AssertionError::class.java) {
                runBlocking { scenario.exchange(scenario.alice, scenario.bob, 500) }
            }
            assertEquals(30078, scenario.alice.pending().single().kind)
            assertTrue(scenario.bob.net(scenario.groupId).isEmpty())
        }
    }
}
