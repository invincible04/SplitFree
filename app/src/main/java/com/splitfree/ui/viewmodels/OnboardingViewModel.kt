package com.splitfree.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Handles first-launch identity generation, display name setup, and optional key import from mnemonic.
 */
@HiltViewModel
class OnboardingViewModel
@Inject
constructor(
    private val identity: IdentityContract,
    private val settings: SettingsContract
) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun generateIdentity(displayName: String = "") {
        if (!identity.hasIdentity()) {
            identity.generateKeyPair()
        }
        if (displayName.isNotBlank()) {
            settings.displayName = displayName
        }
    }

    fun importKey(input: String): Boolean = try {
        identity.importKey(input)
        _error.value = null
        true
    } catch (e: Exception) {
        _error.value = "Invalid key. Enter a hex private key or 24-word seed phrase."
        false
    }

    fun clearError() {
        _error.value = null
    }
}
