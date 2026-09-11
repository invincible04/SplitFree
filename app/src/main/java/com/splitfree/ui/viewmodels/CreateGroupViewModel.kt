package com.splitfree.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.usecase.group.CreateGroupUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.components.RelayCheckStatus
import com.splitfree.ui.components.RelayInfo
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.util.toUiMessage
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Handles group creation with name validation and relay selection.
 */
@HiltViewModel
class CreateGroupViewModel
@Inject
constructor(
    private val createGroup: CreateGroupUseCase,
    private val relayHealthMonitor: RelayHealthMonitor,
    private val eventSigner: EventSigner
) : ViewModel() {
    private val _error = MutableStateFlow<UiMessage?>(null)
    val error: StateFlow<UiMessage?> = _error.asStateFlow()

    private val _relays = MutableStateFlow(RelayDefaults.DEFAULT_RELAYS)
    val relays: StateFlow<List<String>> = _relays.asStateFlow()

    private val _relayStatuses = MutableStateFlow<Map<String, RelayCheckStatus>>(emptyMap())
    val relayStatuses: StateFlow<Map<String, RelayCheckStatus>> = _relayStatuses.asStateFlow()

    private val _relayInfo = MutableStateFlow<Map<String, RelayInfo>>(emptyMap())
    val relayInfo: StateFlow<Map<String, RelayInfo>> = _relayInfo.asStateFlow()

    /** True while a group is being created; the screen disables the submit button. */
    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val creationInProgress = AtomicBoolean(false)

    fun addRelay(url: String) {
        _relays.value = (_relays.value + url).distinct()
    }

    fun clearError() {
        _error.value = null
    }

    fun removeRelay(url: String) {
        if (_relays.value.size > 1) _relays.value = _relays.value - url
    }

    fun checkRelay(url: String) {
        val isKnown = url in RelayDefaults.DEFAULT_RELAYS || url in RelayDefaults.FALLBACK_RELAYS
        val host = url.removePrefix("wss://")
        viewModelScope.launch {
            try {
                runRelayCheck(url, host, isKnown)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Relay check failed for $host: ${e.message}")
                _relayStatuses.value = _relayStatuses.value +
                    (url to if (isKnown) RelayCheckStatus.IDLE else RelayCheckStatus.OFFLINE)
                _error.value = UiMessage.Res(R.string.relay_check_failed, host)
            }
        }
    }

    private suspend fun runRelayCheck(url: String, host: String, isKnown: Boolean) {
        _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.CHECKING)
        relayHealthMonitor.checkRelays(listOf(url))
        val status = relayHealthMonitor.statuses[url]
        if (status?.online == true) {
            _relayInfo.value = _relayInfo.value +
                (
                    url to
                        RelayInfo(
                            paid = status.paid,
                            supportsGiftWrap = status.supportsGiftWrap,
                            latencyMs = status.latencyMs
                        )
                    )
        }
        if (isKnown) {
            // Default relays: ONLINE if NIP-11 passed, IDLE (grey) if not — never red
            _relayStatuses.value = _relayStatuses.value +
                (url to if (status?.online == true) RelayCheckStatus.ONLINE else RelayCheckStatus.IDLE)
            return
        }
        if (status?.online != true) {
            _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.OFFLINE)
            _relays.value = _relays.value - url
            _error.value = UiMessage.Res(R.string.relay_offline, host)
            return
        }
        _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.VERIFYING)
        val testEvent = eventSigner.createSignedEvent("verify-${System.nanoTime()}", "relay_test", "test")
        if (!relayHealthMonitor.verifyRelayRoundTrip(url, testEvent)) {
            _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.REJECTED)
            _relays.value = _relays.value - url
            _error.value = UiMessage.Res(R.string.relay_write_read_failed, host)
            return
        }
        _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.ONLINE)
    }

    fun checkAllRelays() {
        _relays.value.forEach { checkRelay(it) }
    }

    /**
     * Create the group once. A second call while the first is still running is ignored so a
     * double tap cannot create two groups; the guard is released when the attempt finishes.
     */
    fun createGroup(name: String, onCreated: (String) -> Unit) {
        if (!creationInProgress.compareAndSet(false, true)) return
        _isCreating.value = true
        viewModelScope.launch {
            try {
                val group = createGroup(name, _relays.value)
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

    companion object {
        private const val TAG = "CreateGroupVM"
    }
}
