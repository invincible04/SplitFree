package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.usecase.group.CreateGroupUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.components.RelayCheckStatus
import com.splitfree.ui.components.RelayInfo
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.util.toUiMessage
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Handles group creation with name validation and relay selection.
 *
 * The relay choice and the creation command (creation time, author and command id) live in saved state, so
 * a creation interrupted by process death is retried as the same command against the same group id, and
 * the relay list cannot change while a command is in flight.
 */
@HiltViewModel
class CreateGroupViewModel
@Inject
constructor(
    private val createGroup: CreateGroupUseCase,
    private val relayHealthMonitor: RelayHealthMonitor,
    private val eventSigner: EventSigner,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _error = MutableStateFlow<UiMessage?>(null)
    val error: StateFlow<UiMessage?> = _error.asStateFlow()

    private val _relays = MutableStateFlow(
        savedStateHandle.get<ArrayList<String>>(RELAYS_KEY)?.toList() ?: RelayDefaults.DEFAULT_RELAYS
    )
    val relays: StateFlow<List<String>> = _relays.asStateFlow()

    private val _relayStatuses = MutableStateFlow<Map<String, RelayCheckStatus>>(emptyMap())
    val relayStatuses: StateFlow<Map<String, RelayCheckStatus>> = _relayStatuses.asStateFlow()

    private val _relayInfo = MutableStateFlow<Map<String, RelayInfo>>(emptyMap())
    val relayInfo: StateFlow<Map<String, RelayInfo>> = _relayInfo.asStateFlow()

    /** True while a group is being created; the screen disables the submit button. */
    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val creationInProgress = AtomicBoolean(false)

    /** Append [url] unless a creation is in flight or the list would no longer fit an invite link. */
    fun addRelay(url: String) {
        if (_isCreating.value) return
        val next = (_relays.value + url).distinct()
        if (InviteLinkCodec.fitsInviteLink(next)) setRelays(next)
    }

    fun clearError() {
        _error.value = null
    }

    fun removeRelay(url: String) {
        if (!_isCreating.value && _relays.value.size > 1) setRelays(_relays.value - url)
    }

    /** Return to [RelayDefaults.DEFAULT_RELAYS] and check the ones not already known to be online. */
    fun resetRelays() {
        if (_isCreating.value) return
        setRelays(RelayDefaults.DEFAULT_RELAYS)
        _relays.value.filterNot { _relayStatuses.value[it] == RelayCheckStatus.ONLINE }.forEach(::checkRelay)
    }

    fun checkRelay(url: String) {
        viewModelScope.launch {
            try {
                runRelayCheck(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val host = url.removePrefix("wss://")
                Log.w(TAG, "Relay check failed for $host: ${e.message}")
                recordStatus(url, RelayCheckStatus.OFFLINE)
                _error.value = UiMessage.Res(R.string.relay_check_failed, host)
            }
        }
    }

    /**
     * NIP-11 probe, then a signed write+read round-trip for relays outside [RelayDefaults.KNOWN_RELAYS]. Known
     * relays skip the round-trip: they already passed the write+read acceptance check recorded in
     * [RelayDefaults]. Statuses are informational; only the user changes the list.
     */
    private suspend fun runRelayCheck(url: String) {
        recordStatus(url, RelayCheckStatus.CHECKING)
        relayHealthMonitor.checkRelays(listOf(url))
        val status = relayHealthMonitor.statuses[url]
        if (status?.online != true) {
            recordStatus(url, RelayCheckStatus.OFFLINE)
            return
        }
        _relayInfo.value = _relayInfo.value +
            (url to RelayInfo(status.paid, status.supportsGiftWrap, status.latencyMs))
        if (url in RelayDefaults.KNOWN_RELAYS) {
            recordStatus(url, RelayCheckStatus.ONLINE)
            return
        }
        recordStatus(url, RelayCheckStatus.VERIFYING)
        val testEvent = eventSigner.createSignedEvent("verify-${System.nanoTime()}", "relay_test", "test")
        val verified = relayHealthMonitor.verifyRelayRoundTrip(url, testEvent)
        recordStatus(url, if (verified) RelayCheckStatus.ONLINE else RelayCheckStatus.REJECTED)
    }

    private fun recordStatus(url: String, status: RelayCheckStatus) {
        _relayStatuses.value = _relayStatuses.value + (url to status)
    }

    fun checkAllRelays() {
        _relays.value.forEach { checkRelay(it) }
    }

    /**
     * Create the group once. A second call while the first is still running is ignored so a
     * double tap cannot create two groups; the guard is released when the attempt finishes.
     *
     * The creation time, author and command id are pinned in saved state on the first attempt and reused by
     * every later one, including after process recreation, so a retry addresses the same group.
     */
    fun createGroup(name: String, onCreated: (String) -> Unit) {
        if (!creationInProgress.compareAndSet(false, true)) return
        _isCreating.value = true
        viewModelScope.launch {
            try {
                val createdAt = savedStateHandle.get<Long>(CREATED_AT_KEY) ?: (System.currentTimeMillis() / 1000).also {
                    savedStateHandle[CREATED_AT_KEY] = it
                }
                val author = savedStateHandle.get<String>(AUTHOR_KEY) ?: createGroup.currentAuthor().also {
                    savedStateHandle[AUTHOR_KEY] = it
                }
                val command = savedStateHandle.get<String>(COMMAND_KEY) ?: UUID.randomUUID().toString().also {
                    savedStateHandle[COMMAND_KEY] = it
                }
                val group = createGroup(name, _relays.value, createdAt, author, command)
                onCreated(group.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.toUiMessage(R.string.create_group_failed)
            } finally {
                _isCreating.value = false
                creationInProgress.set(false)
            }
        }
    }

    private fun setRelays(relays: List<String>) {
        _relays.value = relays
        savedStateHandle[RELAYS_KEY] = ArrayList(relays)
    }

    companion object {
        private const val RELAYS_KEY = "createGroupRelays"
        private const val CREATED_AT_KEY = "createGroupCreatedAt"
        private const val COMMAND_KEY = "createGroupCommand"
        private const val AUTHOR_KEY = "createGroupAuthor"
        private const val TAG = "CreateGroupVM"
    }
}
