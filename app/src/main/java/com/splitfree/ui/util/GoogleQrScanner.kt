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
        val barcode = scanner.startScan().awaitResult() ?: return null
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
