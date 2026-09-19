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
import com.splitfree.data.nostr.relay.Relay
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
import com.splitfree.domain.model.group.GroupMeta
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.IOException
import java.time.Duration
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * Real LiveSync, Relay (including onOpen/re-REQ), NostrClient, connection manager, SyncEngine,
 * Room DAOs, signatures, encryption, validation and balances. Only WebSocket/server IO is simulated;
 * notification posting, health probing and unrelated membership side effects are mocked.
 * Server histories differ deliberately: publication succeeds if ANY relay accepts, not all relays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RelayRecoveryLedgerTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val dispatcher = StandardTestDispatcher()
    private val ts = TestScope(dispatcher)
    private val scope get() = ts.backgroundScope
    private val context = RuntimeEnvironment.getApplication()
    private val alice = TestIdentity(31)
    private val bob = TestIdentity(32)
    private val ephemeral = TestIdentity(33)
    private val owner = Owner()
    private val encryption = GroupEncryption(CompressionUtil)
    private val key = encryption.generateGroupKey()
    private val groupId = "relay-recovery-ledger"
    private val now = System.currentTimeMillis() / 1000
    private val primaryUrl = "wss://primary.invalid"
    private val servers = linkedMapOf<String, Endpoint>()
    private lateinit var db: AppDatabase
    private lateinit var groups: GroupRepository
    private lateinit var client: NostrClient
    private lateinit var manager: RelayConnectionManager
    private lateinit var processor: EventProcessor
    private lateinit var engine: SyncEngine
    private lateinit var balances: ComputeBalancesUseCase
    private lateinit var live: LiveSync
    private lateinit var primary: Endpoint

    /** Controlled remote server, behind REAL Relay WebSocketListener callbacks and serialized NIP01 frames. */
    private inner class Endpoint(val url: String, var autoOpen: Boolean = true) {
        val history = mutableListOf<NostrEvent>()
        val requests = mutableListOf<Pair<String, List<JSONObject>>>()
        var stallOldHistory = false
        var failNextFetches = 0
        val connections = mutableListOf<Connection>()
        fun newConnection(request: Request, listener: WebSocketListener): WebSocket {
            val connection = Connection(request, listener)
            connections += connection
            if (autoOpen) scope.launch { connection.open() }
            return connection.socket
        }
        fun latest() = connections.last()
        fun fetchCount() = requests.count { ":fetch:" in it.first }

        inner class Connection(val request: Request, val listener: WebSocketListener) {
            var opened = false
            val queued = mutableListOf<String>()
            val active = mutableMapOf<String, List<JSONObject>>()
            val responses = mutableMapOf<String, Job>()
            val socket = mockk<WebSocket>(relaxed = true)
            init {
                every { socket.send(any<String>()) } answers {
                    val text = firstArg<String>()
                    if (opened) receive(text) else queued += text
                    true
                }
                every { socket.close(any(), any()) } answers { true }
            }
            fun open() {
                if (opened) return
                opened = true
                listener.onOpen(socket, mockk<Response>(relaxed = true))
                val buffered = queued.toList()
                queued.clear()
                buffered.forEach(::receive)
            }
            fun breakConnection() {
                opened = false
                responses.values.forEach { it.cancel() }
                active.clear()
                listener.onFailure(socket, IOException("simulated link lost"), null)
            }
            fun publish(event: NostrEvent) {
                active.forEach { (id, filters) ->
                    if (filters.any { matches(event, it) }) {
                        listener.onMessage(socket, "[\"EVENT\",${JSONObject.quote(id)},${event.toJson()}]")
                    }
                }
            }
            private fun receive(text: String) {
                val frame = JSONArray(text)
                when (frame.getString(0)) {
                    "REQ" -> {
                        val id = frame.getString(1)
                        val filters = (2 until frame.length()).map { frame.getJSONObject(it) }
                        requests += id to filters
                        active[id] = filters
                        responses.remove(id)?.cancel()
                        val fail = ":fetch:" in id && failNextFetches > 0
                        if (fail) failNextFetches--
                        responses[id] = scope.launch {
                            val eligible = filters.flatMap { filter ->
                                history.filter { matches(it, filter) }.sortedByDescending { it.createdAt }
                                    .take(filter.optInt("limit", Int.MAX_VALUE))
                            }.distinctBy { it.id }.sortedByDescending { it.createdAt }
                            val withheld = stallOldHistory && ":fetch:" in id
                            for (event in eligible.filter { !withheld || it.createdAt >= now - 60 }) {
                                listener.onMessage(socket, "[\"EVENT\",${JSONObject.quote(id)},${event.toJson()}]")
                            }
                            if (fail) {
                                delay(100)
                                autoOpen = false
                                breakConnection()
                            } else if (!withheld) {
                                listener.onMessage(socket, "[\"EOSE\",${JSONObject.quote(id)}]")
                            }
                        }
                    }
                    "EVENT" -> {
                        val event = checkNotNull(NostrEvent.fromJson(frame.getJSONObject(1).toString()))
                        check(event.verify())
                        if (history.none { it.id == event.id }) history += event
                        connections.filter { it.opened }.forEach { it.publish(event) }
                        listener.onMessage(socket, JSONArray(listOf("OK", event.id, true, "stored")).toString())
                    }
                    "CLOSE" -> {
                        val id = frame.getString(1)
                        active.remove(id)
                        responses.remove(id)?.cancel()
                    }
                }
            }
        }
    }

    private fun matches(e: NostrEvent, filter: JSONObject): Boolean {
        if (filter.has("since") && e.createdAt < filter.getLong("since")) return false
        if (filter.has("until") && e.createdAt > filter.getLong("until")) return false
        filter.optJSONArray("kinds")?.let { a ->
            if ((0 until a.length()).none { a.getInt(it) == e.kind }) return false
        }
        for (tag in listOf("g", "p")) {
            filter.optJSONArray("#$tag")?.let { a ->
                val wanted = (0 until a.length()).map { a.getString(it) }
                if (e.tags.none { it.size > 1 && it[0] == tag && it[1] in wanted }) return false
            }
        }
        return true
    }

    @Before
    fun setup() = runBlocking {
        com.splitfree.util.DebugLog.clear()
        mockkObject(Relay.sharedClient, SyncScheduler, ExpenseNotifier)
        every { SyncScheduler.scheduleImmediateSync(any()) } returns Unit
        every { ExpenseNotifier.notifyIfNeeded(any(), any(), any(), any(), any(), any()) } returns Unit
        for (identity in listOf(alice, bob)) {
            every { identity.contract.hasIdentity() } returns true
            every { identity.contract.observeHasIdentity() } returns kotlinx.coroutines.flow.MutableStateFlow(true)
            every { identity.contract.getPublicKeyBytes() } answers { identity.pub.hexToBytes() }
        }
        primary = Endpoint(primaryUrl, autoOpen = false)
        servers[primaryUrl] = primary
        RelayDefaults.FALLBACK_RELAYS.forEach { servers[it] = Endpoint(it) }
        val http = Relay.sharedClient
        every { http.newWebSocket(any(), any()) } answers {
            val request = firstArg<Request>()
            val url = "wss://${request.url.host}" + request.url.encodedPath.removeSuffix("/")
            checkNotNull(servers[url]) { "Unexpected external connection $url" }
                .newConnection(request, secondArg())
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
        client = NostrClient(scope)
        val health = mockk<RelayHealthMonitor>()
        coEvery { health.getOnlineRelays(any()) } answers { firstArg<List<String>>() }
        manager = RelayConnectionManager(client, groups, health, signer)
        engine =
            SyncEngine(db.eventDao(), db.outboxDao(), groups, client, bob.contract, processor, RelaySyncCursors(db))
        balances = ComputeBalancesUseCase(EventRepository(db, db.eventDao()), groups, encryption)
        live = LiveSync(client, groups, bob.contract, processor, manager, engine, context, scope)
        live.bind(owner.registry)
    }

    @After
    fun cleanup() {
        println(com.splitfree.util.DebugLog.entries.joinToString("\n") { "${it.tag}: ${it.message}" })
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        client.disconnect()
        db.close()
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
    private fun TestScope.tick(ms: Long) {
        // Keep Android uptime aligned with coroutine time, including work scheduled mid-advance.
        repeat((ms / 1_000).toInt()) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(1_000))
            advanceTimeBy(1_000)
            runCurrent()
        }
        val remainder = ms % 1_000
        if (remainder > 0) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(remainder))
            advanceTimeBy(remainder)
            runCurrent()
        }
    }
    private suspend fun cursor() = checkNotNull(groups.getGroupEntity(groupId)).lastSyncTimestamp
    private suspend fun incrementalWorker() = SyncWorker(
        context,
        mockk<WorkerParameters>(relaxed = true),
        groups,
        client,
        bob.contract,
        manager,
        engine
    ).doWork()

    private suspend fun relayCursors() = RelaySyncCursors(db).cursors(groupId, bob.pub)

    private fun TestScope.recoverExcludedPrimary(inner: NostrEvent, envelope: NostrEvent) = runTest {
        primary.history += envelope
        start()
        tick(6_000)
        assertTrue(client.connectionState.value)
        assertTrue("fallback makes independent progress", cursor() >= now)
        assertNull("excluded primary has no trusted coverage", relayCursors()[primaryUrl])
        assertEquals(RelayDefaults.FALLBACK_RELAYS.toSet(), relayCursors().keys)
        assertEquals(0L, bobNet())
        assertNull(db.eventDao().getEvent(inner.id))
        assertEquals(0, primary.fetchCount())
        val generation = client.connectionGeneration.value
        primary.latest().open()
        tick(35_000)
        assertTrue(client.connectionState.value)
        assertTrue(client.connectionGeneration.value > generation)
        assertTrue("independent relay recovery triggers historical fetch", primary.fetchCount() > 0)
        assertTrue(primary.requests.filter { ":fetch:" in it.first }.first().second.all { !it.has("since") })
        assertNotNull("automatic catch-up recovers the old expense", db.eventDao().getEvent(inner.id))
        assertEquals(-500L, bobNet())
        assertTrue(checkNotNull(relayCursors()[primaryUrl]) >= now)
        assertEquals(ListenableWorker.Result.success(), async { incrementalWorker() }.await())
        assertEquals("worker replay must not double count", -500L, bobNet())
    }

    @Test
    fun `excluded primary direct history recovers automatically with correct balance`() {
        val inner = signedExpense(2 * 3600)
        ts.recoverExcludedPrimary(inner, inner)
    }

    @Test
    fun `excluded primary old gift wrap outside live window recovers automatically`() {
        val inner = signedExpense(60 * 3600)
        ts.recoverExcludedPrimary(inner, wrapped(inner, 72 * 3600))
    }

    @Test
    fun `coverage debt older than live timestamp limit is historical ingestion`() {
        val inner = signedExpense(45 * 86400)
        ts.recoverExcludedPrimary(inner, inner)
    }

    @Test
    fun `ordinary background worker recovers debt after foreground session and engine are replaced`() = ts.runTest {
        val inner = signedExpense(60 * 3600)
        primary.history += wrapped(inner, 72 * 3600)
        start()
        tick(6_000)
        assertTrue(cursor() >= now)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        runCurrent()
        assertFalse(client.isConnected)
        // Reconstruct the engine/repository adapter, not an in-memory debt set, and use ordinary worker.
        engine =
            SyncEngine(db.eventDao(), db.outboxDao(), groups, client, bob.contract, processor, RelaySyncCursors(db))
        primary.autoOpen = true
        assertEquals(ListenableWorker.Result.success(), async { incrementalWorker() }.await())
        assertNotNull(db.eventDao().getEvent(inner.id))
        assertEquals(-500L, bobNet())
        assertTrue(checkNotNull(relayCursors()[primaryUrl]) >= now)
    }

    @Test
    fun `old expense blocked by missing participant join stays recoverable across worker runs`() = ts.runTest {
        groups.save(checkNotNull(groups.getById(groupId)).copy(members = listOf(alice.pub)), key)
        val expense = signedExpense(60 * 3600)
        primary.history += expense
        primary.autoOpen = true
        assertEquals(ListenableWorker.Result.retry(), async { incrementalWorker() }.await())
        assertNull(db.eventDao().getEvent(expense.id))
        assertTrue("unstored dependency cannot certify coverage", relayCursors().isEmpty())
        val join = EventSigner(bob.contract).createSignedEvent(
            groupId,
            "group_meta",
            encryption.encrypt(
                Json.encodeToString(
                    GroupMeta(
                        name = "Trip",
                        createdBy = alice.pub,
                        members = listOf(alice.pub, bob.pub),
                        relays = listOf(primaryUrl)
                    )
                ),
                key
            ),
            createdAt = now - 70 * 3600
        )
        primary.history += join
        assertEquals(ListenableWorker.Result.success(), async { incrementalWorker() }.await())
        assertTrue(checkNotNull(groups.getById(groupId)).members.contains(bob.pub))
        assertNotNull(db.eventDao().getEvent(expense.id))
        assertEquals(-500L, bobNet())
        assertTrue(checkNotNull(relayCursors()[primaryUrl]) >= now)
    }

    @Test
    fun `other members gift wraps do not poison this recipients coverage`() = ts.runTest {
        val inner = signedExpense(60 * 3600)
        primary.history += wrapped(inner, 72 * 3600, to = alice)
        primary.history += wrapped(inner, 72 * 3600, to = bob)
        primary.autoOpen = true
        assertEquals(ListenableWorker.Result.success(), async { incrementalWorker() }.await())
        assertNotNull(db.eventDao().getEvent(inner.id))
        assertEquals(-500L, bobNet())
        assertTrue(checkNotNull(relayCursors()[primaryUrl]) >= now)
    }

    @Test
    fun `old gift wrap arrives through real Relay live publication without reconnect`() = ts.runTest {
        primary.autoOpen = true
        start()
        tick(6_000)
        val inner = signedExpense(60 * 3600)
        val envelope = wrapped(inner, 72 * 3600)
        val generation = client.connectionGeneration.value
        assertTrue(async { client.publish(envelope) }.await())
        runCurrent()
        assertNotNull(db.eventDao().getEvent(inner.id))
        assertEquals(-500L, bobNet())
        assertEquals(generation, client.connectionGeneration.value)
    }

    @Test
    fun `incomplete old history is retried on primary reconnect while fallback stays connected`() = ts.runTest {
        val old = signedExpense(60 * 3600)
        primary.history += wrapped(old, 72 * 3600)
        primary.autoOpen = true
        primary.stallOldHistory = true
        start()
        tick(20_000)
        assertTrue(client.connectionState.value)
        val initialFetches = primary.fetchCount()
        assertTrue(initialFetches > 0)
        assertNull(relayCursors()[primaryUrl])
        assertNull(db.eventDao().getEvent(old.id))
        primary.stallOldHistory = false
        primary.latest().breakConnection()
        tick(35_000)
        assertTrue(client.connectionState.value)
        assertEquals(Relay.State.CONNECTED, clientState(primaryUrl))
        assertTrue(primary.fetchCount() > initialFetches)
        assertNotNull("reconnect caught up without worker or daily reconciliation", db.eventDao().getEvent(old.id))
        assertEquals(-500L, bobNet())
    }

    @Test
    fun `incomplete history is retried by bounded timer without any connectivity change`() = ts.runTest {
        val old = signedExpense(60 * 3600)
        primary.history += wrapped(old, 72 * 3600)
        primary.autoOpen = true
        primary.stallOldHistory = true
        start()
        tick(20_000)
        assertNull(db.eventDao().getEvent(old.id))
        val generation = client.connectionGeneration.value
        primary.stallOldHistory = false
        tick(50_000)
        assertEquals(generation, client.connectionGeneration.value)
        assertNotNull(db.eventDao().getEvent(old.id))
        assertEquals(-500L, bobNet())
        verify(exactly = 0) { SyncScheduler.scheduleImmediateSync(any()) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun clientState(url: String): Relay.State {
        val field = NostrClient::class.java.getDeclaredField("relays").apply { isAccessible = true }
        return (field.get(client) as Map<String, Relay>).getValue(url).state.value
    }
}
