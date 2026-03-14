package com.splitfree.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.export.ImportGroupUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Handles first-launch identity generation, display name setup, optional key import from mnemonic,
 * and backup file import after key restore.
 */
@HiltViewModel
class OnboardingViewModel
@Inject
constructor(
    private val identity: IdentityContract,
    private val settings: SettingsContract,
    private val importGroup: ImportGroupUseCase
) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus = _importStatus.asStateFlow()

    /** True once a key has been successfully imported (shows the backup import option). */
    private val _keyImported = MutableStateFlow(false)
    val keyImported = _keyImported.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

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
        _keyImported.value = true
        true
    } catch (e: Exception) {
        _error.value = "Invalid key. Enter a hex private key or 24-word seed phrase."
        false
    }

    suspend fun importBackup(jsonContent: String) {
        try {
            val trimmed = jsonContent.trimStart()
            val count = if (trimmed.startsWith("[")) {
                val exports = json.decodeFromString<List<SplitFreeExport>>(trimmed)
                exports.sumOf { importGroup(json.encodeToString(it)) }
            } else {
                importGroup(jsonContent)
            }
            _importStatus.value = "Restored $count events"
        } catch (e: Exception) {
            _importStatus.value = "Import failed: ${e.message}"
        }
    }

    fun clearError() {
        _error.value = null
    }
}
