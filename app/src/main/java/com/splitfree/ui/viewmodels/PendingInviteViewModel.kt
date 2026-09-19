package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.splitfree.ui.util.decodeInviteForConfirmation

/** Retains the bearer link in Android saved state, never preferences, until cancelled or navigated. */
class PendingInviteViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    val pendingLink = savedState.getStateFlow<String?>(KEY, null)

    fun offer(link: String): Boolean {
        if (decodeInviteForConfirmation(link) == null) return false
        savedState[KEY] = link
        return true
    }

    fun dismiss() {
        savedState[KEY] = null
    }

    fun joined(groupId: String) {
        if (pendingLink.value?.let(::decodeInviteForConfirmation)?.groupId == groupId) dismiss()
    }

    private companion object {
        const val KEY = "pending_invite"
    }
}
