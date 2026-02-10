package com.splitfree.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.splitfree.domain.crypto.IdentityManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val identity: IdentityManager
) : ViewModel() {

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun generateIdentity() {
        if (!identity.hasIdentity()) {
            identity.generateKeyPair()
        }
    }

    fun importKey(input: String): Boolean {
        return try {
            identity.importKey(input)
            _error.value = null
            true
        } catch (e: Exception) {
            _error.value = "Invalid key. Enter a hex private key or 24-word seed phrase."
            false
        }
    }

    fun clearError() { _error.value = null }
}
