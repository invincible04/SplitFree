package com.splitfree.sync.worker

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.repository.RelaySyncCursors
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.crypto.nip.Nip59
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.sync.event.EventPostProcessor
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.ExpenseNotifier
import com.splitfree.sync.event.MembershipHistory
import com.splitfree.sync.nearby.TestIdentity
import com.splitfree.test.FakeSecureStorage
import com.splitfree.test.LoopbackNostrRelay
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.Duration
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/** Real local TLS/WebSocket, signed direct/NIP59 events, Room, processor, balances, LiveSync and SyncWorker. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LatePublicationLoopbackTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context = RuntimeEnvironment.getApplication()
    private val alice = TestIdentity(31)
    private val bob = TestIdentity(32)
    private val ephemeral = TestIdentity(33)
    private val owner = Owner()
    private val encryption = GroupEncryption(CompressionUtil)
    private val key = encryption.generateGroupKey()
    private val groupId = "late-publication-loopback"
    private val now = System.currentTimeMillis() / 1000
    private lateinit var relay: LoopbackNostrRelay
    private lateinit var db: AppDatabase
    private lateinit var groups: GroupRepository
    private lateinit var client: NostrClient
    private lateinit var manager: RelayConnectionManager
    private lateinit var processor: EventProcessor
    private lateinit var engine: SyncEngine
    private lateinit var balances: ComputeBalancesUseCase
    private lateinit var live: LiveSync
    private val notified = CompletableDeferred<Unit>()
    private val primaryUrl get() = relay.url

    @Before
    fun setup() = runBlocking {
        relay = LoopbackNostrRelay()
        mockkObject(RelayDefaults, SyncScheduler, ExpenseNotifier)
        every { RelayDefaults.FALLBACK_RELAYS } returns emptyList()
        every { RelayDefaults.DEFAULT_RELAYS } returns listOf(relay.url)
        every { SyncScheduler.scheduleImmediateSync(any()) } returns Unit
        every { ExpenseNotifier.notifyIfNeeded(any(), any(), any(), any(), any(), any()) } answers {
            notified.complete(Unit)
            Unit
        }
        for (identity in listOf(alice, bob)) {
            every { identity.contract.hasIdentity() } returns true
            every { identity.contract.observeHasIdentity() } returns kotlinx.coroutines.flow.MutableStateFlow(true)
            every { identity.contract.getPublicKeyBytes() } answers { identity.pub.hexToBytes() }
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
            .allowMainThreadQueries().setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() }).build()
        groups = GroupRepository(db.groupDao(), FakeSecureStorage())
        groups.save(
            Group(
                groupId,
                "Trip",
                createdBy = alice.pub,
                createdAt = now - 90 * 86400,
                members = listOf(alice.pub, bob.pub),
                relays = listOf(primaryUrl)
            ),
            key
        )
        groups.updateLastSync(groupId, now - 7 * 86400)
        val settings = mockk<SettingsContract>().also { every { it.giftWrapEnabled } returns true }
        val signer = EventSigner(bob.contract)
        processor = EventProcessor(
            db.eventDao(), groups, encryption, signer, bob.contract,
            GiftWrapService(bob.contract, settings), EventValidator(),
            EventPostProcessor(
                groups,
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                bob.contract,
                scope
            ),
            MembershipHistory(db.eventDao(), groups, bob.contract)
        )
        client = NostrClient(scope, relay.client)
        val health = mockk<RelayHealthMonitor>()
        coEvery { health.getOnlineRelays(any()) } answers { firstArg<List<String>>() }
        manager = RelayConnectionManager(client, groups, health, signer)
        engine = newEngine()
        balances = ComputeBalancesUseCase(EventRepository(db, db.eventDao()), groups, encryption)
        live = LiveSync(client, groups, bob.contract, processor, manager, engine, context, scope)
        live.bind(owner.registry)
        client.connect(listOf(relay.url))
        client.acquireConnection()
        withTimeout(10_000) { client.connectionState.first { it } }
        Unit
    }

    private fun newEngine() =
        SyncEngine(db.eventDao(), db.outboxDao(), groups, client, bob.contract, processor, RelaySyncCursors(db))

    @After
    fun cleanup() {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        client.disconnect()
        scope.cancel()
        relay.close()
        db.close()
        assertTrue(relay.failures.toString(), relay.failures.isEmpty())
        unmockkAll()
    }

    private fun signedExpense(age: Long, id: String = "missing-expense"): NostrEvent {
        val at = now - age
        val expense = Expense(
            id = id,
            amount = 1000,
            currency = "INR",
            description = id,
            paidBy = alice.pub,
            splitType = SplitType.EQUAL,
            splitAmong = listOf(SplitEntry(alice.pub, 500), SplitEntry(bob.pub, 500)),
            timestamp = at
        )
        return EventSigner(alice.contract).createSignedEvent(
            groupId,
            "expense",
            encryption.encrypt(Json.encodeToString(expense), key),
            expenseUuid = id,
            createdAt = at
        )
            .also { assertTrue(it.verify()) }
    }

    /** Historical NIP59 creation with explicit timestamps, real sender seal and ephemeral outer signature. */
    private fun wrapped(inner: NostrEvent, outerAge: Long, to: TestIdentity = bob): NostrEvent {
        val recipient = to.pub.hexToBytes()
        val seal = NostrEvent(
            pubkey = alice.pub,
            createdAt = now - outerAge,
            kind = 13,
            content = Nip44.encrypt(inner.copy(sig = "").toJson(), Nip44.getConversationKey(alice.priv, recipient))
        )
            .sign(alice.priv)
        return NostrEvent(
            pubkey = ephemeral.pub,
            createdAt = now - outerAge,
            kind = 1059,
            tags = listOf(listOf("p", to.pub), listOf("g", groupId)),
            content = Nip44.encrypt(seal.toJson(), Nip44.getConversationKey(ephemeral.priv, recipient))
        )
            .sign(ephemeral.priv).also {
                assertTrue(it.verify())
                assertEquals(inner.id, Nip59.unwrap(it, to.priv)?.rumor?.id)
            }
    }

    private suspend fun bobNet(): Long = balances.computeWithExclusions(groupId).balances
        .filter { it.pubkey == bob.pub }.sumOf { it.net }
    private fun start() = owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

    private suspend fun ordinaryWorker() = SyncWorker(
        context,
        mockk<WorkerParameters>(relaxed = true),
        groups,
        client,
        bob.contract,
        manager,
        engine
    ).doWork()

    private suspend fun publish(envelope: NostrEvent) {
        val sender = NostrClient(scope, relay.client)
        try {
            sender.connect(listOf(relay.url))
            withTimeout(10_000) { sender.connectionState.first { it } }
            assertTrue(withTimeout(10_000) { sender.publish(envelope) })
        } finally {
            sender.disconnect()
        }
    }

    private fun assertLatePublication(wrap: Boolean, foreground: Boolean) = runBlocking {
        assertEquals(ListenableWorker.Result.success(), ordinaryWorker())
        val inner = signedExpense(if (wrap) 72 * 3600 else 45 * 86400)
        val envelope = if (wrap) wrapped(inner, 73 * 3600) else inner
        assertNull(db.eventDao().getEvent(inner.id))
        if (foreground) {
            val subscribed = CompletableDeferred<Unit>()
            relay.respond = { request ->
                relay.sendHistory(request)
                if (request.filters.all { it.optInt("limit", -1) == 0 }) subscribed.complete(Unit)
            }
            start()
            withTimeout(10_000) { subscribed.await() }
        }
        val generation = client.connectionGeneration.value
        publish(envelope)
        if (foreground) {
            withTimeout(10_000) { notified.await() }
        } else {
            engine = newEngine()
            assertEquals(ListenableWorker.Result.success(), ordinaryWorker())
        }
        assertEquals(generation, client.connectionGeneration.value)
        assertNotNull(db.eventDao().getEvent(inner.id))
        assertEquals(-500L, bobNet())
        assertEquals(ListenableWorker.Result.success(), ordinaryWorker())
        assertEquals(ListenableWorker.Result.success(), ordinaryWorker())
        assertEquals(1, db.eventDao().getEventCount(groupId))
        assertEquals(-500L, bobNet())
        verify(exactly = 1) { ExpenseNotifier.notifyIfNeeded(any(), "expense", any(), alice.pub, bob.pub, any()) }
    }

    @Test fun `old direct publication arrives on healthy live stream exactly once`() = assertLatePublication(
        false,
        true
    )

    @Test fun `old wrapped publication arrives on healthy live stream exactly once`() = assertLatePublication(
        true,
        true
    )

    @Test fun `ordinary periodic worker discovers late direct event after completed sweep`() =
        assertLatePublication(false, false)

    @Test fun `ordinary periodic worker discovers late wrap after completed sweep`() = assertLatePublication(
        true,
        false
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `healthy foreground periodic sweep recovers old publication omitted from live delivery`() = runBlocking {
        val testScope = TestScope(UnconfinedTestDispatcher())
        val periodicOwner = Owner()
        val periodicLive =
            LiveSync(client, groups, bob.contract, processor, manager, engine, context, testScope.backgroundScope)
        periodicLive.bind(periodicOwner.registry)
        val subscribed = CompletableDeferred<Unit>()
        relay.respond = { request ->
            relay.sendHistory(request)
            if (request.filters.all { it.optInt("limit", -1) == 0 }) subscribed.complete(Unit)
        }
        try {
            periodicOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            withTimeout(10_000) { subscribed.await() }
            val generation = client.connectionGeneration.value
            relay.deliverLive = false
            val inner = signedExpense(45 * 86400)
            publish(wrapped(inner, 46 * 86400))
            assertNull(db.eventDao().getEvent(inner.id))
            ShadowSystemClock.advanceBy(Duration.ofMillis(LiveSync.RECONCILE_INTERVAL_MS))
            testScope.testScheduler.advanceTimeBy(LiveSync.RECONCILE_INTERVAL_MS)
            testScope.testScheduler.runCurrent()
            withTimeout(10_000) { notified.await() }
            assertNotNull(db.eventDao().getEvent(inner.id))
            assertEquals(generation, client.connectionGeneration.value)
            assertEquals(-500L, bobNet())
            assertEquals(ListenableWorker.Result.success(), ordinaryWorker())
            verify(exactly = 1) { ExpenseNotifier.notifyIfNeeded(any(), "expense", any(), alice.pub, bob.pub, any()) }
        } finally {
            periodicOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            testScope.backgroundScope.cancel()
        }
    }

    @Test
    fun `large history resumes durable partitions across replacement engines`() = runBlocking {
        val events = (0 until 300).map { signedExpense(2 * 3600, "expense-$it") }
        relay.history.addAll(events)
        var passes = 0
        do {
            engine = newEngine()
            val result = withTimeout(30_000) { engine.pullEvents(groupId, now, key) }
            passes++
            assertTrue("bounded sweep must progress", passes < 30)
            if (!result.complete) assertTrue(RelaySyncCursors(db).sweeps(groupId, bob.pub).isNotEmpty())
        } while (!result.complete)
        assertTrue(passes > 1)
        assertEquals(300, db.eventDao().getEventCount(groupId))
        assertEquals(-150_000L, bobNet())
        assertTrue(
            RelaySyncCursors(db).sweeps(groupId, bob.pub).values.all {
                it.pending.isEmpty() && !it.hadUnresolved
            }
        )
    }
}
