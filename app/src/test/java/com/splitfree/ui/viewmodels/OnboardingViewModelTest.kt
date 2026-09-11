package com.splitfree.ui.viewmodels

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.splitfree.R
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.export.ImportGroupUseCase
import com.splitfree.ui.util.UiMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The backup import flow must always report an outcome (never a silent success) and must never
 * read an unbounded file into memory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val identity = mockk<IdentityContract>(relaxed = true)
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
        vm = OnboardingViewModel(identity, settings, importGroup, context, testDispatcher)
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

    private val singleExport = """{"version":2,"groupId":"g1","exportedAt":1,"events":[],"hmac":"aa"}"""

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
    fun `invalid key input surfaces a resource message`() {
        every { identity.importKey("junk") } throws IllegalArgumentException("bad key")

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
}
