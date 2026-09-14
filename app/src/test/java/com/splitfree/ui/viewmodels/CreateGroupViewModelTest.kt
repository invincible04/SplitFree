package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import com.splitfree.R
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.data.nostr.relay.RelayStatus
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.usecase.group.CreateGroupUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.components.RelayCheckStatus
import com.splitfree.ui.components.RelayInfo
import com.splitfree.ui.util.UiMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateGroupViewModelTest {
    private val createGroup = mockk<CreateGroupUseCase>()
    private val relayHealthMonitor = mockk<RelayHealthMonitor>(relaxed = true)
    private val eventSigner = mockk<EventSigner>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var vm: CreateGroupViewModel

    private val fakeGroup = Group(
        id = "g1",
        name = "Test",
        createdBy = "pub1",
        createdAt = 1000L,
        members = listOf("pub1"),
        relays = listOf("wss://r")
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0

        every { createGroup.currentAuthor() } returns "pub1"
        vm = viewModel(SavedStateHandle())
    }

    private fun viewModel(handle: SavedStateHandle) =
        CreateGroupViewModel(createGroup, relayHealthMonitor, eventSigner, handle)

    /** A fresh handle carrying everything [handle] saved, as the framework restores it after process death. */
    private fun restore(handle: SavedStateHandle) =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `initial relays are DEFAULT_RELAYS`() {
        assertEquals(RelayDefaults.DEFAULT_RELAYS, vm.relays.value)
    }

    @Test
    fun `addRelay appends to list`() {
        vm.addRelay("wss://custom.relay")
        assertTrue(vm.relays.value.contains("wss://custom.relay"))
        assertEquals(RelayDefaults.DEFAULT_RELAYS.size + 1, vm.relays.value.size)
    }

    @Test
    fun `addRelay deduplicates`() {
        val before = vm.relays.value.size
        vm.addRelay(RelayDefaults.DEFAULT_RELAYS.first())
        assertEquals(before, vm.relays.value.size)
    }

    @Test
    fun `removeRelay removes from list`() {
        val first = vm.relays.value.first()
        vm.removeRelay(first)
        assertTrue(first !in vm.relays.value)
    }

    @Test
    fun `removeRelay does not remove last relay`() {
        // Remove all but one
        val relays = vm.relays.value.toList()
        relays.dropLast(1).forEach { vm.removeRelay(it) }
        assertEquals(1, vm.relays.value.size)
        // Try removing the last one
        vm.removeRelay(vm.relays.value.first())
        assertEquals(1, vm.relays.value.size)
    }

    @Test
    fun `checkRelay sets CHECKING then ONLINE`() = runTest {
        val url = RelayDefaults.DEFAULT_RELAYS.first()
        every { relayHealthMonitor.statuses } returns
            mapOf(url to RelayStatus(url, online = true, latencyMs = 50))

        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.ONLINE, vm.relayStatuses.value[url])
    }

    @Test
    fun `checkRelay sets OFFLINE for unreachable relay`() = runTest {
        val url = "wss://custom.bad.relay"
        every { relayHealthMonitor.statuses } returns
            mapOf(url to RelayStatus(url, online = false))

        vm.addRelay(url)
        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.OFFLINE, vm.relayStatuses.value[url])
    }

    @Test
    fun `createGroup passes current relays to use case`() = runTest {
        vm.addRelay("wss://custom.relay")
        coEvery { createGroup(any(), any(), any(), any(), any()) } returns fakeGroup

        var createdId: String? = null
        vm.createGroup("Trip") { createdId = it }

        coVerify { createGroup("Trip", vm.relays.value, any(), "pub1", any()) }
        assertEquals("g1", createdId)
    }

    @Test
    fun `createGroup sets error on failure`() = runTest {
        coEvery { createGroup(any(), any(), any(), any(), any()) } throws RuntimeException("boom")

        vm.createGroup("Trip") {}

        assertEquals(UiMessage.Raw("boom"), vm.error.value)
        assertFalse(vm.isCreating.value)
    }

    // --- double-submit guard ---

    @Test
    fun `two rapid createGroup calls invoke the use case once and isCreating toggles`() = runTest {
        val gate = CompletableDeferred<Group>()
        coEvery { createGroup(any(), any(), any(), any(), any()) } coAnswers { gate.await() }
        assertFalse(vm.isCreating.value)

        val created = mutableListOf<String>()
        vm.createGroup("Trip") { created += it }
        vm.createGroup("Trip") { created += it }

        assertTrue(vm.isCreating.value)
        coVerify(exactly = 1) { createGroup("Trip", any(), any(), "pub1", any()) }

        gate.complete(fakeGroup)

        assertFalse(vm.isCreating.value)
        assertEquals(listOf("g1"), created)
        // The guard is released: a later, deliberate submission goes through.
        coEvery { createGroup(any(), any(), any(), any(), any()) } returns fakeGroup.copy(id = "g2")
        vm.createGroup("Second") { created += it }
        assertEquals(listOf("g1", "g2"), created)
    }

    @Test
    fun `a failed creation releases the guard and clears isCreating`() = runTest {
        coEvery { createGroup(any(), any(), any(), any(), any()) } throws RuntimeException("offline")

        vm.createGroup("Trip") {}
        assertFalse(vm.isCreating.value)

        coEvery { createGroup(any(), any(), any(), any(), any()) } returns fakeGroup
        var createdId: String? = null
        vm.createGroup("Trip") { createdId = it }
        assertEquals("g1", createdId)
        coVerify(exactly = 2) { createGroup("Trip", any(), any(), "pub1", any()) }
    }

    @Test
    fun `relay check failure marks unknown relay offline and surfaces an error`() = runTest {
        val url = "wss://custom.bad.relay"
        coEvery { relayHealthMonitor.checkRelays(any()) } throws java.io.IOException("socket closed")

        vm.addRelay(url)
        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.OFFLINE, vm.relayStatuses.value[url])
        assertEquals(UiMessage.Res(R.string.relay_check_failed, "custom.bad.relay"), vm.error.value)
        // The relay stays in the list: a failed probe is not a rejection.
        assertTrue(url in vm.relays.value)
    }

    @Test
    fun `relay check failure marks a default relay offline and surfaces an error`() = runTest {
        val url = RelayDefaults.DEFAULT_RELAYS.first()
        coEvery { relayHealthMonitor.checkRelays(any()) } throws java.io.IOException("socket closed")

        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.OFFLINE, vm.relayStatuses.value[url])
        assertEquals(UiMessage.Res(R.string.relay_check_failed, url.removePrefix("wss://")), vm.error.value)
        assertTrue(url in vm.relays.value)
    }

    // --- statuses are informational; only the user changes the list ---

    @Test
    fun `an unreachable default relay is OFFLINE without an error and stays listed`() = runTest {
        val url = RelayDefaults.DEFAULT_RELAYS.first()
        every { relayHealthMonitor.statuses } returns mapOf(url to RelayStatus(url, online = false))

        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.OFFLINE, vm.relayStatuses.value[url])
        assertNull(vm.error.value)
        assertTrue(url in vm.relays.value)
    }

    @Test
    fun `a known relay that answers NIP-11 is ONLINE without a write round-trip`() = runTest {
        val url = RelayDefaults.KNOWN_RELAYS.last()
        every { relayHealthMonitor.statuses } returns
            mapOf(url to RelayStatus(url, online = true, latencyMs = 30, paid = true))

        vm.addRelay(url)
        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.ONLINE, vm.relayStatuses.value[url])
        assertEquals(RelayInfo(paid = true, latencyMs = 30), vm.relayInfo.value[url])
        coVerify(exactly = 0) { relayHealthMonitor.verifyRelayRoundTrip(any(), any()) }
    }

    @Test
    fun `a custom relay that fails the round-trip is REJECTED and stays listed without an error`() = runTest {
        val url = "wss://custom.relay"
        every { relayHealthMonitor.statuses } returns mapOf(url to RelayStatus(url, online = true, latencyMs = 30))
        coEvery { relayHealthMonitor.verifyRelayRoundTrip(url, any()) } returns false

        vm.addRelay(url)
        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.REJECTED, vm.relayStatuses.value[url])
        assertNull(vm.error.value)
        assertTrue(url in vm.relays.value)
    }

    @Test
    fun `a custom relay that passes the round-trip is ONLINE`() = runTest {
        val url = "wss://custom.relay"
        every { relayHealthMonitor.statuses } returns mapOf(url to RelayStatus(url, online = true, latencyMs = 30))
        coEvery { relayHealthMonitor.verifyRelayRoundTrip(url, any()) } returns true

        vm.addRelay(url)
        vm.checkRelay(url)

        assertEquals(RelayCheckStatus.ONLINE, vm.relayStatuses.value[url])
    }

    // --- invite-link budget and reset ---

    @Test
    fun `addRelay ignores the eleventh relay`() {
        (1..5).forEach { vm.addRelay("wss://custom$it.relay") }
        assertEquals(InviteLinkCodec.MAX_RELAYS, vm.relays.value.size)

        vm.addRelay("wss://one.too.many")

        assertEquals(InviteLinkCodec.MAX_RELAYS, vm.relays.value.size)
        assertFalse("wss://one.too.many" in vm.relays.value)
    }

    @Test
    fun `addRelay ignores a relay that overflows the custom byte budget`() {
        vm.addRelay("wss://" + "a".repeat(200) + ".example")
        vm.addRelay("wss://" + "b".repeat(60) + ".example")

        assertEquals(RelayDefaults.DEFAULT_RELAYS.size + 1, vm.relays.value.size)
    }

    @Test
    fun `resetRelays restores the defaults and checks the ones not yet online`() = runTest {
        val online = RelayDefaults.DEFAULT_RELAYS.first()
        every { relayHealthMonitor.statuses } returns mapOf(online to RelayStatus(online, online = true, latencyMs = 5))
        vm.checkRelay(online)
        vm.addRelay("wss://custom.relay")
        vm.removeRelay(RelayDefaults.DEFAULT_RELAYS[1])

        vm.resetRelays()

        assertEquals(RelayDefaults.DEFAULT_RELAYS, vm.relays.value)
        coVerify(exactly = 1) { relayHealthMonitor.checkRelays(listOf(online)) }
        RelayDefaults.DEFAULT_RELAYS.drop(1).forEach { url ->
            coVerify(exactly = 1) { relayHealthMonitor.checkRelays(listOf(url)) }
        }
    }

    @Test
    fun `resetRelays survives saved state reconstruction and is refused while creating`() = runTest {
        val handle = SavedStateHandle()
        val first = viewModel(handle)
        first.addRelay("wss://custom.relay")
        first.resetRelays()
        assertEquals(RelayDefaults.DEFAULT_RELAYS, viewModel(restore(handle)).relays.value)

        val gate = CompletableDeferred<Group>()
        coEvery { createGroup(any(), any(), any(), any(), any()) } coAnswers { gate.await() }
        vm.addRelay("wss://custom.relay")
        vm.createGroup("Trip") {}
        vm.resetRelays()
        assertTrue("wss://custom.relay" in vm.relays.value)
        gate.complete(fakeGroup)
    }

    // --- the creation command survives recreation ---

    @Test
    fun `custom relays and removal survive saved state reconstruction`() {
        val handle = SavedStateHandle()
        val first = viewModel(handle)
        first.addRelay("wss://custom.relay")
        first.removeRelay(RelayDefaults.DEFAULT_RELAYS.first())
        val restored = viewModel(restore(handle))
        assertEquals(first.relays.value, restored.relays.value)
        assertTrue("wss://custom.relay" in restored.relays.value)
        assertTrue(RelayDefaults.DEFAULT_RELAYS.first() !in restored.relays.value)
    }

    @Test
    fun `a retry after a failed attempt reuses the pinned creation time author and command`() = runTest {
        val timestamps = mutableListOf<Long>()
        val commands = mutableListOf<String>()
        coEvery { createGroup(any(), any(), any(), any(), any()) } coAnswers {
            timestamps += thirdArg<Long>()
            commands += arg<String>(4)
            throw java.io.IOException("interrupted")
        }
        vm.createGroup("Trip") {}
        vm.createGroup("Trip") {}
        assertEquals(2, timestamps.size)
        assertEquals(timestamps.first(), timestamps.last())
        assertEquals(commands.first(), commands.last())
    }

    @Test
    fun `retry after process recreation keeps creation timestamp author and command`() = runTest {
        val handle = SavedStateHandle()
        val timestamps = mutableListOf<Long>()
        val commands = mutableListOf<String>()
        coEvery { createGroup(any(), any(), any(), any(), any()) } coAnswers {
            timestamps += thirdArg<Long>()
            commands += arg<String>(4)
            throw java.io.IOException("interrupted")
        }
        viewModel(handle).createGroup("Trip") {}
        val restored = viewModel(restore(handle))
        // The identity in effect after recreation does not replace the one the command was issued under.
        every { createGroup.currentAuthor() } returns "other"
        restored.createGroup("Trip") {}
        assertEquals(2, timestamps.size)
        assertEquals(timestamps.first(), timestamps.last())
        assertEquals(commands.first(), commands.last())
        coVerify(exactly = 2) { createGroup("Trip", any(), any(), "pub1", any()) }
    }

    @Test
    fun `separate editors issue separate commands`() = runTest {
        val commands = mutableListOf<String>()
        coEvery { createGroup(any(), any(), any(), any(), any()) } coAnswers {
            commands += arg<String>(4)
            fakeGroup
        }
        viewModel(SavedStateHandle()).createGroup("Trip") {}
        viewModel(SavedStateHandle()).createGroup("Trip") {}
        assertEquals(2, commands.distinct().size)
    }

    @Test
    fun `relays cannot change while creation is suspended`() = runTest {
        val gate = CompletableDeferred<Group>()
        coEvery { createGroup(any(), any(), any(), any(), any()) } coAnswers { gate.await() }
        val before = vm.relays.value
        vm.createGroup("Trip") {}
        vm.addRelay("wss://custom.relay")
        vm.removeRelay(before.first())
        assertEquals(before, vm.relays.value)
        gate.complete(fakeGroup)
        vm.addRelay("wss://custom.relay")
        assertTrue("wss://custom.relay" in vm.relays.value)
    }

    @Test
    fun `a failed relay probe does not drop a relay while creation is suspended`() = runTest {
        val url = "wss://custom.bad.relay"
        vm.addRelay(url)
        val gate = CompletableDeferred<Group>()
        coEvery { createGroup(any(), any(), any(), any(), any()) } coAnswers { gate.await() }
        every { relayHealthMonitor.statuses } returns mapOf(url to RelayStatus(url, online = false))
        vm.createGroup("Trip") {}
        vm.checkRelay(url)
        assertEquals(RelayCheckStatus.OFFLINE, vm.relayStatuses.value[url])
        assertTrue(url in vm.relays.value)
        gate.complete(fakeGroup)
    }
}
