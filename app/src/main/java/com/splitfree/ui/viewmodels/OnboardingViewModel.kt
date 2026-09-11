package com.splitfree.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.di.IoDispatcher
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.export.ImportGroupUseCase
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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** Outcome of the last backup import: `"Restored N events"` or `"Import failed: …"`. */
    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus = _importStatus.asStateFlow()

    /** True while a backup file is being read and imported. */
    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

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

    /**
     * Read the backup file at [uri] and import every group in it.
     *
     * Runs on [viewModelScope] rather than a composable scope so recomposition and configuration
     * changes do not cancel it mid-transaction, and the read is capped at [MAX_IMPORT_BYTES] so a
     * mis-picked multi-gigabyte file fails fast instead of exhausting the heap. The outcome is always
     * reported through [importStatus]; a second call while one is running is ignored.
     */
    fun importBackup(uri: Uri) {
        if (!_importing.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val count = withContext(ioDispatcher) { importContent(readBackup(uri)) }
                _importStatus.value = "Restored $count events"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Backup import failed: ${e.message}")
                _importStatus.value = "Import failed: ${e.message}"
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
