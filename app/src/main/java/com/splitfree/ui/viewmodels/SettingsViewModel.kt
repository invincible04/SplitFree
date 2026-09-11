package com.splitfree.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.export.ExportGroupUseCase
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.UpdateDisplayNameUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Drives the settings screen: identity display, key backup/restore,
 * privacy toggles (gift wrap), and key revocation.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val identity: IdentityContract,
    private val giftWrap: GiftWrapService,
    private val userPreferences: SettingsContract,
    private val revokeKeyUseCase: RevokeKeyUseCase,
    private val updateDisplayName: UpdateDisplayNameUseCase,
    private val groupRepo: GroupRepositoryContract,
    private val exportGroup: ExportGroupUseCase
) : ViewModel() {
    // Reading the pubkey hits Keystore-backed storage, which can throw SecureStorageException
    // on transient failures. Never let that crash the screen on open; show an empty npub instead.
    private val _npub = MutableStateFlow(
        runCatching { if (identity.hasIdentity()) identity.getPublicKeyHex() else "" }.getOrDefault("")
    )
    val npub: StateFlow<String> = _npub

    // V4 fix: lazy-load private key only on reveal, clear on hide
    private val _nsec = MutableStateFlow("")
    val nsec: StateFlow<String> = _nsec

    private val _seedPhrase = MutableStateFlow<List<String>>(emptyList())
    val seedPhrase: StateFlow<List<String>> = _seedPhrase

    private val _displayName = MutableStateFlow(userPreferences.displayName)
    val displayName: StateFlow<String> = _displayName

    init {
        // Debounce name changes to avoid spamming relays on every keystroke
        _displayName
            .debounce(800)
            .drop(1) // skip initial value
            .onEach { name ->
                try {
                    updateDisplayName(name)
                } catch (_: Exception) { }
            }
            .launchIn(viewModelScope)
    }

    fun setDisplayName(name: String) {
        userPreferences.displayName = name
        _displayName.value = userPreferences.displayName
    }

    var giftWrapEnabled: Boolean
        get() = giftWrap.enabled
        set(value) {
            giftWrap.setEnabled(value)
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

    /**
     * Export all groups as a JSON array, streaming each group directly to [out]
     * to avoid holding the entire export in memory.
     */
    suspend fun exportAllGroups(out: java.io.OutputStream) {
        out.bufferedWriter().use { writer ->
            val groups = groupRepo.getAll()
            writer.write("[")
            groups.forEachIndexed { i, group ->
                if (i > 0) writer.write(",")
                writer.write(exportGroup(group.id))
                writer.flush()
            }
            writer.write("]")
        }
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
