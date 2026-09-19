package com.splitfree.ui.viewmodels

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.splitfree.R
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.repository.DisplayNameIntent
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.IdentityState
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.export.ImportGroupUseCase
import com.splitfree.domain.usecase.group.IdentitySwitchCoordinator
import com.splitfree.ui.util.UiMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ViewModel backup bounds/outcomes and identity-state handling with mocked collaborators.
 * Does not exercise backup authentication, Keystore recovery or screen navigation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val identity = mockk<IdentityContract>(relaxed = true)
    private val identitySwitch = mockk<IdentitySwitchCoordinator>(relaxed = true)
    private val settings = mockk<SettingsContract>(relaxed = true)
    private val importGroup = mockk<ImportGroupUseCase>()
    private val contentResolver = mockk<ContentResolver>()
    private val context = mockk<Context> {
        every { contentResolver } returns
            this@OnboardingViewModelTest.contentResolver
    }
    private val uri = mockk<Uri>()

    private lateinit var vm: OnboardingViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { identity.identityState() } returns IdentityState.ABSENT
        every { identity.getPublicKeyHex() } returns "ab".repeat(32)
        coEvery { identitySwitch.generateKeyPair() } returns "ab".repeat(32)
        coEvery { identitySwitch.withIdentity<DisplayNameIntent>(any(), any()) } coAnswers {
            secondArg<() -> DisplayNameIntent>().invoke()
        }
        vm = OnboardingViewModel(identity, settings, importGroup, context, testDispatcher, identitySwitch)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
    }

    private fun fileWith(bytes: ByteArray) {
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(bytes)
    }

    private fun fileWith(content: String) = fileWith(content.toByteArray())

    private val singleExport = """{"version":3,"groupId":"g1","exportedAt":1,"events":[],"hmac":"aa"}"""

    @Test
    fun `valid single-group backup is imported and the count is surfaced`() = runTest {
        fileWith(singleExport)
        coEvery { importGroup(any<SplitFreeExport>()) } returns 7

        vm.importBackup(uri)

        assertEquals(ImportStatus.Restored(7), vm.importStatus.value)
        assertFalse(vm.importing.value)
        coVerify(exactly = 1) { importGroup(match<SplitFreeExport> { it.groupId == "g1" && it.hmac == "aa" }) }
    }

    @Test
    fun `multi-group backup array imports every group and sums the counts`() = runTest {
        fileWith("  [$singleExport, ${singleExport.replace("g1", "g2")}]")
        coEvery { importGroup(match<SplitFreeExport> { it.groupId == "g1" }) } returns 2
        coEvery { importGroup(match<SplitFreeExport> { it.groupId == "g2" }) } returns 3

        vm.importBackup(uri)

        assertEquals(ImportStatus.Restored(5), vm.importStatus.value)
        coVerify(exactly = 2) { importGroup(any<SplitFreeExport>()) }
    }

    @Test
    fun `oversize backup is rejected before it is parsed or imported`() = runTest {
        fileWith(ByteArray(OnboardingViewModel.MAX_IMPORT_BYTES + 1) { 'a'.code.toByte() })

        vm.importBackup(uri)

        assertEquals(ImportStatus.Failed("Backup file is too large"), vm.importStatus.value)
        assertFalse(vm.importing.value)
        coVerify(exactly = 0) { importGroup(any<SplitFreeExport>()) }
    }

    @Test
    fun `oversize stream is abandoned as soon as the cap is crossed`() = runTest {
        // An endless stream: the read must stop on its own instead of draining it into memory.
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                served++
                return 'a'.code
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill('a'.code.toByte(), off, off + len)
                served += len
                return len
            }
        }
        every { contentResolver.openInputStream(uri) } returns endless

        vm.importBackup(uri)

        assertEquals(ImportStatus.Failed("Backup file is too large"), vm.importStatus.value)
        // Stops within one read buffer of the cap.
        assertTrue("read $served bytes past the cap", served <= OnboardingViewModel.MAX_IMPORT_BYTES + 64 * 1024L)
    }

    @Test
    fun `backup exactly at the cap is still accepted`() = runTest {
        val padding = " ".repeat(OnboardingViewModel.MAX_IMPORT_BYTES - singleExport.length)
        fileWith(singleExport + padding)
        coEvery { importGroup(any<SplitFreeExport>()) } returns 1

        vm.importBackup(uri)

        assertEquals(ImportStatus.Restored(1), vm.importStatus.value)
    }

    @Test
    fun `unreadable backup file is reported as a failed import`() = runTest {
        every { contentResolver.openInputStream(uri) } throws FileNotFoundException("gone")

        vm.importBackup(uri)

        assertEquals(ImportStatus.Failed("could not read the backup file"), vm.importStatus.value)
        assertFalse(vm.importing.value)
    }

    @Test
    fun `null stream is reported as a failed import`() = runTest {
        every { contentResolver.openInputStream(uri) } returns null

        vm.importBackup(uri)

        assertEquals(ImportStatus.Failed("could not read the backup file"), vm.importStatus.value)
    }

    @Test
    fun `rejected backup content is reported as a failed import`() = runTest {
        fileWith(singleExport)
        coEvery { importGroup(any<SplitFreeExport>()) } throws IllegalArgumentException("integrity check failed")

        vm.importBackup(uri)

        assertEquals(ImportStatus.Failed("integrity check failed"), vm.importStatus.value)
        assertFalse(vm.importing.value)
    }

    @Test
    fun `invalid key input surfaces a resource message`() = runTest {
        coEvery { identitySwitch.importKey("junk") } throws IllegalArgumentException("bad key")

        assertFalse(vm.importKey("junk"))

        assertEquals(UiMessage.Res(R.string.invalid_key_input), vm.error.value)
        vm.clearError()
        assertEquals(null, vm.error.value)
    }

    @Test
    fun `malformed JSON is reported as a failed import`() = runTest {
        fileWith("{not json")

        vm.importBackup(uri)

        assertTrue(vm.importStatus.value is ImportStatus.Failed)
        coVerify(exactly = 0) { importGroup(any<SplitFreeExport>()) }
    }

    // --- identity store state: a failing store is never reported as a wrong phrase ---

    @Test
    fun `a storage failure during key import is reported as a storage problem, not an invalid key`() = runTest {
        coEvery { identitySwitch.importKey("valid words") } throws SecureStorageException("keystore down")
        every { identity.identityState() } returns IdentityState.UNAVAILABLE

        assertFalse(vm.importKey("valid words"))

        assertEquals(UiMessage.Res(R.string.identity_storage_unavailable), vm.error.value)
        assertFalse(vm.keyImported.value)
        assertEquals(IdentityState.UNAVAILABLE, vm.identityState.value)
    }

    @Test
    fun `a successful import after key loss marks the identity ready`() = runTest {
        every { identity.identityState() } returns IdentityState.RECOVERY_REQUIRED
        vm = OnboardingViewModel(identity, settings, importGroup, context, testDispatcher, identitySwitch)
        assertEquals(IdentityState.RECOVERY_REQUIRED, vm.identityState.value)

        assertTrue(vm.importKey("valid words"))

        coVerify(exactly = 1) { identitySwitch.importKey("valid words") }
        assertEquals(IdentityState.READY, vm.identityState.value)
        assertTrue(vm.keyImported.value)
        assertEquals(null, vm.error.value)
    }

    @Test
    fun `while the store is unavailable neither import nor creation touches the identity`() = runTest {
        every { identity.identityState() } returns IdentityState.UNAVAILABLE
        vm = OnboardingViewModel(identity, settings, importGroup, context, testDispatcher, identitySwitch)

        assertFalse(vm.importKey("valid words"))
        assertFalse(vm.generateIdentity("Ann"))

        coVerify(exactly = 0) { identitySwitch.importKey(any()) }
        coVerify(exactly = 0) { identitySwitch.generateKeyPair() }
        assertEquals(UiMessage.Res(R.string.identity_storage_unavailable), vm.error.value)
    }

    @Test
    fun `retrying the store re-probes it and clears the error`() = runTest {
        every { identity.identityState() } returns IdentityState.UNAVAILABLE
        vm = OnboardingViewModel(identity, settings, importGroup, context, testDispatcher, identitySwitch)
        vm.generateIdentity()
        assertEquals(UiMessage.Res(R.string.identity_storage_unavailable), vm.error.value)

        every { identity.identityState() } returns IdentityState.ABSENT
        assertFalse(vm.retryIdentityStore())

        assertEquals(IdentityState.ABSENT, vm.identityState.value)
        assertEquals(null, vm.error.value)

        // READY reports that onboarding may finish; navigation is not exercised.
        every { identity.identityState() } returns IdentityState.READY
        assertTrue(vm.retryIdentityStore())
    }

    @Test
    fun `a failed identity creation keeps the user on onboarding with a storage message`() = runTest {
        every { identity.hasIdentity() } returns false
        coEvery { identitySwitch.generateKeyPair() } throws SecureStorageException("keystore down")
        every { identity.identityState() } returns IdentityState.ABSENT

        assertFalse(vm.generateIdentity("Ann"))

        assertEquals(UiMessage.Res(R.string.identity_storage_unavailable), vm.error.value)
        verify(exactly = 0) { settings.saveDisplayNameIntent(any(), any()) }
    }

    @Test
    fun `a successful identity creation records the name and reports ready`() = runTest {
        every { identity.hasIdentity() } returns false
        coEvery { identitySwitch.generateKeyPair() } returns "ab".repeat(32)

        assertTrue(vm.generateIdentity("Ann"))

        verify { settings.saveDisplayNameIntent("ab".repeat(32), "Ann") }
        assertEquals(IdentityState.READY, vm.identityState.value)
        assertFalse(vm.identityBusy.value)
    }

    @Test
    fun `a store that stops answering after the screen opened is re-probed, not trusted from the cache`() = runTest {
        every { identity.identityState() } returns IdentityState.READY
        vm = OnboardingViewModel(identity, settings, importGroup, context, testDispatcher, identitySwitch)

        // UNAVAILABLE must not be mistaken for absence even when hasIdentity returns false.
        every { identity.identityState() } returns IdentityState.UNAVAILABLE
        every { identity.hasIdentity() } returns false

        assertFalse(vm.generateIdentity("Ann"))
        assertFalse(vm.importKey("valid words"))

        coVerify(exactly = 0) { identitySwitch.generateKeyPair() }
        coVerify(exactly = 0) { identitySwitch.importKey(any()) }
        assertEquals(IdentityState.UNAVAILABLE, vm.identityState.value)
        assertEquals(UiMessage.Res(R.string.identity_storage_unavailable), vm.error.value)
    }

    @Test
    fun `creation is decided by the classified state and never generates over a readable identity`() = runTest {
        every { identity.identityState() } returns IdentityState.READY
        // hasIdentity() disagrees, as it does when a single decrypt attempt fails.
        every { identity.hasIdentity() } returns false

        assertTrue(vm.generateIdentity("Ann"))

        coVerify(exactly = 0) { identitySwitch.generateKeyPair() }
        verify { settings.saveDisplayNameIntent("ab".repeat(32), "Ann") }
        assertEquals(IdentityState.READY, vm.identityState.value)
    }

    @Test
    fun `creation over a store that lost its key is the user's explicit choice and still generates`() = runTest {
        every { identity.identityState() } returns IdentityState.RECOVERY_REQUIRED
        coEvery { identitySwitch.generateKeyPair() } returns "ab".repeat(32)

        assertTrue(vm.generateIdentity())

        coVerify(exactly = 1) { identitySwitch.generateKeyPair() }
        assertEquals(IdentityState.READY, vm.identityState.value)
    }

    @Test
    fun `journal recovery failure is not presented as an invalid recovery phrase`() = runTest {
        coEvery { identitySwitch.importKey("valid words") } throws IllegalStateException("journal unavailable")
        assertFalse(vm.importKey("valid words"))
        assertEquals(UiMessage.Res(R.string.identity_storage_unavailable), vm.error.value)
        assertFalse(vm.keyImported.value)
        assertFalse(vm.identityBusy.value)
    }

    @Test
    fun `creation retains completion and rejects duplicates before and after acknowledgement`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { identitySwitch.generateKeyPair() } coAnswers {
            release.await()
            "ab".repeat(32)
        }
        vm.createIdentity("Ann")
        vm.createIdentity("Ann")
        vm.retryIdentity()
        assertTrue(vm.identityBusy.value)
        assertEquals(OnboardingCompletion.Running, vm.completion.value)
        vm.acknowledgeCompletion()
        assertEquals(OnboardingCompletion.Running, vm.completion.value)
        coVerify(exactly = 1) { identitySwitch.generateKeyPair() }

        release.complete(Unit)

        assertEquals(OnboardingCompletion.Pending, vm.completion.value)
        assertFalse(vm.identityBusy.value)
        vm.createIdentity("Duplicate")
        vm.retryIdentity()
        assertEquals(OnboardingCompletion.Pending, vm.completion.value)
        vm.acknowledgeCompletion()
        assertEquals(OnboardingCompletion.Acknowledged, vm.completion.value)
        vm.acknowledgeCompletion()
        vm.createIdentity("Duplicate")
        vm.retryIdentity()
        assertEquals(OnboardingCompletion.Acknowledged, vm.completion.value)
        coVerify(exactly = 1) { identitySwitch.generateKeyPair() }
        coVerify(exactly = 0) { identitySwitch.resumeIfNeeded() }
        verify(exactly = 1) { settings.saveDisplayNameIntent("ab".repeat(32), "Ann") }
    }

    @Test
    fun `duplicate requests are rejected before the first coroutine runs`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        vm = OnboardingViewModel(identity, settings, importGroup, context, testDispatcher, identitySwitch)

        vm.createIdentity("Ann")
        vm.createIdentity("Duplicate")
        vm.retryIdentity()
        assertEquals(OnboardingCompletion.Running, vm.completion.value)
        coVerify(exactly = 0) { identitySwitch.generateKeyPair() }
        runCurrent()

        assertEquals(OnboardingCompletion.Pending, vm.completion.value)
        coVerify(exactly = 1) { identitySwitch.generateKeyPair() }
        verify(exactly = 1) { settings.saveDisplayNameIntent("ab".repeat(32), "Ann") }
    }

    @Test
    fun `failed creation does not complete and a later successful attempt can complete`() = runTest {
        coEvery { identitySwitch.generateKeyPair() } throws SecureStorageException("keystore down")
        vm.createIdentity("Ann")
        assertEquals(OnboardingCompletion.Idle, vm.completion.value)
        assertEquals(UiMessage.Res(R.string.identity_storage_unavailable), vm.error.value)
        assertFalse(vm.identityBusy.value)

        coEvery { identitySwitch.generateKeyPair() } returns "ab".repeat(32)
        vm.createIdentity("Ann")

        assertEquals(OnboardingCompletion.Pending, vm.completion.value)
        assertEquals(null, vm.error.value)
        coVerify(exactly = 2) { identitySwitch.generateKeyPair() }
    }

    @Test
    fun `name save failure leaves completion idle even after key generation succeeded`() = runTest {
        coEvery { identitySwitch.withIdentity<DisplayNameIntent>(any(), any()) } throws
            IllegalStateException("identity changed")

        vm.createIdentity("Ann")

        assertEquals(OnboardingCompletion.Idle, vm.completion.value)
        assertEquals(UiMessage.Res(R.string.identity_storage_unavailable), vm.error.value)
        verify(exactly = 0) { settings.saveDisplayNameIntent(any(), any()) }
    }

    @Test
    fun `retry waits for retained recovery and duplicate retry or create cannot bypass it`() = runTest {
        every { identity.identityState() } returns IdentityState.READY
        val release = CompletableDeferred<Unit>()
        coEvery { identitySwitch.resumeIfNeeded() } coAnswers { release.await() }

        vm.retryIdentity()
        vm.retryIdentity()
        vm.createIdentity("Ann")
        assertEquals(OnboardingCompletion.Running, vm.completion.value)
        assertTrue(vm.identityBusy.value)
        assertFalse(vm.importKey("valid words"))
        coVerify(exactly = 1) { identitySwitch.resumeIfNeeded() }
        coVerify(exactly = 0) { identitySwitch.importKey(any()) }

        release.complete(Unit)

        assertEquals(OnboardingCompletion.Pending, vm.completion.value)
        assertFalse(vm.identityBusy.value)
        vm.acknowledgeCompletion()
        vm.retryIdentity()
        assertEquals(OnboardingCompletion.Acknowledged, vm.completion.value)
        coVerify(exactly = 1) { identitySwitch.resumeIfNeeded() }
        coVerify(exactly = 0) { identitySwitch.generateKeyPair() }
    }

    @Test
    fun `unready or failed retry never completes and recovery can be retried`() = runTest {
        for (state in listOf(IdentityState.ABSENT, IdentityState.RECOVERY_REQUIRED, IdentityState.UNAVAILABLE)) {
            every { identity.identityState() } returns state
            vm.retryIdentity()
            assertEquals(OnboardingCompletion.Idle, vm.completion.value)
            assertEquals(state, vm.identityState.value)
            assertFalse(vm.identityBusy.value)
        }
        coVerify(exactly = 0) { identitySwitch.resumeIfNeeded() }
        every { identity.identityState() } returns IdentityState.READY
        coEvery { identitySwitch.resumeIfNeeded() } throws SecureStorageException("journal unavailable")
        vm.retryIdentity()
        assertEquals(OnboardingCompletion.Idle, vm.completion.value)
        assertEquals(UiMessage.Res(R.string.identity_storage_unavailable), vm.error.value)

        coEvery { identitySwitch.resumeIfNeeded() } returns Unit
        vm.retryIdentity()

        assertEquals(OnboardingCompletion.Pending, vm.completion.value)
        assertEquals(null, vm.error.value)
        assertFalse(vm.identityBusy.value)
    }

    @Test
    fun `cancelled generation releases admission without a completion or storage error`() = runTest {
        coEvery { identitySwitch.generateKeyPair() } throws CancellationException("cancelled")

        vm.createIdentity("Ann")

        assertEquals(OnboardingCompletion.Idle, vm.completion.value)
        assertEquals(null, vm.error.value)
        assertFalse(vm.identityBusy.value)
        coEvery { identitySwitch.generateKeyPair() } returns "ab".repeat(32)
        vm.createIdentity("Ann")
        assertEquals(OnboardingCompletion.Pending, vm.completion.value)
    }

    @Test
    fun `key restore retains the backup step without requesting onboarding completion`() = runTest {
        vm.restoreKey("valid words")

        assertTrue(vm.keyImported.value)
        assertEquals(OnboardingCompletion.Idle, vm.completion.value)
        assertFalse(vm.identityBusy.value)
        coVerify(exactly = 1) { identitySwitch.importKey("valid words") }
    }

    @Test
    fun `identity changing before name save cannot attach onboarding name to another identity`() = runTest {
        coEvery { identitySwitch.withIdentity<DisplayNameIntent>(any(), any()) } throws
            IllegalStateException("identity changed")
        assertFalse(vm.generateIdentity("Ann"))
        verify(exactly = 0) { settings.saveDisplayNameIntent(any(), any()) }
        assertEquals(UiMessage.Res(R.string.identity_storage_unavailable), vm.error.value)
    }
}
