package com.splitfree.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.domain.usecase.group.CreateGroupUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.components.RelayCheckStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
    private val relayHealthMonitor: RelayHealthMonitor
) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _relays = MutableStateFlow(RelayDefaults.DEFAULT_RELAYS)
    val relays: StateFlow<List<String>> = _relays.asStateFlow()

    private val _relayStatuses = MutableStateFlow<Map<String, RelayCheckStatus>>(emptyMap())
    val relayStatuses: StateFlow<Map<String, RelayCheckStatus>> = _relayStatuses.asStateFlow()

    fun addRelay(url: String) {
        _relays.value = (_relays.value + url).distinct()
    }

    fun removeRelay(url: String) {
        if (_relays.value.size > 1) _relays.value = _relays.value - url
    }

    fun checkRelay(url: String) {
        viewModelScope.launch {
            _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.CHECKING)
            relayHealthMonitor.checkRelays(listOf(url))
            val online = relayHealthMonitor.statuses[url]?.online == true
            _relayStatuses.value = _relayStatuses.value +
                (url to if (online) RelayCheckStatus.ONLINE else RelayCheckStatus.OFFLINE)
        }
    }

    fun checkAllRelays() {
        _relays.value.forEach { checkRelay(it) }
    }

    fun createGroup(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val group = createGroup(name, _relays.value)
                onCreated(group.id)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to create group"
            }
        }
    }
}
