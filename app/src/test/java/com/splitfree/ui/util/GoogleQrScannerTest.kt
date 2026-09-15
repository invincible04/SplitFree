package com.splitfree.ui.util

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleAvailabilityResponse
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallResponse
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.splitfree.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class GoogleQrScannerTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val context = mockk<Context>()
    private val applicationContext = mockk<Context>()
    private val packageManager = mockk<PackageManager>()
    private val availability = mockk<GoogleApiAvailabilityLight>()
    private val scanner = mockk<GmsBarcodeScanner>()
    private val moduleInstaller = mockk<ModuleInstallClient>()
    private val scannerFactory = mockk<(Context) -> GmsBarcodeScanner>()
    private val moduleInstallFactory = mockk<(Context) -> ModuleInstallClient>()
    private val installTask = TaskCompletionSource<ModuleInstallResponse>()
    private val request = slot<ModuleInstallRequest>()

    private val listener: InstallStatusListener get() = checkNotNull(request.captured.listener)

    @Before
    fun setup() {
        every { context.applicationContext } returns applicationContext
        every { applicationContext.packageManager } returns packageManager
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) } returns true
        every { availability.isGooglePlayServicesAvailable(applicationContext) } returns ConnectionResult.SUCCESS
        every { scannerFactory(applicationContext) } returns scanner
        every { moduleInstallFactory(applicationContext) } returns moduleInstaller
        every { moduleInstaller.areModulesAvailable(scanner) } returns
            Tasks.forResult(ModuleAvailabilityResponse(false, 0))
        every { moduleInstaller.installModules(capture(request)) } returns installTask.task
        every { moduleInstaller.unregisterListener(any()) } returns Tasks.forResult(true)
    }

    @Test
    fun `SDK clients are lazy and receive only application context`() = runTest(dispatcher) {
        every { moduleInstaller.areModulesAvailable(scanner) } returns
            Tasks.forResult(ModuleAvailabilityResponse(true, 0))
        every { scanner.startScan() } returns Tasks.forResult(barcode("invite"))
        val backend = backend()
        verify(exactly = 0) { scannerFactory(any()) }
        verify(exactly = 0) { moduleInstallFactory(any()) }

        backend.prepare()
        assertEquals("invite", backend.scan())
        backend.prepare()

        verify(exactly = 1) { scannerFactory(applicationContext) }
        verify(exactly = 1) { moduleInstallFactory(applicationContext) }
        verify(exactly = 0) { scannerFactory(context) }
        verify(exactly = 0) { moduleInstallFactory(context) }
        verify(exactly = 0) { context.packageManager }
    }

    @Test
    fun `unavailable Play services prevents scanner and module client creation`() = runTest(dispatcher) {
        for (status in listOf(
            ConnectionResult.SERVICE_MISSING,
            ConnectionResult.SERVICE_DISABLED,
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED,
            ConnectionResult.SERVICE_UPDATING,
            ConnectionResult.SERVICE_INVALID
        )) {
            every { availability.isGooglePlayServicesAvailable(applicationContext) } returns status
            assertMessage(R.string.qr_scan_services_unavailable, runCatching { backend().prepare() }.exceptionOrNull())
        }
        verify(exactly = 0) { scannerFactory(any()) }
        verify(exactly = 0) { moduleInstallFactory(any()) }
    }

    @Test
    fun `device without any camera is rejected before SDK client creation`() = runTest(dispatcher) {
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) } returns false

        assertMessage(R.string.qr_scan_no_camera, runCatching { backend().prepare() }.exceptionOrNull())

        verify(exactly = 0) { scannerFactory(any()) }
        verify(exactly = 0) { moduleInstallFactory(any()) }
    }

    @Test
    fun `available module needs no install and never starts scanner during preparation`() = runTest(dispatcher) {
        every { moduleInstaller.areModulesAvailable(scanner) } returns
            Tasks.forResult(ModuleAvailabilityResponse(true, 0))

        backend().prepare()

        verify(exactly = 0) { moduleInstaller.installModules(any()) }
        verify(exactly = 0) { moduleInstaller.unregisterListener(any()) }
        verify(exactly = 0) { scanner.startScan() }
    }

    @Test
    fun `availability failure preserves the setup exception without claiming a download`() = runTest(dispatcher) {
        val failure = IllegalStateException("module check failed")
        every { moduleInstaller.areModulesAvailable(scanner) } returns Tasks.forException(failure)

        val error = runCatching { backend().prepare() }.exceptionOrNull()

        assertOriginalException(failure, error)
        verify(exactly = 0) { moduleInstaller.installModules(any()) }
    }

    @Test
    fun `canceled availability task is a generic failure not caller cancellation`() = runTest(dispatcher) {
        every { moduleInstaller.areModulesAvailable(scanner) } returns Tasks.forCanceled()

        assertMessage(R.string.qr_scan_failed, runCatching { backend().prepare() }.exceptionOrNull())
    }

    @Test
    fun `scanner constructor failure is not misreported as a download failure`() = runTest(dispatcher) {
        val failure = NullPointerException("scanner component unavailable")
        every { scannerFactory(applicationContext) } throws failure

        assertSame(failure, runCatching { backend().prepare() }.exceptionOrNull())
        verify(exactly = 0) { moduleInstaller.areModulesAvailable(any()) }
        verify(exactly = 0) { moduleInstaller.installModules(any()) }
        verify(exactly = 0) { scanner.startScan() }
    }

    @Test
    fun `same backend can retry a failed lazy scanner constructor`() = runTest(dispatcher) {
        val failure = NullPointerException("scanner component unavailable")
        val backend = backend()
        every { scannerFactory(applicationContext) } throws failure
        assertSame(failure, runCatching { backend.prepare() }.exceptionOrNull())

        every { scannerFactory(applicationContext) } returns scanner
        every { moduleInstaller.areModulesAvailable(scanner) } returns
            Tasks.forResult(ModuleAvailabilityResponse(true, 0))
        every { scanner.startScan() } returns Tasks.forResult(barcode("invite"))
        backend.prepare()
        assertEquals("invite", backend.scan())

        verify(exactly = 2) { scannerFactory(applicationContext) }
        verify(exactly = 1) { moduleInstallFactory(applicationContext) }
        verify(exactly = 0) { moduleInstaller.installModules(any()) }
    }

    @Test
    fun `module client constructor failure preserves the setup exception`() = runTest(dispatcher) {
        val failure = IllegalStateException("module client unavailable")
        every { moduleInstallFactory(applicationContext) } throws failure

        assertSame(failure, runCatching { backend().prepare() }.exceptionOrNull())
        verify(exactly = 0) { scannerFactory(any()) }
        verify(exactly = 0) { moduleInstaller.installModules(any()) }
    }

    @Test
    fun `synchronous availability failure does not claim a download happened`() = runTest(dispatcher) {
        val failure = IllegalStateException("module readiness unavailable")
        every { moduleInstaller.areModulesAvailable(scanner) } throws failure

        assertSame(failure, runCatching { backend().prepare() }.exceptionOrNull())
        verify(exactly = 0) { moduleInstaller.installModules(any()) }
    }

    @Test
    fun `caller cancellation from scanner construction is preserved`() = runTest(dispatcher) {
        val failure = kotlinx.coroutines.CancellationException("caller canceled")
        every { scannerFactory(applicationContext) } throws failure

        assertSame(failure, runCatching { backend().prepare() }.exceptionOrNull())
        verify(exactly = 0) { moduleInstaller.installModules(any()) }
    }

    @Test
    fun `caller cancellation during availability never initiates a late install`() = runTest(dispatcher) {
        val available = TaskCompletionSource<ModuleAvailabilityResponse>()
        every { moduleInstaller.areModulesAvailable(scanner) } returns available.task
        val preparation = launch { backend().prepare() }

        preparation.cancel()
        preparation.join()
        available.setResult(ModuleAvailabilityResponse(false, 0))

        assertTrue(preparation.isCancelled)
        verify(exactly = 0) { moduleInstaller.installModules(any()) }
    }

    @Test
    fun `successful initiation and nonterminal updates do not complete preparation`() = runTest(dispatcher) {
        val preparation = async { backend().prepare() }
        assertEquals(listOf(scanner), request.captured.apis)
        assertFalse(preparation.isCompleted)

        installTask.setResult(ModuleInstallResponse(42))
        assertFalse(preparation.isCompleted)
        for (state in listOf(
            InstallState.STATE_UNKNOWN,
            InstallState.STATE_PENDING,
            InstallState.STATE_DOWNLOADING,
            InstallState.STATE_INSTALLING,
            InstallState.STATE_DOWNLOAD_PAUSED
        )) {
            update(state)
            assertFalse(preparation.isCompleted)
        }
        verify(exactly = 0) { moduleInstaller.unregisterListener(any()) }

        update(InstallState.STATE_COMPLETED)
        preparation.await()

        verify(exactly = 1) { moduleInstaller.unregisterListener(listener) }
        verify(exactly = 0) { scanner.startScan() }
    }

    @Test
    fun `already installed initiation response completes without a status update`() = runTest(dispatcher) {
        val preparation = async { backend().prepare() }

        installTask.setResult(ModuleInstallResponse(0))
        preparation.await()

        verify(exactly = 1) { moduleInstaller.unregisterListener(listener) }
    }

    @Test
    fun `failed install initiation detaches and preserves failure cause`() = runTest(dispatcher) {
        val failure = IllegalStateException("download request failed")
        val preparation = async { runCatching { backend().prepare() } }

        installTask.setException(failure)
        val error = preparation.await().exceptionOrNull()

        assertMessage(R.string.qr_scan_download_failed, error)
        assertOriginalException(failure, error?.cause)
        verify(exactly = 1) { moduleInstaller.unregisterListener(listener) }
    }

    @Test
    fun `synchronous install failure detaches captured listener`() = runTest(dispatcher) {
        val failure = IllegalStateException("cannot register")
        every { moduleInstaller.installModules(capture(request)) } throws failure

        val error = runCatching { backend().prepare() }.exceptionOrNull()

        assertMessage(R.string.qr_scan_download_failed, error)
        assertSame(failure, error?.cause)
        verify(exactly = 1) { moduleInstaller.unregisterListener(listener) }
    }

    @Test
    fun `canceled install initiation detaches as download failure`() = runTest(dispatcher) {
        every { moduleInstaller.installModules(capture(request)) } returns Tasks.forCanceled()

        assertMessage(R.string.qr_scan_download_failed, runCatching { backend().prepare() }.exceptionOrNull())

        verify(exactly = 1) { moduleInstaller.unregisterListener(listener) }
    }

    @Test
    fun `failed install status detaches and reports download failure`() = runTest(dispatcher) {
        val preparation = async { runCatching { backend().prepare() } }
        installTask.setResult(ModuleInstallResponse(42))

        update(InstallState.STATE_FAILED)

        assertMessage(R.string.qr_scan_download_failed, preparation.await().exceptionOrNull())
        verify(exactly = 1) { moduleInstaller.unregisterListener(listener) }
    }

    @Test
    fun `canceled install status reports download failure rather than scan dismissal`() = runTest(dispatcher) {
        val preparation = async { runCatching { backend().prepare() } }
        installTask.setResult(ModuleInstallResponse(42))

        update(InstallState.STATE_CANCELED)

        assertMessage(R.string.qr_scan_download_failed, preparation.await().exceptionOrNull())
        verify(exactly = 1) { moduleInstaller.unregisterListener(listener) }
    }

    @Test
    fun `caller timeout bounds the real download and detaches listener`() = runTest(dispatcher) {
        val preparation = async { withTimeoutOrNull(120_000) { backend().prepare() } }
        installTask.setResult(ModuleInstallResponse(42))
        advanceTimeBy(119_999)
        assertFalse(preparation.isCompleted)

        advanceTimeBy(1)
        runCurrent()

        assertNull(preparation.await())
        verify(exactly = 1) { moduleInstaller.unregisterListener(listener) }
    }

    @Test
    fun `cancellation detaches immediately and again after late registration success`() = runTest(dispatcher) {
        val preparation = launch { backend().prepare() }

        preparation.cancel()
        preparation.join()
        verify(exactly = 1) { moduleInstaller.unregisterListener(listener) }

        installTask.setResult(ModuleInstallResponse(42))
        update(InstallState.STATE_COMPLETED)

        assertTrue(preparation.isCancelled)
        verify(exactly = 2) { moduleInstaller.unregisterListener(listener) }
        verify(exactly = 0) { scanner.startScan() }
    }

    @Test
    fun `late registration failure also cleans up after caller cancellation`() = runTest(dispatcher) {
        val preparation = launch { backend().prepare() }
        preparation.cancel()
        preparation.join()

        installTask.setException(IllegalStateException("late failure"))

        assertTrue(preparation.isCancelled)
        verify(exactly = 2) { moduleInstaller.unregisterListener(listener) }
    }

    @Test
    fun `completion before registration response detaches again when registration finishes`() = runTest(dispatcher) {
        val preparation = async { backend().prepare() }

        update(InstallState.STATE_COMPLETED)
        preparation.await()
        verify(exactly = 1) { moduleInstaller.unregisterListener(listener) }

        installTask.setResult(ModuleInstallResponse(42))

        verify(exactly = 2) { moduleInstaller.unregisterListener(listener) }
    }

    @Test
    fun `failure before registration response detaches again after late success`() = runTest(dispatcher) {
        val preparation = async { runCatching { backend().prepare() } }
        update(InstallState.STATE_FAILED)
        assertMessage(R.string.qr_scan_download_failed, preparation.await().exceptionOrNull())

        installTask.setResult(ModuleInstallResponse(42))

        verify(exactly = 2) { moduleInstaller.unregisterListener(listener) }
    }

    @Test
    fun `duplicate terminal updates cannot change successful preparation`() = runTest(dispatcher) {
        val preparation = async { backend().prepare() }
        installTask.setResult(ModuleInstallResponse(42))
        update(InstallState.STATE_COMPLETED)
        update(InstallState.STATE_FAILED)
        update(InstallState.STATE_COMPLETED)

        preparation.await()

        verify(exactly = 1) { moduleInstaller.unregisterListener(listener) }
    }

    @Test
    fun `unregister exception cannot mask original download failure`() = runTest(dispatcher) {
        every { moduleInstaller.unregisterListener(any()) } throws IllegalStateException("cleanup failed")
        val failure = IllegalArgumentException("install failed")
        val preparation = async { runCatching { backend().prepare() } }

        installTask.setException(failure)
        val error = preparation.await().exceptionOrNull()

        assertMessage(R.string.qr_scan_download_failed, error)
        assertOriginalException(failure, error?.cause)
    }

    @Test
    fun `failed unregister task cannot turn completed preparation into failure`() = runTest(dispatcher) {
        every { moduleInstaller.unregisterListener(any()) } returns
            Tasks.forException(IllegalStateException("cleanup failed"))
        val preparation = async { backend().prepare() }
        installTask.setResult(ModuleInstallResponse(42))

        update(InstallState.STATE_COMPLETED)
        preparation.await()

        verify(exactly = 1) { moduleInstaller.unregisterListener(listener) }
    }

    @Test
    fun `scan awaits the actual barcode task`() = runTest(dispatcher) {
        val scan = TaskCompletionSource<Barcode>()
        every { scanner.startScan() } returns scan.task
        val result = async { backend().scan() }
        assertFalse(result.isCompleted)

        scan.setResult(barcode("splitfree invite"))

        assertEquals("splitfree invite", result.await())
        verify(exactly = 1) { scanner.startScan() }
    }

    @Test
    fun `scan returns null when SDK task is user canceled`() = runTest(dispatcher) {
        every { scanner.startScan() } returns Tasks.forCanceled()

        assertNull(backend().scan())
    }

    @Test
    fun `barcode without raw value is distinct from user cancellation`() = runTest(dispatcher) {
        every { scanner.startScan() } returns Tasks.forResult(barcode(null))

        assertEquals("", backend().scan())
    }

    @Test
    fun `scan task failure preserves the SDK exception`() = runTest(dispatcher) {
        val failure = IllegalStateException("scanner unavailable")
        every { scanner.startScan() } returns Tasks.forException(failure)

        assertOriginalException(failure, runCatching { backend().scan() }.exceptionOrNull())
    }

    @Test
    fun `synchronous scan failure propagates`() = runTest(dispatcher) {
        val failure = IllegalStateException("cannot launch scanner")
        every { scanner.startScan() } throws failure

        assertSame(failure, runCatching { backend().scan() }.exceptionOrNull())
    }

    @Test
    fun `caller scan cancellation is not swallowed as SDK dismissal`() = runTest(dispatcher) {
        val scan = TaskCompletionSource<Barcode>()
        every { scanner.startScan() } returns scan.task
        var returned = false
        val operation = launch {
            backend().scan()
            returned = true
        }

        operation.cancel()
        operation.join()
        scan.setResult(barcode("late invite"))

        assertTrue(operation.isCancelled)
        assertFalse(returned)
    }

    private fun backend() = GoogleQrScanner(context, availability, scannerFactory, moduleInstallFactory)

    private fun barcode(value: String?): Barcode = mockk { every { rawValue } returns value }

    private fun update(state: Int) {
        listener.onInstallStatusUpdated(ModuleInstallStatusUpdate(42, state, null, null, 0))
    }

    private fun assertOriginalException(expected: Throwable, actual: Throwable?) {
        var recovered = actual
        val visited = mutableListOf<Throwable>()
        while (recovered !== expected) {
            assertEquals(expected.javaClass, recovered?.javaClass)
            assertEquals(expected.message, recovered?.message)
            val clone = checkNotNull(recovered)
            assertFalse("Exception recovery cause chain contains a cycle", visited.any { it === clone })
            visited += clone
            recovered = clone.cause
        }
        assertSame(expected, recovered)
    }

    private fun assertMessage(expected: Int, error: Throwable?) {
        assertTrue("Expected QrScannerException, got $error", error is QrScannerException)
        assertEquals(expected, (error as QrScannerException).messageRes)
    }
}
