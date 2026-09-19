package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.balance.BalanceResult
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import com.splitfree.domain.usecase.expense.GetExpensesUseCase
import com.splitfree.domain.usecase.expense.SimplifyDebtsUseCase
import com.splitfree.domain.usecase.group.CreateInviteLinkUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.usecase.group.UpdateGroupRelaysUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.util.UiMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Real ViewModel, invite use case and codec; storage/rotation effects are controlled, not device acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteFreshnessTest {
    private val creator = "aa".repeat(32)
    private val remaining = "bb".repeat(32)
    private val departing = "cc".repeat(32)
    private val createdAt = 1_700_000_000L
    private val gid = GroupIdentity.derive(creator, createdAt)
    private val initial = Group(
        id = gid,
        name = "Trip",
        createdBy = creator,
        createdAt = createdAt,
        members = listOf(creator, remaining, departing),
        relays = RelayDefaults.DEFAULT_RELAYS
    )
    private val groups = MutableStateFlow<Group?>(initial)
    private val keys = (0..2).associateWith { epoch ->
        Base64.getEncoder().encodeToString(ByteArray(32) { (epoch + 1).toByte() })
    }.toMutableMap()
    private val repo = mockk<GroupRepositoryContract>(relaxed = true)
    private val identity = mockk<IdentityContract>()
    private val balances = mockk<ComputeBalancesUseCase>()
    private val simplify = mockk<SimplifyDebtsUseCase>()
    private val expenses = mockk<GetExpensesUseCase>()
    private val rotate = mockk<RotateGroupKeyUseCase>()
    private val updateRelays = mockk<UpdateGroupRelaysUseCase>()
    private val models = mutableListOf<GroupDetailViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { identity.getPublicKeyHex() } returns creator
        every { repo.observeById(gid) } returns groups
        coEvery { repo.getById(gid) } coAnswers { groups.value }
        coEvery { repo.getGroupKeyForEpoch(gid, any()) } coAnswers { keys[secondArg<Int>()] }
        every { expenses.observeWithAuthors(gid) } returns flowOf(emptyList())
        coEvery { balances.computeWithExclusions(gid) } returns BalanceResult(emptyList(), emptySet())
        every { simplify(any()) } returns emptyList()
    }

    @After
    fun teardown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    private fun model() = GroupDetailViewModel(
        SavedStateHandle(mapOf("groupId" to gid)), repo, mockk(relaxed = true), balances, simplify,
        rotate, identity, expenses, mockk(relaxed = true), CreateInviteLinkUseCase(repo), updateRelays,
        mockk(relaxed = true), mockk(relaxed = true)
    ).also { models += it }

    private fun invite(vm: GroupDetailViewModel) = InviteLinkCodec.decode(checkNotNull(vm.inviteLink.value))

    @Test
    fun `excess creator history clears stale invite and reports explicit limit`() = runTest {
        val vm = model()
        assertNotNull(vm.inviteLink.value)
        groups.value = initial.copy(
            creatorTransitions = List(5) {
                com.splitfree.domain.model.group.CreatorTransition("", 1, 0, creator, remaining, "", "")
            }
        )
        assertNull(vm.inviteLink.value)
        assertEquals(UiMessage.Res(R.string.invite_creator_history_too_long), vm.uiState.value.inviteError)
    }

    @Test
    fun `removal on the open screen refreshes epoch and key`() = runTest {
        val vm = model()
        assertEquals(0, invite(vm).keyEpoch)
        coEvery { rotate(gid, departing) } coAnswers {
            groups.value = initial.copy(keyEpoch = 1, members = initial.members - departing)
        }
        vm.removeMember(departing)
        assertEquals(1, invite(vm).keyEpoch)
        assertEquals(keys[1], invite(vm).groupKey)
        assertFalse(departing in vm.uiState.value.members)
    }

    @Test
    fun `relay save on the open screen refreshes endpoints`() = runTest {
        val vm = model()
        coEvery { updateRelays(gid, any()) } coAnswers {
            groups.value = initial.copy(relays = secondArg())
        }
        vm.beginRelayEdit()
        vm.removeRelay(initial.relays.first())
        vm.saveRelays {}
        assertEquals(initial.relays.drop(1), invite(vm).relays)
    }

    @Test
    fun `queued dispatcher conflates rapid remote changes to the latest snapshot`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = model()
        runCurrent()
        assertEquals(0, invite(vm).keyEpoch)
        val pending = CompletableDeferred<String>()
        coEvery { repo.getGroupKeyForEpoch(gid, 1) } coAnswers { pending.await() }
        groups.value = initial.copy(keyEpoch = 1)
        runCurrent()
        assertNull(vm.inviteLink.value)
        groups.value = initial.copy(keyEpoch = 2, name = "Latest", relays = listOf("wss://latest.test"))
        runCurrent()
        pending.complete(keys.getValue(1))
        runCurrent()
        assertEquals(2, invite(vm).keyEpoch)
        assertEquals(keys[2], invite(vm).groupKey)
        assertEquals("Latest", invite(vm).name)
        assertEquals(listOf("wss://latest.test"), invite(vm).relays)
        assertNull(vm.uiState.value.inviteError)
    }

    @Test
    fun `remote rotation and metadata refresh without a local mutation`() = runTest {
        val vm = model()
        groups.value = initial.copy(keyEpoch = 2, name = "New name", relays = listOf("wss://relay.test/Case,x"))
        val link = invite(vm)
        assertEquals(2, link.keyEpoch)
        assertEquals(keys[2], link.groupKey)
        assertEquals("New name", link.name)
        assertEquals(groups.value!!.relays, link.relays)
        coVerify(exactly = 0) { rotate(any(), any()) }
        coVerify(exactly = 0) { updateRelays(any(), any()) }
    }

    @Test
    fun `unknown creator failure recovers when creator metadata arrives`() = runTest {
        groups.value = initial.copy(createdBy = "")
        val vm = model()
        assertNull(vm.inviteLink.value)
        assertEquals(UiMessage.Res(R.string.create_invite_failed), vm.uiState.value.inviteError)
        groups.value = initial
        assertEquals(creator, invite(vm).creatorPubkey)
        assertNull(vm.uiState.value.inviteError)
    }

    @Test
    fun `missing epoch key withdraws old link and retry reads the repaired key`() = runTest {
        val vm = model()
        keys.remove(1)
        groups.value = initial.copy(keyEpoch = 1)
        assertNull(vm.inviteLink.value)
        assertEquals(UiMessage.Res(R.string.create_invite_failed), vm.uiState.value.inviteError)
        keys[1] = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        vm.retryInviteLink()
        assertEquals(keys[1], invite(vm).groupKey)
        assertNull(vm.uiState.value.inviteError)
    }

    @Test
    fun `old noncooperative generation cannot overwrite a newer link`() = runTest {
        val oldKey = CompletableDeferred<String>()
        coEvery { repo.getGroupKeyForEpoch(gid, 0) } coAnswers { withContext(NonCancellable) { oldKey.await() } }
        val vm = model()
        assertNull(vm.inviteLink.value)
        groups.value = initial.copy(keyEpoch = 1)
        assertEquals(1, invite(vm).keyEpoch)
        oldKey.complete(keys.getValue(0))
        assertEquals(1, invite(vm).keyEpoch)
        assertEquals(keys[1], invite(vm).groupKey)
        assertNull(vm.uiState.value.inviteError)
    }

    @Test
    fun `old noncooperative failure cannot replace newer success`() = runTest {
        val oldKey = CompletableDeferred<String>()
        coEvery { repo.getGroupKeyForEpoch(gid, 0) } coAnswers { withContext(NonCancellable) { oldKey.await() } }
        val vm = model()
        groups.value = initial.copy(keyEpoch = 1)
        oldKey.completeExceptionally(IllegalStateException("old failure"))
        assertEquals(1, invite(vm).keyEpoch)
        assertNull(vm.uiState.value.inviteError)
    }

    @Test
    fun `pending remote key read clears the previously ready link`() = runTest {
        val vm = model()
        assertNotNull(vm.inviteLink.value)
        val pending = CompletableDeferred<String>()
        coEvery { repo.getGroupKeyForEpoch(gid, 1) } coAnswers { pending.await() }
        groups.value = initial.copy(keyEpoch = 1)
        assertNull(vm.inviteLink.value)
        assertNull(vm.uiState.value.inviteError)
        pending.complete(keys.getValue(1))
        assertEquals(1, invite(vm).keyEpoch)
    }

    @Test
    fun `rotation in flight blocks sharing even if refreshed then failure restores current invite`() = runTest {
        val vm = model()
        val pending = CompletableDeferred<Unit>()
        coEvery { rotate(gid, departing) } coAnswers { pending.await() }
        vm.removeMember(departing)
        assertNull(vm.inviteLink.value)
        vm.retryInviteLink()
        assertNull(vm.inviteLink.value)
        pending.completeExceptionally(IllegalStateException("rotation failed"))
        assertEquals(0, invite(vm).keyEpoch)
        assertNotNull(vm.error.value)
    }

    @Test
    fun `overlapping relay save and rotation keep link unavailable until both finish`() = runTest {
        val vm = model()
        val relaySaved = CompletableDeferred<Unit>()
        val rotated = CompletableDeferred<Unit>()
        coEvery { updateRelays(gid, any()) } coAnswers {
            relaySaved.await()
            groups.value = groups.value!!.copy(relays = listOf("wss://new.test"))
        }
        coEvery { rotate(gid, departing) } coAnswers {
            rotated.await()
            groups.value = groups.value!!.copy(keyEpoch = 1, members = initial.members - departing)
        }
        vm.saveRelays {}
        vm.removeMember(departing)
        assertNull(vm.inviteLink.value)
        relaySaved.complete(Unit)
        assertNull(vm.inviteLink.value)
        rotated.complete(Unit)
        assertEquals(1, invite(vm).keyEpoch)
        assertEquals(listOf("wss://new.test"), invite(vm).relays)
    }

    @Test
    fun `unreadable identity withdraws link and retry recovers`() = runTest {
        val vm = model()
        every { identity.getPublicKeyHex() } throws IllegalStateException("key unavailable")
        groups.value = initial.copy(name = "New name")
        assertNull(vm.inviteLink.value)
        assertEquals(UiMessage.Res(R.string.invite_group_unavailable), vm.uiState.value.inviteError)
        every { identity.getPublicKeyHex() } returns creator
        vm.retryInviteLink()
        assertEquals("New name", invite(vm).name)
    }

    @Test
    fun `synchronous observer construction failure is retryable`() = runTest {
        every { repo.observeById(gid) } throws IllegalStateException("query unavailable")
        val vm = model()
        assertNull(vm.inviteLink.value)
        assertEquals(UiMessage.Res(R.string.invite_group_unavailable), vm.uiState.value.inviteError)
        every { repo.observeById(gid) } returns groups
        vm.retryInviteLink()
        assertEquals(0, invite(vm).keyEpoch)
    }

    @Test
    fun `missing group clears link and later restoration generates again`() = runTest {
        val vm = model()
        groups.value = null
        assertNull(vm.inviteLink.value)
        assertEquals(UiMessage.Res(R.string.invite_group_unavailable), vm.uiState.value.inviteError)
        groups.value = initial.copy(keyEpoch = 1)
        assertEquals(1, invite(vm).keyEpoch)
    }

    @Test
    fun `dead observer withdraws link and invitation retry resubscribes`() = runTest {
        every { repo.observeById(gid) } returns flow {
            emit(initial)
            throw IllegalStateException("read failed")
        }
        val vm = model()
        assertNull(vm.inviteLink.value)
        assertEquals(UiMessage.Res(R.string.invite_group_unavailable), vm.uiState.value.inviteError)
        every { repo.observeById(gid) } returns groups
        vm.retryInviteLink()
        assertEquals(0, invite(vm).keyEpoch)
        assertNull(vm.uiState.value.inviteError)
    }

    @Test
    fun `legacy overbudget endpoints stay intact until deliberate edit repairs invites`() = runTest {
        val prefix = "wss://relay.test/"
        val relays = listOf(prefix + "a".repeat(127 - prefix.length), prefix + "b".repeat(127 - prefix.length))
        groups.value = initial.copy(relays = relays)
        val vm = model()
        assertNull(vm.inviteLink.value)
        assertEquals(UiMessage.Res(R.string.invite_relays_need_edit), vm.uiState.value.inviteError)
        assertEquals(relays, vm.uiState.value.relays)
        coVerify(exactly = 0) { repo.save(any(), any()) }
        coEvery { updateRelays(gid, any()) } coAnswers {
            groups.value = groups.value!!.copy(relays = secondArg())
        }
        vm.beginRelayEdit()
        vm.removeRelay(relays.first())
        vm.cancelRelayEdit()
        assertEquals(relays, vm.uiState.value.relays)
        assertNull(vm.inviteLink.value)
        vm.beginRelayEdit()
        vm.removeRelay(relays.first())
        vm.saveRelays {}
        assertEquals(relays.drop(1), invite(vm).relays)
        assertNull(vm.uiState.value.inviteError)
    }
}
