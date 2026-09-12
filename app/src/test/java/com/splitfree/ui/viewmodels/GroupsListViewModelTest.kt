package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.sync.ConnectionStatus
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.usecase.group.GroupSummary
import com.splitfree.domain.usecase.group.ObserveGroupSummariesUseCase
import com.splitfree.ui.util.UiMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupsListViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val observeSummaries = mockk<ObserveGroupSummariesUseCase>()
    private val nostrClient = mockk<NostrClientContract>()

    private val summaries = MutableStateFlow<List<GroupSummary>>(emptyList())
    private val connection = MutableStateFlow(ConnectionStatus.Connecting)

    private val goa = summary("goa", "Goa trip", mapOf("INR" to 240000L), setOf("INR"))
    private val flat = summary("flat", "Flatmates", mapOf("INR" to 75000L, "USD" to -1500L), setOf("INR", "USD"))
    private val club = summary("club", "Badminton", mapOf("INR" to -65000L), setOf("INR"))

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { observeSummaries.observe() } returns summaries
        every { nostrClient.connectionStatus } returns connection
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `starts loading with no currency and no groups`() = runTest(testDispatcher) {
        every { observeSummaries.observe() } returns flow { }
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        val state = vm.uiState.value
        assertTrue(state.loading)
        assertTrue(state.groups.isEmpty())
        assertNull(state.selectedCurrency)
        assertEquals(0L, state.netMinor)
        job.cancel()
    }

    @Test
    fun `first emission clears loading and picks the most common currency`() = runTest(testDispatcher) {
        summaries.value = listOf(goa, flat, club)
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(3, state.groups.size)
        assertEquals(listOf("INR", "USD"), state.currencies)
        assertEquals("INR", state.selectedCurrency)
        job.cancel()
    }

    @Test
    fun `hero totals are computed per selected currency only`() = runTest(testDispatcher) {
        summaries.value = listOf(goa, flat, club)
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        val inr = vm.uiState.value
        assertEquals(315000L, inr.owedMinor)
        assertEquals(65000L, inr.oweMinor)
        assertEquals(250000L, inr.netMinor)

        vm.selectCurrency("USD")
        val usd = vm.uiState.value
        assertEquals("USD", usd.selectedCurrency)
        assertEquals(0L, usd.owedMinor)
        assertEquals(1500L, usd.oweMinor)
        assertEquals(-1500L, usd.netMinor)
        assertNull("Goa has no USD entry", usd.myNet(goa))
        job.cancel()
    }

    @Test
    fun `currency tie resolves alphabetically`() = runTest(testDispatcher) {
        summaries.value = listOf(
            summary("a", "A", mapOf("USD" to 100L), setOf("USD")),
            summary("b", "B", mapOf("EUR" to 100L), setOf("EUR"))
        )
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        assertEquals("EUR", vm.uiState.value.selectedCurrency)
        job.cancel()
    }

    @Test
    fun `empty group list is ready not loading and has no currency`() = runTest(testDispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertTrue(state.groups.isEmpty())
        assertTrue(state.currencies.isEmpty())
        assertNull(state.selectedCurrency)
        job.cancel()
    }

    @Test
    fun `groups without any activity leave selectedCurrency null`() = runTest(testDispatcher) {
        summaries.value = listOf(summary("new", "New group", emptyMap(), emptySet()))
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        assertNull(vm.uiState.value.selectedCurrency)
        assertEquals(1, vm.uiState.value.groups.size)
        job.cancel()
    }

    @Test
    fun `selected currency survives recreation through SavedStateHandle`() = runTest(testDispatcher) {
        summaries.value = listOf(goa, flat)
        val handle = SavedStateHandle()
        val first = viewModel(handle)
        val job1 = launch { first.uiState.collect {} }
        first.selectCurrency("USD")
        assertEquals("USD", first.uiState.value.selectedCurrency)
        job1.cancel()

        val second = viewModel(SavedStateHandle(mapOf("selectedCurrency" to handle.get<String>("selectedCurrency"))))
        val job2 = launch { second.uiState.collect {} }
        assertEquals("USD", second.uiState.value.selectedCurrency)
        job2.cancel()
    }

    @Test
    fun `a saved currency that no longer exists falls back to the default`() = runTest(testDispatcher) {
        summaries.value = listOf(goa, club)
        val vm = viewModel(SavedStateHandle(mapOf("selectedCurrency" to "JPY")))
        val job = launch { vm.uiState.collect {} }

        assertEquals("INR", vm.uiState.value.selectedCurrency)
        job.cancel()
    }

    @Test
    fun `connection status is mirrored`() = runTest(testDispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        assertEquals(ConnectionStatus.Connecting, vm.uiState.value.connection)
        connection.value = ConnectionStatus.Connected
        assertEquals(ConnectionStatus.Connected, vm.uiState.value.connection)
        connection.value = ConnectionStatus.Offline
        assertEquals(ConnectionStatus.Offline, vm.uiState.value.connection)
        job.cancel()
    }

    @Test
    fun `still connecting twelve seconds after creation presents as offline`() = runTest(testDispatcher) {
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        advanceTimeBy(11_999)
        assertEquals(ConnectionStatus.Connecting, vm.uiState.value.connection)
        advanceTimeBy(2)
        assertEquals(ConnectionStatus.Offline, vm.uiState.value.connection)

        connection.value = ConnectionStatus.Connected
        assertEquals(ConnectionStatus.Connected, vm.uiState.value.connection)
        connection.value = ConnectionStatus.Connecting
        assertEquals(ConnectionStatus.Offline, vm.uiState.value.connection)
        job.cancel()
    }

    @Test
    fun `grace period never overrides a connected relay`() = runTest(testDispatcher) {
        connection.value = ConnectionStatus.Connected
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        advanceTimeBy(30_000)
        assertEquals(ConnectionStatus.Connected, vm.uiState.value.connection)
        job.cancel()
    }

    @Test
    fun `observation failure surfaces an error and stops loading`() = runTest(testDispatcher) {
        every { observeSummaries.observe() } returns flow { throw IllegalStateException("db closed") }
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(UiMessage.Raw("db closed"), state.error)
        vm.clearError()
        assertNull(vm.uiState.value.error)
        job.cancel()
    }

    @Test
    fun `later emissions replace the group list`() = runTest(testDispatcher) {
        summaries.value = listOf(goa)
        val vm = viewModel()
        val job = launch { vm.uiState.collect {} }
        assertEquals(1, vm.uiState.value.groups.size)

        summaries.value = listOf(goa, flat)
        assertEquals(2, vm.uiState.value.groups.size)
        assertNotNull(vm.uiState.value.myNet(flat))
        job.cancel()
    }

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) =
        GroupsListViewModel(handle, observeSummaries, nostrClient)

    private fun summary(id: String, name: String, mine: Map<String, Long>, currencies: Set<String>) = GroupSummary(
        group = Group(
            id = id,
            name = name,
            createdBy = "me",
            createdAt = 1000L,
            members = listOf("me", "other"),
            relays = listOf("wss://relay.example")
        ),
        myBalances = mine,
        hasExpenses = currencies.isNotEmpty(),
        currencies = currencies
    )
}
