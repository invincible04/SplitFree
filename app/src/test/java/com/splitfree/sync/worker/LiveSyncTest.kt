package com.splitfree.sync.worker

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.sync.FlushResult
import com.splitfree.domain.model.sync.PullResult
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.ExpenseNotifier
import com.splitfree.sync.event.IngestOutcome
import com.splitfree.util.ProcessHealthTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LiveSyncTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val context = RuntimeEnvironment.getApplication()
    private val owner = Owner()

    private val incoming = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 64)
    private val connectionState = MutableStateFlow(true)
    private val connectionGeneration = MutableStateFlow(1L)
    private val hasIdentity = MutableStateFlow(true)
    private val nostrClient = mockk<NostrClient>(relaxed = true)
    private val identity = mockk<IdentityContract>(relaxed = true)
    private val groupRepo = mockk<GroupRepository>(relaxed = true)
    private val eventProcessor = mockk<EventProcessor>(relaxed = true)
    private val connectionManager = mockk<RelayConnectionManager>(relaxed = true)
    private val syncEngine = mockk<SyncEngine>(relaxed = true)

    private val group = group("g1", relays = listOf("wss://test"), lastSync = 10_000)
    private val groups = MutableStateFlow(listOf(group))

    /** Subscription count of [incoming] observed at the moment of every `subscribe` call. */
    private val collectorsAtSubscribe = mutableListOf<Int>()

    private lateinit var liveSync: LiveSync

    @Before
    fun setup() {
        mockkObject(SyncScheduler, ExpenseNotifier)
        every { SyncScheduler.scheduleImmediateSync(any()) } returns Unit
        every { ExpenseNotifier.notifyIfNeeded(any(), any(), any(), any(), any(), any()) } returns Unit
        every { identity.hasIdentity() } returns true
        every { identity.observeHasIdentity() } returns hasIdentity
        every { identity.getPublicKeyHex() } returns "alice"
        every { nostrClient.incomingEvents } returns incoming
        every { nostrClient.connectionState } returns connectionState
        every { nostrClient.connectionGeneration } returns connectionGeneration
        every { nostrClient.isConnected } returns true
        coEvery { nostrClient.subscribe(any(), any(), any()) } answers {
            collectorsAtSubscribe += incoming.subscriptionCount.value
        }
        coEvery { groupRepo.getAll() } answers { groups.value }
        every { groupRepo.observeAll() } returns groups
        coEvery { connectionManager.ensureConnected(any()) } returns listOf("wss://test")
        every { connectionManager.primaryRelaysOf(any()) } answers { callOriginal() }
        coEvery { syncEngine.flushOutbox() } returns FlushResult(0, 0)
        coEvery { syncEngine.pullEvents(any(), any(), any(), any(), any()) } returns PullResult(0, true)
        liveSync = LiveSync(
            nostrClient,
            groupRepo,
            identity,
            eventProcessor,
            connectionManager,
            syncEngine,
            context,
            testScope.backgroundScope
        )
        liveSync.bind(owner.registry)
    }

    @After
    fun teardown() = unmockkAll()

    private fun group(id: String, relays: List<String>, lastSync: Long = 0, key: String? = "key-$id"): Group {
        coEvery { groupRepo.getGroupEntity(id) } returns
            GroupEntity(
                id,
                id,
                createdBy = "alice",
                createdAt = 1,
                members = "[]",
                relays = "[]",
                lastSyncTimestamp = lastSync
            )
        coEvery { groupRepo.getGroupKey(id) } returns key
        return Group(id, id, createdBy = "alice", createdAt = 1, members = listOf("alice"), relays = relays)
    }

    private fun start() = owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

    private fun stop() = owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

    /** Advances both clocks the session reads: the coroutine scheduler and the device uptime. */
    private fun TestScope.elapse(ms: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun lastHeartbeat() = ProcessHealthTracker.buildReport(context)

    private fun event(id: String) =
        NostrEvent(id = id, pubkey = "bob", createdAt = 1, kind = 30078, content = "x", sig = "s")

    private fun processed(outcome: IngestOutcome) = EventProcessor.ProcessResult(
        stored = outcome != IngestOutcome.REJECTED,
        groupName = "Trip",
        eventType = "expense",
        decrypted = "{}",
        authorHex = "bob",
        outcome = outcome
    )

    @Test
    fun `healthy foreground starts another full sweep without reconnect or group changes`() = testScope.runTest {
        start()
        runCurrent()
        val generation = connectionGeneration.value
        elapse(LiveSync.RECONCILE_INTERVAL_MS - 1)
        coVerify(exactly = 1) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        elapse(1)
        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        assertEquals(generation, connectionGeneration.value)
        coVerify(exactly = 1) { connectionManager.ensureConnected(any()) }
    }

    @Test
    fun `binding twice is refused`() {
        assertThrows(IllegalStateException::class.java) { liveSync.bind(owner.registry) }
    }

    @Test
    fun `the live collector is attached before the first subscribe`() = testScope.runTest {
        start()
        runCurrent()

        assertEquals(listOf(1), collectorsAtSubscribe)
        assertTrue(lastHeartbeat().contains("live_connected"))
    }

    @Test
    fun `start pulls history and subscribes to arrivals of any authored age`() = testScope.runTest {
        start()
        runCurrent()

        val since = slot<Long>()
        coVerifyOrder {
            connectionManager.ensureConnected(forceReconnect = true)
            syncEngine.flushOutbox()
            syncEngine.pullEvents("g1", 10_000 - 3600, "key-g1", false, context)
            nostrClient.subscribe("g1", capture(since), "alice")
        }
        coVerify(exactly = 0) { connectionManager.ensureConnected(false) }
        assertEquals(0L, since.captured)
    }

    @Test
    fun `a group without a cursor is pulled from zero`() = testScope.runTest {
        groups.value = listOf(group("fresh", relays = listOf("wss://test"), lastSync = 0))
        start()
        runCurrent()

        coVerify(exactly = 1) { syncEngine.pullEvents("fresh", 0, "key-fresh", false, context) }
    }

    @Test
    fun `a group without a key is subscribed but not pulled`() = testScope.runTest {
        groups.value = listOf(group("keyless", relays = listOf("wss://test"), key = null))
        start()
        runCurrent()

        coVerify(exactly = 0) { syncEngine.pullEvents(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { nostrClient.subscribe("keyless", any(), "alice") }
    }

    @Test
    fun `stop releases the connection exactly once and nothing is processed afterwards`() = testScope.runTest {
        start()
        runCurrent()
        verify(exactly = 0) { nostrClient.releaseConnection() }

        stop()
        runCurrent()
        verify(exactly = 1) { nostrClient.releaseConnection() }
        assertEquals(0, incoming.subscriptionCount.value)

        incoming.tryEmit(event("late"))
        runCurrent()
        coVerify(exactly = 0) { eventProcessor.process(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(lastHeartbeat().contains("live_stop"))
    }

    @Test
    fun `stop during a suspended catch-up releases once and never subscribes`() = testScope.runTest {
        coEvery { syncEngine.pullEvents(any(), any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        start()
        runCurrent()
        coVerify(exactly = 1) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }
        coVerify(exactly = 0) { nostrClient.subscribe(any(), any(), any()) }

        stop()
        runCurrent()
        verify(exactly = 1) { nostrClient.releaseConnection() }
        coVerify(exactly = 0) { nostrClient.subscribe(any(), any(), any()) }
        elapse(120_000)
        coVerify(exactly = 1) { connectionManager.ensureConnected(any()) }
    }

    @Test
    fun `every foreground entry catches up again`() = testScope.runTest {
        start()
        runCurrent()
        stop()
        runCurrent()
        start()
        runCurrent()

        coVerify(exactly = 2) { connectionManager.ensureConnected(true) }
        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }
        coVerify(exactly = 2) { nostrClient.subscribe("g1", any(), "alice") }
        verify(exactly = 1) { nostrClient.releaseConnection() }
    }

    @Test
    fun `an account with no groups still connects while visible and releases on stop`() = testScope.runTest {
        groups.value = emptyList()
        start()
        runCurrent()

        coVerify(exactly = 1) { connectionManager.ensureConnected(true) }
        coVerify(exactly = 1) { syncEngine.flushOutbox() }
        coVerify(exactly = 0) { nostrClient.subscribe(any(), any(), any()) }
        assertEquals(1, groups.subscriptionCount.value)

        stop()
        runCurrent()
        verify(exactly = 1) { nostrClient.releaseConnection() }
        assertEquals(0, groups.subscriptionCount.value)
    }

    @Test
    fun `without an identity nothing is acquired until one appears`() = testScope.runTest {
        hasIdentity.value = false
        start()
        runCurrent()
        coVerify(exactly = 0) { connectionManager.ensureConnected(any()) }

        hasIdentity.value = true
        runCurrent()
        coVerify(exactly = 1) { connectionManager.ensureConnected(true) }
        coVerify(exactly = 1) { nostrClient.subscribe("g1", any(), "alice") }
    }

    @Test
    fun `a newly observed group is flushed, pulled and subscribed without a reconnect`() = testScope.runTest {
        start()
        runCurrent()
        val joined = group("g2", relays = listOf("wss://test"), lastSync = 50_000)

        groups.value = listOf(group, joined)
        runCurrent()

        coVerify(exactly = 2) { syncEngine.flushOutbox() }
        coVerify(exactly = 1) { syncEngine.pullEvents("g2", 50_000 - 3600, "key-g2", false, context) }
        coVerify(exactly = 1) { nostrClient.subscribe("g2", any(), "alice") }
        coVerify(exactly = 1) { nostrClient.subscribe("g1", any(), "alice") }
        coVerify(exactly = 1) { connectionManager.ensureConnected(any()) }
        verify(exactly = 0) { nostrClient.releaseConnection() }
    }

    @Test
    fun `a group that disappears is unsubscribed once`() = testScope.runTest {
        val second = group("g2", relays = listOf("wss://test"))
        groups.value = listOf(group, second)
        start()
        runCurrent()

        groups.value = listOf(group)
        runCurrent()

        coVerify(exactly = 1) { nostrClient.unsubscribe("g2") }
        coVerify(exactly = 0) { nostrClient.unsubscribe("g1") }
        coVerify(exactly = 1) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }

        groups.value = listOf(group)
        runCurrent()
        coVerify(exactly = 1) { nostrClient.unsubscribe("g2") }
    }

    @Test
    fun `a changed relay set reconnects, hands the temporary reference back and redoes every group`() =
        testScope.runTest {
            start()
            runCurrent()
            val joined = group("g2", relays = listOf("wss://other"))

            groups.value = listOf(group, joined)
            runCurrent()

            coVerifyOrder {
                connectionManager.ensureConnected(true)
                nostrClient.releaseConnection()
                syncEngine.flushOutbox()
            }
            coVerify(exactly = 2) { connectionManager.ensureConnected(true) }
            verify(exactly = 1) { nostrClient.releaseConnection() }
            coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }
            coVerify(exactly = 2) { nostrClient.subscribe("g1", any(), "alice") }
            coVerify(exactly = 1) { nostrClient.subscribe("g2", any(), "alice") }

            stop()
            runCurrent()
            verify(exactly = 2) { nostrClient.releaseConnection() }
        }

    @Test
    fun `relays that stay down for five seconds force a reconnect and the catch-up follows the recovery`() =
        testScope.runTest {
            start()
            runCurrent()

            every { nostrClient.isConnected } returns false
            connectionState.value = false
            elapse(4_999)
            coVerify(exactly = 1) { connectionManager.ensureConnected(true) }

            elapse(1)
            coVerify(exactly = 2) { connectionManager.ensureConnected(true) }
            verify(exactly = 1) { nostrClient.releaseConnection() }
            coVerify(exactly = 1) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }

            every { nostrClient.isConnected } returns true
            connectionState.value = true
            connectionGeneration.value++
            runCurrent()
            coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }
            coVerify(exactly = 2) { nostrClient.subscribe("g1", any(), "alice") }
            coVerify(exactly = 2) { connectionManager.ensureConnected(true) }
        }

    @Test
    fun `a drop that recovers on its own is not reconnected but is caught up once`() = testScope.runTest {
        start()
        runCurrent()

        connectionState.value = false
        runCurrent()
        connectionState.value = true
        connectionGeneration.value++
        elapse(5_000)

        coVerify(exactly = 1) { connectionManager.ensureConnected(any()) }
        verify(exactly = 0) { nostrClient.releaseConnection() }
        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }
        coVerify(exactly = 2) { nostrClient.subscribe("g1", any(), "alice") }
    }

    @Test
    fun `starting offline reports no connection and re-pulls every group once a relay comes up`() = testScope.runTest {
        groups.value = listOf(group, group("g2", relays = listOf("wss://test"), lastSync = 20_000))
        every { nostrClient.isConnected } returns false
        connectionState.value = false
        start()
        runCurrent()

        assertFalse(lastHeartbeat().contains("live_connected"))
        assertTrue(lastHeartbeat().contains("live_acquired"))
        coVerify(exactly = 1) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }
        coVerify(exactly = 1) { syncEngine.pullEvents("g2", any(), "key-g2", any(), any()) }
        coVerify(exactly = 1) { nostrClient.subscribe("g1", any(), "alice") }

        every { nostrClient.isConnected } returns true
        connectionState.value = true
        connectionGeneration.value++
        elapse(5_000)

        assertTrue(lastHeartbeat().contains("live_connected"))
        coVerify(exactly = 1) { connectionManager.ensureConnected(any()) }
        coVerify(exactly = 2) { syncEngine.pullEvents("g1", 10_000 - 3600, "key-g1", false, context) }
        coVerify(exactly = 2) { syncEngine.pullEvents("g2", 20_000 - 3600, "key-g2", false, context) }
        coVerify(exactly = 2) { nostrClient.subscribe("g1", any(), "alice") }
        coVerify(exactly = 2) { nostrClient.subscribe("g2", any(), "alice") }
    }

    @Test
    fun `an incomplete initial pull recovers on a timer without any connection edge`() = testScope.runTest {
        coEvery { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) } returns
            PullResult(0, false) andThen PullResult(0, true)

        start()
        runCurrent()
        elapse(29_999)
        coVerify(exactly = 1) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }
        elapse(1)
        coVerify(exactly = 2) { syncEngine.pullEvents("g1", 10_000 - 3600, "key-g1", false, context) }
        coVerify(exactly = 2) { nostrClient.subscribe("g1", any(), "alice") }
        elapse(60_000)
        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }
        coVerify(exactly = 1) { connectionManager.ensureConnected(any()) }
        verify(exactly = 0) { SyncScheduler.scheduleImmediateSync(context) }
    }

    @Test
    fun `incomplete groups get three capped timer retries and one durable fallback`() = testScope.runTest {
        groups.value = listOf(group, group("g2", relays = listOf("wss://test")))
        coEvery { syncEngine.pullEvents(any(), any(), any(), any(), any()) } returns PullResult(0, false)
        start()
        runCurrent()

        for ((index, delayMs) in listOf(30_000L, 60_000L, 120_000L).withIndex()) {
            elapse(delayMs - 1)
            coVerify(exactly = index + 1) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
            verify(exactly = 0) { SyncScheduler.scheduleImmediateSync(context) }
            elapse(1)
            coVerify(exactly = index + 2) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
            coVerify(exactly = index + 2) { syncEngine.pullEvents("g2", any(), any(), any(), any()) }
        }
        verify(exactly = 1) { SyncScheduler.scheduleImmediateSync(context) }
        elapse(60_000)
        coVerify(exactly = 4) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        coVerify(exactly = 4) { syncEngine.pullEvents("g2", any(), any(), any(), any()) }
        verify(exactly = 1) { SyncScheduler.scheduleImmediateSync(context) }
    }

    @Test
    fun `a relay generation catches up completed groups while the fallback stays connected`() = testScope.runTest {
        start()
        runCurrent()
        connectionGeneration.value++
        runCurrent()

        assertTrue(connectionState.value)
        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }
        coVerify(exactly = 2) { nostrClient.subscribe("g1", any(), "alice") }
        coVerify(exactly = 1) { connectionManager.ensureConnected(any()) }
        elapse(60_000)
        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }
    }

    @Test
    fun `an incomplete newly observed group retries without rerequesting complete groups`() = testScope.runTest {
        start()
        runCurrent()
        val joined = group("g2", relays = listOf("wss://test"))
        coEvery { syncEngine.pullEvents("g2", any(), "key-g2", any(), any()) } returns
            PullResult(0, false) andThen PullResult(0, true)

        groups.value = listOf(group, joined)
        runCurrent()
        elapse(30_000)
        coVerify(exactly = 1) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }
        coVerify(exactly = 2) { syncEngine.pullEvents("g2", any(), "key-g2", any(), any()) }
        elapse(60_000)
        coVerify(exactly = 1) { syncEngine.pullEvents("g1", any(), "key-g1", any(), any()) }
        coVerify(exactly = 2) { syncEngine.pullEvents("g2", any(), "key-g2", any(), any()) }
        verify(exactly = 0) { SyncScheduler.scheduleImmediateSync(context) }
    }

    @Test
    fun `a generation after joining an incomplete group catches up every current group`() = testScope.runTest {
        start()
        runCurrent()
        val joined = group("g2", relays = listOf("wss://test"))
        coEvery { syncEngine.pullEvents("g2", any(), any(), any(), any()) } returns
            PullResult(0, false) andThen PullResult(0, true)
        groups.value = listOf(group, joined)
        runCurrent()
        connectionGeneration.value++
        runCurrent()

        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        coVerify(exactly = 2) { syncEngine.pullEvents("g2", any(), any(), any(), any()) }
        elapse(60_000)
        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        coVerify(exactly = 2) { syncEngine.pullEvents("g2", any(), any(), any(), any()) }
        verify(exactly = 0) { SyncScheduler.scheduleImmediateSync(context) }
    }

    @Test
    fun `timer retries stop independently as each group completes`() = testScope.runTest {
        groups.value = listOf(group, group("g2", relays = listOf("wss://test")))
        coEvery { syncEngine.pullEvents("g1", any(), any(), any(), any()) } returns
            PullResult(0, false) andThen PullResult(0, true)
        coEvery { syncEngine.pullEvents("g2", any(), any(), any(), any()) } returns
            PullResult(0, false) andThen PullResult(0, false) andThen PullResult(0, true)
        start()
        runCurrent()
        elapse(30_000)
        elapse(60_000)

        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        coVerify(exactly = 3) { syncEngine.pullEvents("g2", any(), any(), any(), any()) }
        elapse(120_000)
        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        coVerify(exactly = 3) { syncEngine.pullEvents("g2", any(), any(), any(), any()) }
        verify(exactly = 0) { SyncScheduler.scheduleImmediateSync(context) }
    }

    @Test
    fun `generations during initial and ongoing catch-up are coalesced but never lost`() = testScope.runTest {
        val initialPull = CompletableDeferred<Unit>()
        val recoveryPull = CompletableDeferred<Unit>()
        var pulls = 0
        coEvery { syncEngine.pullEvents("g1", any(), any(), any(), any()) } coAnswers {
            when (++pulls) {
                1 -> initialPull.await()
                2 -> recoveryPull.await()
            }
            PullResult(0, true)
        }
        start()
        runCurrent()
        repeat(5) {
            connectionGeneration.value++
            runCurrent()
        }
        assertEquals(1, pulls)
        initialPull.complete(Unit)
        runCurrent()
        assertEquals(2, pulls)

        repeat(5) {
            connectionGeneration.value++
            runCurrent()
        }
        assertEquals(2, pulls)
        recoveryPull.complete(Unit)
        runCurrent()
        elapse(29_999)
        assertEquals(2, pulls)
        elapse(1)
        assertEquals(3, pulls)
        assertEquals(listOf(1, 1, 1), collectorsAtSubscribe)
        elapse(120_000)
        assertEquals(3, pulls)
    }

    @Test
    fun `a generation arriving during a timer retry remains pending for complete groups too`() = testScope.runTest {
        groups.value = listOf(group, group("g2", relays = listOf("wss://test")))
        coEvery { syncEngine.pullEvents("g1", any(), any(), any(), any()) } returns PullResult(0, false)
        start()
        runCurrent()
        val timerPull = CompletableDeferred<Unit>()
        var retries = 0
        coEvery { syncEngine.pullEvents("g1", any(), any(), any(), any()) } coAnswers {
            if (++retries == 1) timerPull.await()
            PullResult(0, true)
        }
        elapse(30_000)
        connectionGeneration.value++
        runCurrent()
        assertEquals(1, retries)
        coVerify(exactly = 1) { syncEngine.pullEvents("g2", any(), any(), any(), any()) }
        timerPull.complete(Unit)
        runCurrent()

        assertEquals(2, retries)
        coVerify(exactly = 2) { syncEngine.pullEvents("g2", any(), any(), any(), any()) }
        elapse(120_000)
        assertEquals(2, retries)
        verify(exactly = 0) { SyncScheduler.scheduleImmediateSync(context) }
    }

    @Test
    fun `group changes during a pull are reconciled after it without overlapping catch-ups`() = testScope.runTest {
        val pull = CompletableDeferred<Unit>()
        coEvery { syncEngine.pullEvents("g1", any(), any(), any(), any()) } coAnswers {
            pull.await()
            PullResult(0, true)
        }
        start()
        runCurrent()
        val joined = group("g2", relays = listOf("wss://test"))
        groups.value = listOf(joined)
        runCurrent()
        coVerify(exactly = 0) { syncEngine.pullEvents("g2", any(), any(), any(), any()) }
        pull.complete(Unit)
        runCurrent()

        coVerify(exactly = 1) { nostrClient.unsubscribe("g1") }
        coVerify(exactly = 1) { syncEngine.pullEvents("g2", any(), any(), any(), any()) }
        assertEquals(listOf(1, 1), collectorsAtSubscribe)
    }

    @Test
    fun `generation flaps coalesce without resetting the incomplete retry budget`() = testScope.runTest {
        coEvery { syncEngine.pullEvents(any(), any(), any(), any(), any()) } returns PullResult(0, false)
        start()
        runCurrent()
        repeat(5) {
            connectionGeneration.value++
            runCurrent()
        }
        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        elapse(30_000)
        coVerify(exactly = 3) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        elapse(60_000)
        coVerify(exactly = 4) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        elapse(120_000)
        coVerify(exactly = 5) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        verify(exactly = 1) { SyncScheduler.scheduleImmediateSync(context) }

        repeat(10) {
            connectionGeneration.value++
            runCurrent()
        }
        elapse(119_999)
        coVerify(exactly = 5) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        elapse(1)
        coVerify(exactly = 6) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        repeat(10) {
            connectionGeneration.value++
            runCurrent()
        }
        elapse(120_000)
        coVerify(exactly = 7) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        elapse(60_000)
        coVerify(exactly = 7) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        verify(exactly = 1) { SyncScheduler.scheduleImmediateSync(context) }
    }

    @Test
    fun `a later generation can recover an exhausted group without restarting its timers`() = testScope.runTest {
        coEvery { syncEngine.pullEvents(any(), any(), any(), any(), any()) } returns PullResult(0, false)
        start()
        runCurrent()
        elapse(30_000)
        elapse(60_000)
        elapse(120_000)
        coEvery { syncEngine.pullEvents(any(), any(), any(), any(), any()) } returns PullResult(0, true)
        connectionGeneration.value++
        runCurrent()
        elapse(120_000)

        coVerify(exactly = 5) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        elapse(60_000)
        coVerify(exactly = 5) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        verify(exactly = 1) { SyncScheduler.scheduleImmediateSync(context) }
    }

    @Test
    fun `a failed pull is retried on the timer while live subscriptions stay installed`() = testScope.runTest {
        coEvery { syncEngine.pullEvents(any(), any(), any(), any(), any()) } throws
            IOException("relay unavailable") andThen PullResult(0, true)
        start()
        runCurrent()
        coVerify(exactly = 1) { nostrClient.subscribe("g1", any(), "alice") }
        elapse(30_000)

        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        assertEquals(listOf(1, 1), collectorsAtSubscribe)
        verify(exactly = 0) { nostrClient.releaseConnection() }
        verify(exactly = 0) { SyncScheduler.scheduleImmediateSync(context) }
    }

    @Test
    fun `removing an incomplete group cancels its timer retries`() = testScope.runTest {
        groups.value = listOf(group, group("g2", relays = listOf("wss://test")))
        coEvery { syncEngine.pullEvents("g2", any(), any(), any(), any()) } returns PullResult(0, false)
        start()
        runCurrent()
        groups.value = listOf(group)
        runCurrent()
        elapse(60_000)

        coVerify(exactly = 1) { syncEngine.pullEvents("g2", any(), any(), any(), any()) }
        coVerify(exactly = 1) { nostrClient.unsubscribe("g2") }
        verify(exactly = 0) { SyncScheduler.scheduleImmediateSync(context) }
    }

    @Test
    fun `stop cancels pending timer and generation recovery without touching a worker lease`() = testScope.runTest {
        var references = 1 // An already running worker owns this reference.
        coEvery { connectionManager.ensureConnected(any()) } coAnswers {
            references++
            listOf("wss://test")
        }
        every { nostrClient.releaseConnection() } answers {
            references--
            Unit
        }
        coEvery { syncEngine.pullEvents(any(), any(), any(), any(), any()) } returns PullResult(0, false)
        start()
        runCurrent()
        connectionGeneration.value++
        runCurrent()
        connectionGeneration.value++
        runCurrent()
        assertEquals(2, references)
        stop()
        runCurrent()
        connectionGeneration.value++
        elapse(60_000)

        assertEquals(1, references)
        verify(exactly = 1) { nostrClient.releaseConnection() }
        verify(exactly = 0) { nostrClient.disconnect() }
        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        verify(exactly = 0) { SyncScheduler.scheduleImmediateSync(context) }
        assertEquals(0, groups.subscriptionCount.value)
        assertEquals(0, connectionGeneration.subscriptionCount.value)
        assertEquals(0, incoming.subscriptionCount.value)
    }

    @Test
    fun `stop cancels an in-flight timer retry and releases only the live lease`() = testScope.runTest {
        coEvery { syncEngine.pullEvents(any(), any(), any(), any(), any()) } returns PullResult(0, false)
        start()
        runCurrent()
        coEvery { syncEngine.pullEvents(any(), any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        elapse(30_000)
        stop()
        runCurrent()
        elapse(60_000)

        coVerify(exactly = 2) { syncEngine.pullEvents("g1", any(), any(), any(), any()) }
        coVerify(exactly = 1) { nostrClient.subscribe("g1", any(), "alice") }
        verify(exactly = 1) { nostrClient.releaseConnection() }
        verify(exactly = 0) { nostrClient.disconnect() }
        verify(exactly = 0) { SyncScheduler.scheduleImmediateSync(context) }
    }

    @Test
    fun `a live event is processed non-cancellably and only an applied one notifies`() = testScope.runTest {
        val applied = event("applied")
        val known = event("known")
        coEvery { eventProcessor.process(applied, any(), any(), any(), any(), any(), any()) } returns
            processed(IngestOutcome.APPLIED)
        coEvery { eventProcessor.process(known, any(), any(), any(), any(), any(), any()) } returns
            processed(IngestOutcome.ALREADY_APPLIED)
        start()
        runCurrent()

        incoming.tryEmit(applied)
        incoming.tryEmit(known)
        runCurrent()

        coVerify(exactly = 1) { eventProcessor.process(applied, null, null, true, true, any(), null) }
        coVerify(exactly = 1) { eventProcessor.process(known, null, null, true, true, any(), null) }
        verify(exactly = 1) { ExpenseNotifier.notifyIfNeeded(context, "expense", "{}", "bob", "alice", "Trip") }
        verify(exactly = 1) { identity.getPublicKeyHex() }
    }

    @Test
    fun `a live event addressed to another member never reaches the processor`() = testScope.runTest {
        val forAnother = event("rotation-for-carol").copy(tags = listOf(listOf("g", "g1"), listOf("p", "carol")))
        val forMe = event("rotation-for-alice").copy(tags = listOf(listOf("g", "g1"), listOf("p", "alice")))
        coEvery { eventProcessor.process(forMe, any(), any(), any(), any(), any(), any()) } returns
            processed(IngestOutcome.APPLIED)
        start()
        runCurrent()

        incoming.tryEmit(forAnother)
        incoming.tryEmit(forMe)
        runCurrent()

        coVerify(exactly = 0) { eventProcessor.process(forAnother, any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { eventProcessor.process(forMe, null, null, true, true, any(), null) }
    }

    @Test
    fun `an event that arrives while subscribe is still running is processed`() = testScope.runTest {
        val racing = event("racing")
        coEvery { nostrClient.subscribe(any(), any(), any()) } coAnswers { incoming.emit(racing) }
        coEvery { eventProcessor.process(racing, any(), any(), any(), any(), any(), any()) } returns
            processed(IngestOutcome.APPLIED)

        start()
        runCurrent()

        coVerify(exactly = 1) { eventProcessor.process(racing, any(), any(), true, any(), any(), any()) }
    }

    @Test
    fun `a processor failure is contained and later events still flow`() = testScope.runTest {
        val bad = event("bad")
        val good = event("good")
        coEvery { eventProcessor.process(bad, any(), any(), any(), any(), any(), any()) } throws IOException("db")
        coEvery { eventProcessor.process(good, any(), any(), any(), any(), any(), any()) } returns
            processed(IngestOutcome.APPLIED)
        start()
        runCurrent()

        incoming.tryEmit(bad)
        incoming.tryEmit(good)
        runCurrent()

        coVerify(exactly = 1) { eventProcessor.process(good, any(), any(), true, any(), any(), any()) }
        verify(exactly = 0) { nostrClient.releaseConnection() }
    }

    @Test
    fun `a failed connect backs off and retries without holding a reference`() = testScope.runTest {
        coEvery { connectionManager.ensureConnected(true) } throws IOException("offline") andThen listOf("wss://test")
        start()
        runCurrent()
        coVerify(exactly = 1) { connectionManager.ensureConnected(true) }
        coVerify(exactly = 0) { nostrClient.subscribe(any(), any(), any()) }

        elapse(29_999)
        coVerify(exactly = 1) { connectionManager.ensureConnected(true) }

        elapse(1)
        coVerify(exactly = 2) { connectionManager.ensureConnected(true) }
        coVerify(exactly = 1) { nostrClient.subscribe("g1", any(), "alice") }

        stop()
        runCurrent()
        verify(exactly = 1) { nostrClient.releaseConnection() }
    }

    @Test
    fun `a session failure releases the lease, schedules a fallback and restarts after thirty seconds`() =
        testScope.runTest {
            every { identity.getPublicKeyHex() } throws java.security.KeyStoreException("temporarily unavailable")
            start()
            runCurrent()

            verify(exactly = 1) { nostrClient.releaseConnection() }
            verify(exactly = 1) { SyncScheduler.scheduleImmediateSync(context) }
            assertTrue(lastHeartbeat().contains("live_failed"))
            assertEquals(0, incoming.subscriptionCount.value)

            elapse(29_999)
            coVerify(exactly = 1) { connectionManager.ensureConnected(true) }

            every { identity.getPublicKeyHex() } returns "alice"
            elapse(1)
            coVerify(exactly = 2) { connectionManager.ensureConnected(true) }
            coVerify(exactly = 1) { nostrClient.subscribe("g1", any(), "alice") }
            verify(exactly = 1) { nostrClient.releaseConnection() }
        }

    @Test
    fun `repeated quick failures back off further and a stable session resets the back-off`() = testScope.runTest {
        every { identity.getPublicKeyHex() } throws java.security.KeyStoreException("locked")
        start()
        runCurrent()
        coVerify(exactly = 1) { connectionManager.ensureConnected(true) }

        elapse(30_000)
        coVerify(exactly = 2) { connectionManager.ensureConnected(true) }
        elapse(59_999)
        coVerify(exactly = 2) { connectionManager.ensureConnected(true) }

        // The third session holds for over a minute before its group observer dies: back to a 30s restart.
        every { identity.getPublicKeyHex() } returns "alice"
        every { groupRepo.observeAll() } returns flow {
            delay(61_000)
            throw IllegalStateException("query failed")
        }
        elapse(1)
        coVerify(exactly = 3) { connectionManager.ensureConnected(true) }
        elapse(61_000)
        verify(exactly = 3) { SyncScheduler.scheduleImmediateSync(context) }
        elapse(29_999)
        coVerify(exactly = 3) { connectionManager.ensureConnected(true) }
        elapse(1)
        coVerify(exactly = 4) { connectionManager.ensureConnected(true) }
    }

    @Test
    fun `a timeout cancellation from a dependency is a failure, not the end of the session`() = testScope.runTest {
        coEvery { syncEngine.flushOutbox() } coAnswers { withTimeout(1) { awaitCancellation() } }
        start()
        runCurrent()
        elapse(1)

        verify(exactly = 1) { nostrClient.releaseConnection() }
        assertTrue(lastHeartbeat().contains("live_failed"))
        assertTrue(lastHeartbeat().contains(TimeoutCancellationException::class.java.simpleName))
        verify(exactly = 1) { SyncScheduler.scheduleImmediateSync(context) }

        coEvery { syncEngine.flushOutbox() } returns FlushResult(0, 0)
        elapse(30_000)
        coVerify(exactly = 2) { connectionManager.ensureConnected(true) }
        coVerify(exactly = 1) { nostrClient.subscribe("g1", any(), "alice") }
    }

    @Test
    fun `a failing group observer restarts the session instead of escaping the scope`() = testScope.runTest {
        every { groupRepo.observeAll() } returns flow { throw IllegalStateException("query failed") }
        start()
        runCurrent()

        verify(exactly = 1) { nostrClient.releaseConnection() }
        verify(exactly = 1) { SyncScheduler.scheduleImmediateSync(context) }
        stop()
        runCurrent()
        verify(exactly = 1) { nostrClient.releaseConnection() }
    }

    @Test
    fun `a fallback scheduling failure does not stop the lease from being released`() = testScope.runTest {
        every { SyncScheduler.scheduleImmediateSync(any()) } throws IllegalStateException("WorkManager unavailable")
        every { groupRepo.observeAll() } returns flow { throw IllegalStateException("query failed") }
        start()
        runCurrent()

        verify(exactly = 1) { nostrClient.releaseConnection() }
    }
}
