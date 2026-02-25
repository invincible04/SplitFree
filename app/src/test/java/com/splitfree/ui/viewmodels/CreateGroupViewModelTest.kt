package com.splitfree.ui.viewmodels

import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.data.nostr.relay.RelayStatus
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.usecase.group.CreateGroupUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.components.RelayCheckStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateGroupViewModelTest {
    private val createGroup = mockk<CreateGroupUseCase>()
    private val relayHealthMonitor = mockk<RelayHealthMonitor>(relaxed = true)
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

        vm = CreateGroupViewModel(createGroup, relayHealthMonitor)
    }

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
        every { relayHealthMonitor.statuses } returns
            mapOf("wss://test" to RelayStatus("wss://test", online = true, latencyMs = 50))

        vm.checkRelay("wss://test")

        assertEquals(RelayCheckStatus.ONLINE, vm.relayStatuses.value["wss://test"])
    }

    @Test
    fun `checkRelay sets OFFLINE for unreachable relay`() = runTest {
        every { relayHealthMonitor.statuses } returns
            mapOf("wss://bad" to RelayStatus("wss://bad", online = false))

        vm.checkRelay("wss://bad")

        assertEquals(RelayCheckStatus.OFFLINE, vm.relayStatuses.value["wss://bad"])
    }

    @Test
    fun `createGroup passes current relays to use case`() = runTest {
        vm.addRelay("wss://custom.relay")
        coEvery { createGroup(any(), any()) } returns fakeGroup

        var createdId: String? = null
        vm.createGroup("Trip") { createdId = it }

        coVerify { createGroup("Trip", vm.relays.value) }
        assertEquals("g1", createdId)
    }

    @Test
    fun `createGroup sets error on failure`() = runTest {
        coEvery { createGroup(any(), any()) } throws RuntimeException("boom")

        vm.createGroup("Trip") {}

        assertEquals("boom", vm.error.value)
    }
}
