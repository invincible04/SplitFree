package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.splitfree.data.ble.BleEvent
import com.splitfree.data.ble.BleHandshake
import com.splitfree.data.ble.BleTransfer
import com.splitfree.data.ble.NearbyPeer
import com.splitfree.data.ble.NearbySync
import com.splitfree.data.local.dao.EventDao
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.sync.worker.PowerManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the BLE event collector: a failure while handling one event
 * must not terminate collection (the collector is the screen's only event source,
 * and an uncaught throw in [androidx.lifecycle.viewModelScope] kills the process).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbySyncViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val nearbySync = mockk<NearbySync>(relaxed = true)
    private val bleTransfer = mockk<BleTransfer>(relaxed = true)
    private val identity = mockk<IdentityContract>(relaxed = true)
    private val groupRepo = mockk<GroupRepositoryContract>(relaxed = true)
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val powerManager = mockk<PowerManager>(relaxed = true)

    private val events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 16)

    private lateinit var vm: NearbySyncViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        every { nearbySync.events } returns events
        every { identity.getPublicKeyHex() } returns OUR_PUB
        coEvery { groupRepo.getAll() } returns emptyList()
        coEvery { eventDao.getEventIds(any()) } returns emptyList()

        vm = NearbySyncViewModel(
            SavedStateHandle(mapOf("groupId" to "g1")),
            nearbySync,
            bleTransfer,
            identity,
            groupRepo,
            eventDao,
            powerManager
        )
    }

    @After
    fun teardown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `payload failure is reported and collector keeps processing later events`() = runTest {
        coEvery { bleTransfer.processPayload("peer-1", any()) } throws SerializationException("malformed")

        events.emit(BleEvent.PayloadReceived("peer-1", byteArrayOf(0x01, 0x7B)))

        assertEquals("Could not process peer data", vm.uiState.value.status)
        assertFalse(vm.uiState.value.syncing)

        // A later valid event from an authenticated peer is still handled end to end.
        every { bleTransfer.isAuthenticated("peer-2") } returns true
        coEvery { bleTransfer.processPayload("peer-2", any()) } returns listOf("g1")

        events.emit(BleEvent.PayloadReceived("peer-2", byteArrayOf(0x04)))

        coVerify { bleTransfer.sendSyncRequest("peer-2", "g1", emptyList()) }
        assertEquals("Syncing with peer…", vm.uiState.value.status)
    }

    @Test
    fun `peer discovery still works after a handler failure`() = runTest {
        coEvery { bleTransfer.processPayload(any(), any()) } throws SerializationException("malformed")
        events.emit(BleEvent.PayloadReceived("peer-1", byteArrayOf(0x02)))

        events.emit(BleEvent.PeerFound(NearbyPeer("peer-3", "cafe")))

        assertEquals(listOf("peer-3"), vm.uiState.value.peers.map { it.endpointId })
    }

    @Test
    fun `connect handler failure does not stop collection`() = runTest {
        coEvery { groupRepo.getAll() } throws IllegalStateException("db unavailable")

        events.emit(BleEvent.Connected("peer-1"))

        assertEquals("Could not process peer data", vm.uiState.value.status)

        coEvery { groupRepo.getAll() } returns emptyList()
        events.emit(BleEvent.PeerFound(NearbyPeer("peer-4", "hotel")))
        assertTrue(vm.uiState.value.peers.any { it.endpointId == "peer-4" })
    }

    @Test
    fun `sync request failure is reported without crashing the scope`() = runTest {
        every { bleTransfer.isAuthenticated("peer-1") } returns true
        coEvery { bleTransfer.processPayload("peer-1", any()) } returns listOf("g1")
        coEvery { eventDao.getEventIds("g1") } throws IllegalStateException("disk io")

        events.emit(BleEvent.PayloadReceived("peer-1", byteArrayOf(0x04)))

        assertEquals("Sync request failed", vm.uiState.value.status)
        assertFalse(vm.uiState.value.syncing)
    }

    @Test
    fun `scan failure releases the radio and clears scanning state`() = runTest {
        every { nearbySync.startAdvertising() } throws SecurityException("no permission")

        vm.startScan()

        assertFalse(vm.uiState.value.scanning)
        assertFalse(vm.uiState.value.syncing)
        assertTrue(vm.uiState.value.status.startsWith("Scan failed"))
        verify { nearbySync.stop() }
    }

    // --- Handshake legs bind the peer's claimed pubkey into what BleTransfer signs ---

    @Test
    fun `leg 1 handshake is answered with the peer's claimed pubkey`() = runTest {
        val leg1 = BleHandshake(PEER_PUB, emptyList(), challenge = CHALLENGE)
        coEvery { bleTransfer.processPayload("peer-1", any()) } returns leg1

        events.emit(BleEvent.PayloadReceived("peer-1", byteArrayOf(0x01)))

        verify { bleTransfer.sendHandshakeResponse("peer-1", OUR_PUB, emptyList(), CHALLENGE, PEER_PUB) }
        verify(exactly = 0) { nearbySync.disconnect(any()) }
        assertEquals("Authenticating peer…", vm.uiState.value.status)
    }

    @Test
    fun `leg 1 handshake with a NIP-01-shaped challenge is dropped before anything is signed`() = runTest {
        val nip01 = "[0,\"$PEER_PUB\",1700000000,1,[],\"\"]"
        val leg1 = BleHandshake(PEER_PUB, emptyList(), challenge = nip01)
        coEvery { bleTransfer.processPayload("peer-1", any()) } returns leg1

        events.emit(BleEvent.PayloadReceived("peer-1", byteArrayOf(0x01)))

        verify(exactly = 0) { bleTransfer.sendHandshakeResponse(any(), any(), any(), any(), any()) }
        verify { bleTransfer.clearPeer("peer-1") }
        verify { nearbySync.disconnect("peer-1") }
        assertEquals("Peer authentication failed", vm.uiState.value.status)
    }

    @Test
    fun `leg 1 handshake with a malformed pubkey is dropped before anything is signed`() = runTest {
        val leg1 = BleHandshake("not-a-pubkey", emptyList(), challenge = CHALLENGE)
        coEvery { bleTransfer.processPayload("peer-1", any()) } returns leg1

        events.emit(BleEvent.PayloadReceived("peer-1", byteArrayOf(0x01)))

        verify(exactly = 0) { bleTransfer.sendHandshakeResponse(any(), any(), any(), any(), any()) }
        verify { bleTransfer.clearPeer("peer-1") }
        verify { nearbySync.disconnect("peer-1") }
        assertEquals("Peer authentication failed", vm.uiState.value.status)
    }

    @Test
    fun `leg 2 response is answered with the verified peer's pubkey`() = runTest {
        val leg2 = BleHandshake(PEER_PUB, emptyList(), challenge = CHALLENGE, challengeResponse = "ee".repeat(64))
        coEvery { bleTransfer.processPayload("peer-1", any()) } returns leg2
        every { bleTransfer.verifyHandshake("peer-1", leg2) } returns true
        every { bleTransfer.isAuthenticated("peer-1") } returns true

        events.emit(BleEvent.PayloadReceived("peer-1", byteArrayOf(0x01)))

        verify { bleTransfer.sendChallengeResponse("peer-1", OUR_PUB, CHALLENGE, PEER_PUB) }
        coVerify { bleTransfer.sendGroupIds("peer-1", emptyList()) }
    }

    @Test
    fun `leg 2 response that fails verification is not answered`() = runTest {
        val leg2 = BleHandshake(PEER_PUB, emptyList(), challenge = CHALLENGE, challengeResponse = "ee".repeat(64))
        coEvery { bleTransfer.processPayload("peer-1", any()) } returns leg2
        every { bleTransfer.verifyHandshake("peer-1", leg2) } returns false

        events.emit(BleEvent.PayloadReceived("peer-1", byteArrayOf(0x01)))

        verify(exactly = 0) { bleTransfer.sendChallengeResponse(any(), any(), any(), any()) }
        coVerify(exactly = 0) { bleTransfer.sendGroupIds(any(), any()) }
    }

    private companion object {
        val OUR_PUB = "aa".repeat(32)
        val PEER_PUB = "bb".repeat(32)
        val CHALLENGE = "cc".repeat(32)
    }
}
