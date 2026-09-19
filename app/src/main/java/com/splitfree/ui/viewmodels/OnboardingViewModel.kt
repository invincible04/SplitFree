package com.splitfree.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.di.IoDispatcher
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.IdentityState
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.export.ImportGroupUseCase
import com.splitfree.domain.usecase.group.IdentitySwitchCoordinator
import com.splitfree.ui.util.UiMessage
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Outcome of a backup import; the screen renders it with string resources.
 */
sealed interface ImportStatus {
    /** Import succeeded and restored [count] events across all groups in the file. */
    data class Restored(val count: Int) : ImportStatus

    /** Import failed; [reason] is the exception message from the import path (domain text), if any. */
    data class Failed(val reason: String?) : ImportStatus
}

enum class OnboardingCompletion {
    Idle,
    Running,
    Pending,
    Acknowledged
}

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
    private val importGroup: ImportGroupUseCase,
    @ApplicationContext private val appContext: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val identitySwitch: IdentitySwitchCoordinator
) : ViewModel() {
    private val _error = MutableStateFlow<UiMessage?>(null)
    val error = _error.asStateFlow()

    /** Outcome of the last backup import, or null before the first attempt. */
    private val _importStatus = MutableStateFlow<ImportStatus?>(null)
    val importStatus = _importStatus.asStateFlow()

    /** True while a backup file is being read and imported. */
    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    /** True once a key has been successfully imported (shows the backup import option). */
    private val _keyImported = MutableStateFlow(false)
    val keyImported = _keyImported.asStateFlow()

    /**
     * Last observed store classification, rechecked before identity changes. An unavailable store
     * may still contain a usable key, so creation and import must wait for a successful retry.
     */
    private val _identityState = MutableStateFlow(identity.identityState())
    val identityState = _identityState.asStateFlow()

    /** Serializes creation, key import and store recovery while an identity operation is running. */
    private val _identityBusy = MutableStateFlow(false)
    val identityBusy = _identityBusy.asStateFlow()

    private val _completion = MutableStateFlow(OnboardingCompletion.Idle)
    val completion = _completion.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    fun createIdentity(displayName: String) {
        completeWhenReady { generateIdentity(displayName) }
    }

    fun restoreKey(input: String) {
        viewModelScope.launch { importKey(input) }
    }

    fun retryIdentity() {
        completeWhenReady { retryIdentityStore() }
    }

    fun acknowledgeCompletion() {
        _completion.compareAndSet(OnboardingCompletion.Pending, OnboardingCompletion.Acknowledged)
    }

    private fun completeWhenReady(operation: suspend () -> Boolean) {
        if (!_completion.compareAndSet(OnboardingCompletion.Idle, OnboardingCompletion.Running)) return
        viewModelScope.launch {
            try {
                if (operation()) _completion.value = OnboardingCompletion.Pending
            } finally {
                _completion.compareAndSet(OnboardingCompletion.Running, OnboardingCompletion.Idle)
            }
        }
    }

    /**
     * Re-probe the identity store after a transient failure.
     *
     * @return true if the stored identity is usable again and the caller may leave onboarding
     */
    suspend fun retryIdentityStore(): Boolean = withContext(ioDispatcher) {
        if (!_identityBusy.compareAndSet(expect = false, update = true)) return@withContext false
        try {
            _error.value = null
            val state = identity.identityState()
            _identityState.value = state
            if (state != IdentityState.READY) return@withContext false
            identitySwitch.resumeIfNeeded()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = UiMessage.Res(R.string.identity_storage_unavailable)
            false
        } finally {
            _identityBusy.value = false
        }
    }

    /**
     * Recheck storage before generating a key: reuse READY, refuse UNAVAILABLE, and generate only
     * for ABSENT or RECOVERY_REQUIRED. Save a nonblank name for the resulting identity.
     * A false [IdentityContract.hasIdentity] alone cannot distinguish a lost key from a transient failure.
     *
     * @return true if the device now has a usable identity and the caller may leave onboarding
     */
    suspend fun generateIdentity(displayName: String = ""): Boolean = withContext(ioDispatcher) {
        if (!_identityBusy.compareAndSet(expect = false, update = true)) return@withContext false
        try {
            val state = identity.identityState()
            _identityState.value = state
            val pubkey = when (state) {
                IdentityState.UNAVAILABLE -> {
                    // Never replace a key that may still be there; the user must retry first.
                    _error.value = UiMessage.Res(R.string.identity_storage_unavailable)
                    return@withContext false
                }
                IdentityState.ABSENT, IdentityState.RECOVERY_REQUIRED -> identitySwitch.generateKeyPair()
                IdentityState.READY -> {
                    identitySwitch.resumeIfNeeded()
                    identity.getPublicKeyHex()
                }
            }
            if (displayName.isNotBlank()) {
                identitySwitch.withIdentity(pubkey) {
                    settings.saveDisplayNameIntent(pubkey, displayName)
                }
            }
            _error.value = null
            _identityState.value = IdentityState.READY
            return@withContext true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Identity could not be created: ${e.message}")
            _error.value = UiMessage.Res(R.string.identity_storage_unavailable)
            _identityState.value = identity.identityState()
            return@withContext false
        } finally {
            _identityBusy.value = false
        }
    }

    /**
     * Distinguish invalid input from storage failures so a device error does not invite the user to
     * change their recovery phrase. An unavailable store is refused before the input is validated.
     */
    suspend fun importKey(input: String): Boolean = withContext(ioDispatcher) {
        if (!_identityBusy.compareAndSet(expect = false, update = true)) return@withContext false
        try {
            val state = identity.identityState()
            _identityState.value = state
            if (state == IdentityState.UNAVAILABLE) {
                _error.value = UiMessage.Res(R.string.identity_storage_unavailable)
                return@withContext false
            }
            identitySwitch.importKey(input)
            _error.value = null
            _keyImported.value = true
            _identityState.value = IdentityState.READY
            return@withContext true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecureStorageException) {
            Log.w(TAG, "Key import could not be stored: ${e.message}")
            _error.value = UiMessage.Res(R.string.identity_storage_unavailable)
            _identityState.value = identity.identityState()
            return@withContext false
        } catch (e: IllegalArgumentException) {
            _error.value = UiMessage.Res(R.string.invalid_key_input)
            return@withContext false
        } catch (e: Exception) {
            _error.value = UiMessage.Res(R.string.identity_storage_unavailable)
            return@withContext false
        } finally {
            _identityBusy.value = false
        }
    }

    /**
     * Read the backup file at [uri] and import every group in it.
     *
     * The ViewModel owns the import across configuration changes; clearing it cancels the work.
     * Reads are capped at [MAX_IMPORT_BYTES] before parsing. Each group commits separately, so a
     * later failure does not undo earlier groups. Cancellation propagates without an [importStatus] result;
     * ordinary success/failure is reported there. Concurrent import requests are ignored.
     */
    fun importBackup(uri: Uri) {
        if (!_importing.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val count = withContext(ioDispatcher) { importContent(readBackup(uri)) }
                _importStatus.value = ImportStatus.Restored(count)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Backup import failed: ${e.message}")
                _importStatus.value = ImportStatus.Failed(e.message)
            } finally {
                _importing.value = false
            }
        }
    }

    /**
     * Read at most [MAX_IMPORT_BYTES] from [uri].
     *
     * @throws IOException if the file cannot be opened (deleted, permission revoked)
     * @throws IllegalArgumentException if the file is larger than [MAX_IMPORT_BYTES]
     */
    private fun readBackup(uri: Uri): String {
        val input = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.w(TAG, "Backup read failed: ${e.message}")
            null
        } ?: throw IOException("could not read the backup file")
        return input.use { readBounded(it, MAX_IMPORT_BYTES) }.toString(Charsets.UTF_8)
    }

    /** Copy [input] to memory, aborting as soon as more than [maxBytes] have been read. */
    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            require(total <= maxBytes) { "Backup file is too large" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** A backup file is either a single export or a JSON array of them (Settings → Export All Groups). */
    private suspend fun importContent(content: String): Int {
        val trimmed = content.trimStart()
        return if (trimmed.startsWith("[")) {
            json.decodeFromString<List<SplitFreeExport>>(trimmed).sumOf { importGroup(it) }
        } else {
            importGroup(json.decodeFromString<SplitFreeExport>(trimmed))
        }
    }

    fun clearError() {
        _error.value = null
    }

    companion object {
        private const val TAG = "OnboardingViewModel"

        /** Hard cap on the size of a backup file; larger picks are rejected before parsing. */
        const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
        private const val READ_CHUNK_BYTES = 64 * 1024
    }
}
