package com.splitfree.ui.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.google.mlkit.common.MlKitException
import com.splitfree.R
import com.splitfree.ui.util.QrScannerBackend
import com.splitfree.ui.util.QrScannerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class QrScanViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var prepare: suspend () -> Unit = {}
    private var scan: suspend () -> String? = { null }
    private var prepares = 0
    private var scans = 0
    private val backend = object : QrScannerBackend {
        override suspend fun prepare() {
            prepares++
            prepare.invoke()
        }
        override suspend fun scan(): String? {
            scans++
            return scan.invoke()
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun model(resumed: Boolean = true) = QrScanViewModel(backend).also {
        store.put("scanner", it)
        it.setResumed(resumed)
    }

    @Test
    fun `repeated taps start only one preparation and camera`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        prepare = { gate.await() }
        scan = { awaitCancellation() }
        val vm = model()
        repeat(5) { vm.start() }
        runCurrent()
        assertEquals(1, prepares)
        assertEquals(QrScanPhase.Preparing, vm.state.value.phase)
        gate.complete(Unit)
        runCurrent()
        repeat(5) { vm.start() }
        assertEquals(1, scans)
        assertEquals(QrScanPhase.Scanning, vm.state.value.phase)
    }

    @Test
    fun `not resumed never launches`() = runTest(dispatcher) {
        model(false).start()
        runCurrent()
        assertEquals(0, prepares)
    }

    @Test
    fun `leaving while downloading cancels without opening later`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        prepare = { gate.await() }
        val vm = model()
        vm.start()
        runCurrent()
        vm.setResumed(false)
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.busy)
        assertEquals(0, scans)
    }

    @Test
    fun `cancel followed immediately by retry cannot be cleared by old finally`() = runTest(dispatcher) {
        prepare = { awaitCancellation() }
        val vm = model()
        vm.start()
        runCurrent()
        vm.cancelPreparation()
        vm.start()
        runCurrent()
        assertEquals(2, prepares)
        assertEquals(QrScanPhase.Preparing, vm.state.value.phase)
    }

    @Test
    fun `camera pause retains result until resumed and consumes only once`() = runTest(dispatcher) {
        val result = CompletableDeferred<String?>()
        scan = { result.await() }
        val vm = model()
        vm.start()
        runCurrent()
        vm.setResumed(false)
        result.complete("splitfree://join?d=test")
        runCurrent()
        assertNull(vm.takeResult())
        assertEquals("splitfree://join?d=test", vm.state.value.result)
        vm.setResumed(true)
        assertEquals("splitfree://join?d=test", vm.takeResult())
        assertNull(vm.takeResult())
    }

    @Test
    fun `back in scanner is quiet and retryable`() = runTest(dispatcher) {
        val vm = model()
        vm.start()
        runCurrent()
        assertEquals(QrScanState(), vm.state.value)
        vm.start()
        runCurrent()
        assertEquals(2, scans)
    }

    @Test
    fun `synchronous SDK throw is visible and does not crash coroutine`() = runTest(dispatcher) {
        scan = { throw IllegalStateException("SDK launch failure") }
        val vm = model()
        vm.start()
        runCurrent()
        assertEquals(R.string.qr_scan_failed, vm.state.value.error)
        assertFalse(vm.state.value.busy)
        scan = { "invite" }
        vm.start()
        runCurrent()
        assertEquals("invite", vm.takeResult())
    }

    @Test
    fun `setup constructor failure shows generic error and can be retried`() = runTest(dispatcher) {
        prepare = { throw NullPointerException("scanner component unavailable") }
        val vm = model()
        vm.start()
        runCurrent()
        assertEquals(R.string.qr_scan_failed, vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertEquals(0, scans)
        prepare = {}
        scan = { "invite" }
        vm.start()
        runCurrent()
        assertEquals("invite", vm.takeResult())
    }

    @Test
    fun `actual module install failure retains module-specific feedback`() = runTest(dispatcher) {
        prepare = { throw QrScannerException(R.string.qr_scan_download_failed) }
        val vm = model()
        vm.start()
        runCurrent()
        assertEquals(R.string.qr_scan_download_failed, vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertEquals(0, scans)
    }

    @Test
    fun `preparation timeout is generic and remains retryable`() = runTest(dispatcher) {
        prepare = { awaitCancellation() }
        val vm = model()
        vm.start()
        runCurrent()
        advanceTimeBy(QrScanViewModel.PREPARE_TIMEOUT_MS)
        runCurrent()
        assertEquals(R.string.qr_scan_failed, vm.state.value.error)
        assertEquals(0, scans)
        vm.start()
        runCurrent()
        assertTrue(vm.state.value.busy)
    }

    @Test
    fun `camera permission error has actionable message`() = runTest(dispatcher) {
        scan = { throw MlKitException("Denied", MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED) }
        val vm = model()
        vm.start()
        runCurrent()
        assertEquals(R.string.qr_scan_camera_denied, vm.state.value.error)
    }

    @Test
    fun `module error and outdated services are actionable`() = runTest(dispatcher) {
        val vm = model()
        prepare = { throw QrScannerException(R.string.qr_scan_services_unavailable) }
        vm.start()
        runCurrent()
        assertEquals(R.string.qr_scan_services_unavailable, vm.state.value.error)
        prepare = {}
        scan = { throw MlKitException("Missing", MlKitException.CODE_SCANNER_UNAVAILABLE) }
        vm.start()
        runCurrent()
        assertEquals(R.string.qr_scan_download_failed, vm.state.value.error)
    }

    @Test
    fun `SDK cancellation exception does not become user failure`() = runTest(dispatcher) {
        scan = { throw MlKitException("Closed", MlKitException.CODE_SCANNER_CANCELLED) }
        val vm = model()
        vm.start()
        runCurrent()
        assertEquals(QrScanState(), vm.state.value)
        scan = { throw CancellationException("Scope cancelled") }
        vm.start()
        runCurrent()
        assertEquals(QrScanState(), vm.state.value)
    }

    @Test
    fun `empty QR gives feedback without passing unreadable result`() = runTest(dispatcher) {
        scan = { " " }
        val vm = model()
        vm.start()
        runCurrent()
        assertEquals(R.string.qr_scan_empty, vm.state.value.error)
        assertNull(vm.takeResult())
    }

    @Test
    fun `cleared owner cancels active preparation`() = runTest(dispatcher) {
        var cancelled = false
        prepare = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val vm = model()
        vm.start()
        runCurrent()
        store.clear()
        runCurrent()
        assertTrue(cancelled)
        assertFalse(vm.state.value.busy)
        assertEquals(0, scans)
    }
}
