package com.splitfree.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.common.MlKitException
import com.splitfree.R
import com.splitfree.ui.util.QrScannerBackend
import com.splitfree.ui.util.QrScannerException
import com.splitfree.ui.util.isScannerCancellation
import com.splitfree.util.DebugLog as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal enum class QrScanPhase { Idle, Preparing, Scanning }

internal data class QrScanState(
    val phase: QrScanPhase = QrScanPhase.Idle,
    val error: Int? = null,
    val result: String? = null
) {
    val busy: Boolean get() = phase != QrScanPhase.Idle
}

/** Owns one SDK request, never an Activity or a callback into a disposed composition. */
internal class QrScanViewModel(private val backend: QrScannerBackend) : ViewModel() {
    private val mutableState = MutableStateFlow(QrScanState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var resumed = false
    private var generation = 0L

    fun setResumed(value: Boolean) {
        resumed = value
        // Do not open a camera later after the user leaves or backgrounds this screen. The actual
        // scanner owns another Activity, so pausing during Scanning must NOT discard its result.
        if (!value) cancelPreparation()
    }

    fun start() {
        if (!resumed || state.value.busy || state.value.result != null) return
        val request = ++generation
        mutableState.value = QrScanState(phase = QrScanPhase.Preparing)
        job = viewModelScope.launch {
            try {
                withTimeout(PREPARE_TIMEOUT_MS) { backend.prepare() }
                currentCoroutineContext().ensureActive()
                if (!resumed || request != generation) return@launch
                mutableState.value = QrScanState(phase = QrScanPhase.Scanning)
                val result = backend.scan()
                currentCoroutineContext().ensureActive()
                if (request != generation) return@launch
                mutableState.value = when {
                    result == null -> QrScanState() // Back in Google's scanner is not an error.
                    result.isBlank() -> QrScanState(error = R.string.qr_scan_empty)
                    else -> QrScanState(result = result)
                }
            } catch (_: TimeoutCancellationException) {
                if (request == generation) mutableState.value = QrScanState(error = R.string.qr_scan_failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isScannerCancellation(e)) {
                    if (request == generation) mutableState.value = QrScanState()
                    return@launch
                }
                // Only class and numeric code: scanned values and vendor exception messages may
                // contain a bearer invite. Do not pass the Throwable to logcat.
                Log.w(TAG, "Scanner failed: ${e.javaClass.simpleName}, code=${(e as? MlKitException)?.errorCode}")
                if (request == generation) {
                    val msg = errorMessage(e)
                    mutableState.value = if (msg != null) QrScanState(error = msg) else QrScanState()
                }
            } finally {
                if (request == generation && state.value.busy) mutableState.value = QrScanState()
            }
        }
    }

    fun cancelPreparation() {
        if (state.value.phase != QrScanPhase.Preparing) return
        ++generation
        job?.cancel()
        mutableState.value = QrScanState()
    }

    /** Clears transient scanner error feedback immediately (e.g. from user dismiss action). */
    fun dismissError() {
        if (state.value.error != null) {
            mutableState.value = state.value.copy(error = null)
        }
    }

    /** Invalidates active attempt and clears transient error when navigating away from Home. */
    fun onNavigatedAway() {
        ++generation
        job?.cancel()
        mutableState.value = QrScanState()
    }

    /** Consume before showing the invite prompt, so recreation cannot deliver it twice. */
    fun takeResult(): String? {
        if (!resumed) return null
        val result = state.value.result ?: return null
        mutableState.value = QrScanState()
        return result
    }

    fun deliveryFailed() {
        mutableState.value = QrScanState(error = R.string.invalid_invite_link)
    }

    private fun errorMessage(error: Exception): Int? = when {
        isScannerCancellation(error) -> null
        error is QrScannerException -> error.messageRes
        error is MlKitException -> when (error.errorCode) {
            MlKitException.CODE_SCANNER_CANCELLED -> null
            MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED -> R.string.qr_scan_camera_denied
            MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD -> R.string.qr_scan_services_unavailable
            MlKitException.CODE_SCANNER_UNAVAILABLE -> R.string.qr_scan_download_failed
            else -> R.string.qr_scan_failed
        }
        error is SecurityException -> R.string.qr_scan_camera_denied
        else -> R.string.qr_scan_failed
    }

    companion object {
        private const val TAG = "QrScanner"
        internal const val PREPARE_TIMEOUT_MS = 120_000L
    }
}
