package com.splitfree.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.settings.UserPreferences
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the settings screen: identity display, key backup/restore, relay config,
 * privacy toggles (gift wrap), and key revocation.
 */
@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val identity: IdentityManager,
    private val giftWrap: GiftWrapService,
    private val userPreferences: UserPreferences,
    private val revokeKeyUseCase: RevokeKeyUseCase
) : ViewModel() {
    private val _npub = MutableStateFlow(if (identity.hasIdentity()) identity.getPublicKeyHex() else "")
    val npub: StateFlow<String> = _npub

    // V4 fix: lazy-load private key only on reveal, clear on hide
    private val _nsec = MutableStateFlow("")
    val nsec: StateFlow<String> = _nsec

    private val _seedPhrase = MutableStateFlow<List<String>>(emptyList())
    val seedPhrase: StateFlow<List<String>> = _seedPhrase

    private val _customRelays = MutableStateFlow(userPreferences.getCustomRelays())
    val customRelays: StateFlow<List<String>> = _customRelays

    var giftWrapEnabled: Boolean
        get() = giftWrap.enabled
        set(value) {
            giftWrap.setEnabled(value)
        }

    fun setCustomRelays(relays: List<String>) {
        userPreferences.setCustomRelays(relays)
        _customRelays.value = userPreferences.getCustomRelays()
    }

    private val _revokeState = MutableStateFlow<RevokeState>(RevokeState.Idle)
    val revokeState: StateFlow<RevokeState> = _revokeState

    init {
        viewModelScope.launch {
            if (identity.hasPendingKeyPair()) {
                _revokeState.value = RevokeState.InProgress
                // Resume and wait for completion
                try {
                    revokeKeyUseCase.resumeIfNeeded()
                    _npub.value = identity.getPublicKeyHex()
                    _revokeState.value = RevokeState.Idle
                } catch (_: Exception) {
                    _revokeState.value = RevokeState.Idle
                }
            }
        }
    }

    fun revealPrivateKey() {
        if (identity.hasIdentity()) _nsec.value = identity.getPrivateKeyHex()
    }

    fun hidePrivateKey() {
        _nsec.value = ""
    }

    fun revealSeedPhrase() {
        if (identity.hasIdentity()) _seedPhrase.value = identity.exportAsMnemonic()
    }

    fun hideSeedPhrase() {
        _seedPhrase.value = emptyList()
    }

    fun revokeKey() {
        viewModelScope.launch {
            _revokeState.value = RevokeState.InProgress
            try {
                val newPub = revokeKeyUseCase()
                _npub.value = newPub
                _revokeState.value = RevokeState.Done(newPub)
            } catch (e: Exception) {
                _revokeState.value = RevokeState.Error(e.message ?: "Revocation failed")
            }
        }
    }
}

/**
 * State machine for the key revocation flow in the settings screen.
 */
sealed class RevokeState {
    data object Idle : RevokeState()

    data object InProgress : RevokeState()

    data class Done(val newPubkey: String) : RevokeState()

    data class Error(val message: String) : RevokeState()
}
