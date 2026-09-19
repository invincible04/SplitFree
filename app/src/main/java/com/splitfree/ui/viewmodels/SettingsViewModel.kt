package com.splitfree.ui.viewmodels

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.di.IoDispatcher
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.export.ExportGroupUseCase
import com.splitfree.domain.usecase.group.DisplayNamePublisher
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives identity display and backup, display-name publication, gift-wrap preferences,
 * key revocation, and outbox health. Key import belongs to onboarding.
 */
@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val identity: IdentityContract,
    private val giftWrap: GiftWrapService,
    private val revokeKeyUseCase: RevokeKeyUseCase,
    private val namePublisher: DisplayNamePublisher,
    private val groupRepo: GroupRepositoryContract,
    private val exportGroup: ExportGroupUseCase,
    @ApplicationContext private val appContext: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    outboxDao: OutboxDao
) : ViewModel() {
    // Reading the pubkey hits Keystore-backed storage, which can throw SecureStorageException
    // on transient failures. Never let that crash the screen on open; show an empty npub instead.
    private val _npub = MutableStateFlow(
        runCatching { if (identity.hasIdentity()) identity.getPublicKeyHex() else "" }.getOrDefault("")
    )
    val npub: StateFlow<String> = _npub

    // Keep secret text out of UI state until explicitly revealed; hiding drops the reference.
    private val _nsec = MutableStateFlow("")
    val nsec: StateFlow<String> = _nsec

    private val _seedPhrase = MutableStateFlow<List<String>>(emptyList())
    val seedPhrase: StateFlow<List<String>> = _seedPhrase

    val displayName: StateFlow<String> = namePublisher.displayName
    val namePublication = namePublisher.result

    /**
     * `(pending, stuck)` outbox counts: events not yet accepted by any relay, and the subset that
     * has failed enough attempts to be retried only every few hours. A DB error degrades to
     * `(0, 0)` rather than crashing the screen.
     */
    val outboxStatus: StateFlow<Pair<Int, Int>> =
        combine(outboxDao.pendingOutboxCount(), outboxDao.stuckOutboxCount()) { pending, stuck -> pending to stuck }
            .catch { emit(0 to 0) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0 to 0)

    init {
        namePublisher.start()
    }

    private val _nameSaveFailed = MutableStateFlow(false)
    val nameSaveFailed: StateFlow<Boolean> = _nameSaveFailed

    fun setDisplayName(name: String) {
        try {
            namePublisher.submit(name)
            _nameSaveFailed.value = false
        } catch (e: Exception) {
            _nameSaveFailed.value = true
            Log.w(TAG, "Display name was not saved: ${e.javaClass.simpleName}")
        }
    }

    fun clearNameSaveFailure() {
        _nameSaveFailed.value = false
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

    /** State of the backup export; the screen reports [ExportState.Done]/[ExportState.Error] and clears it. */
    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState

    /**
     * Export all groups as a JSON array to [uri], streaming one group at a time so the whole
     * export is never held in memory.
     *
     * [viewModelScope] survives configuration changes; clearing the ViewModel cancels the write.
     * Failure or cancellation triggers best-effort deletion of the partial SAF document, not a guarantee
     * that the provider removes it. Concurrent export requests are ignored.
     */
    fun exportAllGroups(uri: Uri) {
        if (_exportState.value is ExportState.InProgress) return
        _exportState.value = ExportState.InProgress
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { writeAllGroups(uri) }
                _exportState.value = ExportState.Done
            } catch (e: Exception) {
                withContext(NonCancellable + ioDispatcher) { discardPartialExport(uri) }
                if (e is CancellationException) {
                    _exportState.value = ExportState.Idle
                    throw e
                }
                Log.w(TAG, "Export failed: ${e.message}")
                _exportState.value = ExportState.Error(e.message ?: "Export failed")
            }
        }
    }

    fun clearExportState() {
        if (_exportState.value is ExportState.InProgress) return
        _exportState.value = ExportState.Idle
    }

    private suspend fun writeAllGroups(uri: Uri) {
        exportGroup.openSession().use { session ->
            // "wt" truncates, so overwriting a backup never leaves a stale tail behind.
            val out = appContext.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Could not open the backup file for writing")
            out.bufferedWriter().use { writer ->
                val groups = groupRepo.getAll()
                writer.write("[")
                groups.forEachIndexed { i, group ->
                    if (i > 0) writer.write(",")
                    writer.write(exportGroup(group.id, session))
                    writer.flush()
                }
                session.requireCurrentIdentity()
                writer.write("]")
            }
            // Provider flush/close can suspend the operation long enough for identity replacement.
            session.requireCurrentIdentity()
        }
    }

    /** Best-effort removal of a half-written backup; only SAF documents can be deleted through a Uri. */
    private fun discardPartialExport(uri: Uri) {
        try {
            if (DocumentsContract.isDocumentUri(appContext, uri)) {
                DocumentsContract.deleteDocument(appContext.contentResolver, uri)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete partial backup: ${e.message}")
        }
    }

    /** Starts the identity replacement; a tap while one is already running (or resuming) is ignored. */
    fun revokeKey() {
        if (_revokeState.value is RevokeState.InProgress) return
        _revokeState.value = RevokeState.InProgress
        viewModelScope.launch {
            try {
                val newPub = revokeKeyUseCase()
                _npub.value = newPub
                _revokeState.value = RevokeState.Done(newPub)
            } catch (e: Exception) {
                _revokeState.value = RevokeState.Error(e.message ?: "Revocation failed")
            }
        }
    }

    /**
     * Clear only a finished revocation result. Keeping [RevokeState.InProgress] preserves the
     * duplicate-tap guard while the operation is running or resuming.
     */
    fun clearRevokeState() {
        if (_revokeState.value !is RevokeState.InProgress) _revokeState.value = RevokeState.Idle
    }

    private companion object {
        const val TAG = "SettingsViewModel"
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

/**
 * State machine for the backup export flow in the settings screen.
 */
sealed class ExportState {
    data object Idle : ExportState()

    data object InProgress : ExportState()

    data object Done : ExportState()

    data class Error(val message: String) : ExportState()
}
