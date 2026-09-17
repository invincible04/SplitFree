package com.splitfree.ui.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.splitfree.R
import com.splitfree.util.DebugLog as Log
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

internal interface QrScannerBackend {
    suspend fun prepare()
    suspend fun scan(): String?
}

internal class QrScannerException(@StringRes val messageRes: Int) : Exception()

internal class GoogleQrScanner(
    context: Context,
    private val availability: GoogleApiAvailabilityLight = GoogleApiAvailabilityLight.getInstance(),
    scannerFactory: (Context) -> GmsBarcodeScanner = { applicationContext ->
        GmsBarcodeScanning.getClient(
            applicationContext,
            GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    },
    moduleInstallFactory: (Context) -> ModuleInstallClient = { ModuleInstall.getClient(it) }
) : QrScannerBackend {
    private val applicationContext = context.applicationContext
    private val scanner by lazy { scannerFactory(applicationContext) }
    private val moduleInstaller by lazy { moduleInstallFactory(applicationContext) }

    override suspend fun prepare() {
        currentCoroutineContext().ensureActive()
        if (availability.isGooglePlayServicesAvailable(applicationContext) != ConnectionResult.SUCCESS) {
            throw QrScannerException(R.string.qr_scan_services_unavailable)
        }
        if (!applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            throw QrScannerException(R.string.qr_scan_no_camera)
        }
        // Client construction and readiness checks are not downloads. Let setup failures reach
        // the controller as generic scanner errors instead of blaming connectivity or storage.
        val available = moduleInstaller.areModulesAvailable(scanner).awaitResult()
            ?: throw QrScannerException(R.string.qr_scan_failed)
        if (available.areModulesAvailable()) return
        try {
            installScannerModule()
        } catch (error: CancellationException) {
            throw error
        } catch (error: QrScannerException) {
            throw error
        } catch (error: Exception) {
            throw QrScannerException(R.string.qr_scan_download_failed).apply { initCause(error) }
        }
    }

    override suspend fun scan(): String? {
        currentCoroutineContext().ensureActive()
        val barcode = try {
            scanner.startScan().awaitResult() ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isScannerCancellation(e)) return null
            throw e
        }
        return barcode.rawValue.orEmpty()
    }

    private suspend fun installScannerModule() {
        val completed = CompletableDeferred<Unit>()
        val closed = AtomicBoolean(false)
        val listener = InstallStatusListener { update ->
            when (update.installState) {
                InstallState.STATE_COMPLETED -> completed.complete(Unit)
                InstallState.STATE_FAILED, InstallState.STATE_CANCELED ->
                    completed.completeExceptionally(QrScannerException(R.string.qr_scan_download_failed))
            }
        }
        try {
            val request = ModuleInstallRequest.newBuilder()
                .addApi(scanner)
                .setListener(listener, directExecutor)
                .build()
            currentCoroutineContext().ensureActive()
            moduleInstaller.installModules(request).addOnCompleteListener(directExecutor) { task ->
                if (closed.get()) {
                    // Cancellation or a terminal update can precede SDK listener registration.
                    detachListener(listener)
                } else if (task.isCanceled) {
                    completed.completeExceptionally(QrScannerException(R.string.qr_scan_download_failed))
                } else if (!task.isSuccessful) {
                    completed.completeExceptionally(
                        task.exception ?: QrScannerException(R.string.qr_scan_download_failed)
                    )
                } else if (task.result.areModulesAlreadyInstalled()) {
                    completed.complete(Unit)
                }
                // A successful initiation response does not mean the module has finished installing.
            }
            completed.await()
        } finally {
            closed.set(true)
            completed.cancel()
            detachListener(listener)
        }
    }

    private fun detachListener(listener: InstallStatusListener) {
        try {
            moduleInstaller.unregisterListener(listener).addOnFailureListener(directExecutor) {
                Log.w(TAG, "Could not unregister scanner module listener")
            }
        } catch (_: RuntimeException) {
            // Cleanup must not replace the scan result or escape a cancellation callback.
            Log.w(TAG, "Could not unregister scanner module listener")
        }
    }

    private companion object {
        const val TAG = "GoogleQrScanner"
        val directExecutor = Executor { it.run() }

        suspend fun <T> Task<T>.awaitResult(): T? = suspendCancellableCoroutine { continuation ->
            addOnCompleteListener(directExecutor) { task ->
                if (!continuation.isActive) return@addOnCompleteListener
                when {
                    task.isCanceled -> continuation.resume(null)
                    task.isSuccessful -> continuation.resume(task.result)
                    else -> continuation.resumeWithException(checkNotNull(task.exception))
                }
            }
        }
    }
}

/** Google Play Services [CommonStatusCodes.CANCELED] without a compile-time GMS dependency. */
private const val GMS_STATUS_CANCELED = 16

/** Max cause-chain depth to inspect, preventing stack overflow on circular exception wrappers. */
private const val MAX_CAUSE_DEPTH = 5

/**
 * Recognizes clean user cancellation across GMS Task cancellation, ML Kit
 * and Play Services status codes. Inspects the cause chain up to [MAX_CAUSE_DEPTH]
 * levels to catch wrapped cancellations without risking unbounded recursion.
 */
internal fun isScannerCancellation(e: Throwable, depth: Int = 0): Boolean {
    if (e is CancellationException) return true
    if (e is MlKitException && e.errorCode == MlKitException.CODE_SCANNER_CANCELLED) return true
    val statusCode = try {
        val method = e.javaClass.getMethod("getStatusCode")
        method.invoke(e) as? Int
    } catch (_: Throwable) {
        null
    }
    if (statusCode == GMS_STATUS_CANCELED) return true
    val status = try {
        val method = e.javaClass.getMethod("getStatus")
        method.invoke(e)
    } catch (_: Throwable) {
        null
    }
    if (status != null) {
        val code = try {
            val codeMethod = status.javaClass.getMethod("getStatusCode")
            codeMethod.invoke(status) as? Int
        } catch (_: Throwable) {
            null
        }
        if (code == GMS_STATUS_CANCELED) return true
    }
    // Walk the cause chain with bounded depth to catch wrapped cancellations.
    val cause = e.cause
    if (cause != null && cause !== e && depth < MAX_CAUSE_DEPTH) {
        return isScannerCancellation(cause, depth + 1)
    }
    return false
}
