package com.splitfree.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.usecase.RevokeKeyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val identity: IdentityManager,
    private val giftWrap: GiftWrapService,
    private val revokeKeyUseCase: RevokeKeyUseCase
) : ViewModel() {
    private val _npub = MutableStateFlow(if (identity.hasIdentity()) identity.getPublicKeyHex() else "")
    val npub: StateFlow<String> = _npub

    private val _nsec = MutableStateFlow(if (identity.hasIdentity()) identity.getPrivateKeyHex() else "")
    val nsec: StateFlow<String> = _nsec

    private val _seedPhrase = MutableStateFlow(if (identity.hasIdentity()) identity.exportAsMnemonic() else emptyList())
    val seedPhrase: StateFlow<List<String>> = _seedPhrase

    var giftWrapEnabled: Boolean
        get() = giftWrap.enabled
        set(value) { giftWrap.enabled = value }

    private val _revokeState = MutableStateFlow<RevokeState>(RevokeState.Idle)
    val revokeState: StateFlow<RevokeState> = _revokeState

    fun revokeKey() {
        viewModelScope.launch {
            _revokeState.value = RevokeState.InProgress
            try {
                val newPub = revokeKeyUseCase()
                _npub.value = newPub
                _nsec.value = identity.getPrivateKeyHex()
                _seedPhrase.value = identity.exportAsMnemonic()
                _revokeState.value = RevokeState.Done(newPub)
            } catch (e: Exception) {
                _revokeState.value = RevokeState.Error(e.message ?: "Revocation failed")
            }
        }
    }
}

sealed class RevokeState {
    data object Idle : RevokeState()
    data object InProgress : RevokeState()
    data class Done(val newPubkey: String) : RevokeState()
    data class Error(val message: String) : RevokeState()
}
