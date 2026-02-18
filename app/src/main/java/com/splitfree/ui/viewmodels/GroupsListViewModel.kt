package com.splitfree.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.model.group.Group
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observes all groups and relay connection state for the groups list screen.
 */
@HiltViewModel
class GroupsListViewModel
@Inject
constructor(groupRepo: GroupRepository, nostrClient: NostrClient) : ViewModel() {
    val groups: Flow<List<Group>> = groupRepo.observeAll()
    val isConnected: StateFlow<Boolean> = nostrClient.connectionState
}
