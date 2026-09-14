package com.splitfree.data.nostr.relay

import com.splitfree.data.nostr.NostrClient
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.util.RelayDefaults
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RelayConnectionManagerTest {
    private val dispatcher = StandardTestDispatcher()
    private val anyRelayUp = MutableStateFlow(false)
    private val nostrClient = mockk<NostrClient>(relaxed = true) {
        every { isConnected } answers { anyRelayUp.value }
        every { connectionState } returns anyRelayUp
        every { currentRelayUrls() } returns listOf("wss://already")
    }
    private val groupRepo = mockk<GroupRepositoryContract> { coEvery { getAll() } returns emptyList() }
    private val healthMonitor = mockk<RelayHealthMonitor> { every { getOnlineRelays(any()) } returns emptyList() }
    private val manager = RelayConnectionManager(nostrClient, groupRepo, healthMonitor, mockk<EventSigner>())

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() = unmockkStatic(android.util.Log::class)

    @Test
    fun `already connected fast path takes one reference without reconnecting`() = runTest(dispatcher) {
        anyRelayUp.value = true

        val relays = manager.ensureConnected()

        assertEquals(listOf("wss://already"), relays)
        verify(exactly = 1) { nostrClient.acquireConnection() }
        verify(exactly = 0) { nostrClient.releaseConnection() }
        coVerify(exactly = 0) { nostrClient.connect(any()) }
    }

    @Test
    fun `cold connect takes one reference and returns once a relay is up`() = runTest(dispatcher) {
        var relays: List<String>? = null
        val job = launch { relays = manager.ensureConnected() }
        runCurrent()
        assertTrue("must still be waiting for the first relay", job.isActive)
        verify(exactly = 1) { nostrClient.acquireConnection() }

        anyRelayUp.value = true
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals((RelayDefaults.DEFAULT_RELAYS + RelayDefaults.FALLBACK_RELAYS).distinct(), relays)
        coVerify(exactly = 1) { nostrClient.connect(any()) }
        verify(exactly = 1) { nostrClient.acquireConnection() }
        verify(exactly = 0) { nostrClient.releaseConnection() }
    }

    @Test
    fun `cancellation while waiting releases the reference and propagates`() = runTest(dispatcher) {
        var outcome: Result<List<String>>? = null
        val job = launch { outcome = runCatching { manager.ensureConnected() } }
        runCurrent()
        verify(exactly = 1) { nostrClient.acquireConnection() }
        verify(exactly = 0) { nostrClient.releaseConnection() }

        job.cancel()
        runCurrent()

        assertTrue("cancellation must propagate, not be swallowed", outcome?.exceptionOrNull() is CancellationException)
        verify(exactly = 1) { nostrClient.acquireConnection() }
        verify(exactly = 1) { nostrClient.releaseConnection() }
    }

    @Test
    fun `a connect timeout still returns holding the reference`() = runTest(dispatcher) {
        val relays = manager.ensureConnected()

        assertEquals(5_000L, currentTime)
        assertTrue(relays.isNotEmpty())
        verify(exactly = 1) { nostrClient.acquireConnection() }
        verify(exactly = 0) { nostrClient.releaseConnection() }
    }

    @Test
    fun `primary relays are the groups' distinct relays, or the defaults when no group has any`() {
        fun group(vararg relays: String) =
            Group("g", "g", createdBy = "a", createdAt = 1, members = listOf("a"), relays = relays.toList())

        assertEquals(RelayDefaults.DEFAULT_RELAYS, manager.primaryRelaysOf(emptyList()))
        assertEquals(RelayDefaults.DEFAULT_RELAYS, manager.primaryRelaysOf(listOf(group())))
        assertEquals(
            listOf("wss://a", "wss://b"),
            manager.primaryRelaysOf(listOf(group("wss://a", "wss://b"), group("wss://b")))
        )
    }
}
