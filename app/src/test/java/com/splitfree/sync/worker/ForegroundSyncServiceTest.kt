package com.splitfree.sync.worker

import android.app.Application
import android.app.Service
import android.content.pm.ServiceInfo
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.util.ProcessHealthTracker
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ForegroundSyncServiceTest {
    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private lateinit var service: ForegroundSyncService
    private val nostrClient = mockk<NostrClient>(relaxed = true)
    private val identity = mockk<IdentityContract>(relaxed = true)
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val connectionManager = mockk<RelayConnectionManager>(relaxed = true)
    private val group = Group(
        "g1",
        "Trip",
        createdBy = "alice",
        createdAt = 0,
        members = listOf("alice"),
        relays = listOf("wss://test")
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(SyncScheduler)
        every { SyncScheduler.scheduleImmediateSync(any()) } returns Unit
        service = spyk(Robolectric.buildService(ForegroundSyncService::class.java).get())
        // Supply deterministic dependencies instead of the generated production component.
        Class.forName("com.splitfree.sync.worker.Hilt_ForegroundSyncService")
            .getDeclaredField("injected").apply { isAccessible = true }.setBoolean(service, true)
        ForegroundSyncService::class.java.getDeclaredField("scope").apply { isAccessible = true }.let {
            (it.get(service) as CoroutineScope).cancel()
            it.set(service, testScope.backgroundScope)
        }
        service.identity = identity
        every { identity.hasIdentity() } returns true
        every { identity.getPublicKeyHex() } returns "alice"
        service.groupRepo = groupRepo
        coEvery { groupRepo.getAll() } returns listOf(group)
        every { groupRepo.observeAll() } returns MutableStateFlow(listOf(group))
        service.nostrClient = nostrClient
        every { nostrClient.connectionState } returns MutableStateFlow(true)
        every { nostrClient.incomingEvents } returns MutableSharedFlow()
        service.relayConnectionManager = connectionManager
        coEvery { connectionManager.resolvePrimaryRelays() } returns group.relays
        coEvery { connectionManager.ensureConnected(any()) } returns group.relays
        service.syncEngine = mockk(relaxed = true)
        service.eventProcessor = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        testScope.backgroundScope.cancel()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `timeout stops unconditionally even when start id is old`() = testScope.runTest {
        service.onCreate()
        runCurrent()
        service.onStartCommand(null, 0, 99)
        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        runCurrent()
        verify(exactly = 1) { service.stopSelf() }
        verify(exactly = 0) { service.stopSelf(any()) }
        verify(exactly = 1) { nostrClient.releaseConnection() }
        verify(exactly = 1) { SyncScheduler.scheduleImmediateSync(service) }
        clearMocks(nostrClient, answers = false)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 100))
        runCurrent()
        verify(exactly = 0) { nostrClient.startListening() }
    }

    @Test
    fun `timeout still stops when fallback scheduling fails`() = testScope.runTest {
        every { SyncScheduler.scheduleImmediateSync(any()) } throws IllegalStateException("database unavailable")
        service.onCreate()
        runCurrent()
        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        runCurrent()
        verify(exactly = 1) { service.stopSelf() }
        verify(exactly = 1) { nostrClient.releaseConnection() }
    }

    @Test
    fun `missing identity stops idle foreground service without acquiring a connection`() = testScope.runTest {
        every { identity.hasIdentity() } returns false
        service.onCreate()
        runCurrent()
        verify(exactly = 1) { service.stopSelf() }
        coVerify(exactly = 0) { connectionManager.ensureConnected(any()) }
        verify(exactly = 0) { nostrClient.releaseConnection() }
    }

    @Test
    fun `fresh install with no groups still connects and subscribes the first group when it appears`() =
        testScope.runTest {
            coEvery { groupRepo.getAll() } returns emptyList()
            coEvery { connectionManager.resolvePrimaryRelays() } returns RelayDefaults.DEFAULT_RELAYS
            val groups = MutableStateFlow<List<Group>>(emptyList())
            every { groupRepo.observeAll() } returns groups
            val syncEngine = service.syncEngine
            service.onCreate()
            runCurrent()
            coVerify(exactly = 1) { connectionManager.ensureConnected(any()) }
            coVerify(exactly = 0) { nostrClient.subscribe(any(), any(), any()) }
            coVerify(exactly = 1) { syncEngine.flushOutbox() }
            assertEquals(1, groups.subscriptionCount.value)

            groups.value = listOf(group.copy(relays = RelayDefaults.DEFAULT_RELAYS))
            runCurrent()
            coVerify(exactly = 1) { nostrClient.subscribe(group.id, any(), "alice") }
            coVerify(exactly = 2) { syncEngine.flushOutbox() }
            coVerify(exactly = 1) { connectionManager.ensureConnected(any()) }
        }

    @Test
    fun `destroying a service with no groups cancels its observer and releases the connection`() = testScope.runTest {
        coEvery { groupRepo.getAll() } returns emptyList()
        coEvery { connectionManager.resolvePrimaryRelays() } returns RelayDefaults.DEFAULT_RELAYS
        val groups = MutableStateFlow<List<Group>>(emptyList())
        every { groupRepo.observeAll() } returns groups
        service.onCreate()
        runCurrent()
        assertEquals(1, groups.subscriptionCount.value)
        verify(exactly = 0) { nostrClient.releaseConnection() }
        service.onDestroy()
        runCurrent()
        assertEquals(0, groups.subscriptionCount.value)
        verify(exactly = 1) { nostrClient.releaseConnection() }
    }

    @Test
    fun `promotion refusal is contained inside service creation`() = testScope.runTest {
        every { service.startForeground(any(), any()) } throws IllegalStateException("time budget exhausted")
        service.onCreate()
        runCurrent()
        verify(exactly = 1) { service.stopSelf() }
        verify(exactly = 0) { identity.hasIdentity() }
    }

    @Test
    fun `storage failure after acquisition releases connection and stops service`() = testScope.runTest {
        every { identity.getPublicKeyHex() } throws java.security.KeyStoreException("temporarily unavailable")
        service.onCreate()
        runCurrent()
        verify(exactly = 1) { nostrClient.releaseConnection() }
        verify(exactly = 1) { service.stopSelf() }
        verify(exactly = 1) { SyncScheduler.scheduleImmediateSync(service) }
        assertTrue(ProcessHealthTracker.buildReport(service).contains("fg_sync_failed"))
    }

    @Test
    fun `observer failure cancels the session instead of escaping to process handler`() = testScope.runTest {
        every { groupRepo.observeAll() } returns flow { throw IllegalStateException("query failed") }
        service.onCreate()
        runCurrent()
        verify(exactly = 1) { service.stopSelf() }
        verify(exactly = 1) { nostrClient.releaseConnection() }
    }

    @Test
    fun `reconnect balances temporary reference and destruction releases session reference`() = testScope.runTest {
        every { groupRepo.observeAll() } returns flowOf(listOf(group.copy(relays = listOf("wss://other"))))
        service.onCreate()
        runCurrent()
        verify(exactly = 1) { nostrClient.releaseConnection() }
        service.onDestroy()
        runCurrent()
        verify(exactly = 2) { nostrClient.releaseConnection() }
    }
}
