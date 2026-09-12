package com.splitfree.ui.viewmodels

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.export.ExportGroupUseCase
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.UpdateDisplayNameUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The backup export must never leave a half-written file that looks like a backup, and must
 * always report an outcome to the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val identity = mockk<IdentityContract>(relaxed = true)
    private val giftWrap = mockk<GiftWrapService>(relaxed = true)
    private val userPreferences = mockk<SettingsContract>(relaxed = true)
    private val revokeKeyUseCase = mockk<RevokeKeyUseCase>(relaxed = true)
    private val updateDisplayName = mockk<UpdateDisplayNameUseCase>(relaxed = true)
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val exportGroup = mockk<ExportGroupUseCase>()
    private val outboxDao = mockk<OutboxDao> {
        every { pendingOutboxCount() } returns flowOf(0)
        every { stuckOutboxCount() } returns flowOf(0)
    }
    private val contentResolver = mockk<ContentResolver>()
    private val context =
        mockk<Context> { every { contentResolver } returns this@SettingsViewModelTest.contentResolver }
    private val uri = mockk<Uri>()

    private lateinit var vm: SettingsViewModel

    /** Backing store for the mocked preferences so `displayName` round-trips like the real one. */
    private var storedDisplayName = ""

    private val groups = listOf(
        Group(id = "g1", name = "A", createdBy = "p", createdAt = 1, members = listOf("p"), relays = emptyList()),
        Group(id = "g2", name = "B", createdBy = "p", createdAt = 1, members = listOf("p"), relays = emptyList())
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.isDocumentUri(context, uri) } returns true
        every { DocumentsContract.deleteDocument(contentResolver, uri) } returns true
        every { identity.hasIdentity() } returns false
        every { identity.hasPendingKeyPair() } returns false
        every { userPreferences.displayName } answers { storedDisplayName }
        every { userPreferences.displayName = any() } answers { storedDisplayName = firstArg() }
        coEvery { groupRepo.getAll() } returns groups

        vm = SettingsViewModel(
            identity, giftWrap, userPreferences, revokeKeyUseCase, updateDisplayName,
            groupRepo, exportGroup, context, testDispatcher, outboxDao
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkStatic(DocumentsContract::class)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `export writes every group as a JSON array and reports success`() = runTest {
        val sink = ByteArrayOutputStream()
        every { contentResolver.openOutputStream(uri, "wt") } returns sink
        coEvery { exportGroup("g1") } returns """{"groupId":"g1"}"""
        coEvery { exportGroup("g2") } returns """{"groupId":"g2"}"""

        vm.exportAllGroups(uri)

        assertEquals(ExportState.Done, vm.exportState.value)
        assertEquals("""[{"groupId":"g1"},{"groupId":"g2"}]""", sink.toString(Charsets.UTF_8.name()))
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }

    @Test
    fun `failure mid-write deletes the partial document and reports an error`() = runTest {
        val sink = ByteArrayOutputStream()
        every { contentResolver.openOutputStream(uri, "wt") } returns sink
        coEvery { exportGroup("g1") } returns """{"groupId":"g1"}"""
        coEvery { exportGroup("g2") } throws IllegalStateException("keystore unavailable")

        vm.exportAllGroups(uri)

        val state = vm.exportState.value
        assertTrue(state is ExportState.Error)
        assertEquals("keystore unavailable", (state as ExportState.Error).message)
        verify(exactly = 1) { DocumentsContract.deleteDocument(contentResolver, uri) }
    }

    @Test
    fun `unopenable target is reported as an error`() = runTest {
        every { contentResolver.openOutputStream(uri, "wt") } throws FileNotFoundException("gone")

        vm.exportAllGroups(uri)

        assertTrue(vm.exportState.value is ExportState.Error)
    }

    @Test
    fun `null output stream is an error rather than a silent success`() = runTest {
        every { contentResolver.openOutputStream(uri, "wt") } returns null

        vm.exportAllGroups(uri)

        assertTrue(vm.exportState.value is ExportState.Error)
    }

    @Test
    fun `non-document uri is left alone on failure`() = runTest {
        every { DocumentsContract.isDocumentUri(context, uri) } returns false
        every { contentResolver.openOutputStream(uri, "wt") } returns object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
        }

        vm.exportAllGroups(uri)

        assertTrue(vm.exportState.value is ExportState.Error)
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }

    @Test
    fun `delete failure during cleanup does not mask the export error`() = runTest {
        every { contentResolver.openOutputStream(uri, "wt") } returns ByteArrayOutputStream()
        coEvery { exportGroup("g1") } throws IllegalStateException("boom")
        every { DocumentsContract.deleteDocument(contentResolver, uri) } throws UnsupportedOperationException("ro")

        vm.exportAllGroups(uri)

        assertEquals(ExportState.Error("boom"), vm.exportState.value)
    }

    // --- display name debounce ---

    @Test
    fun `first display name edit within the debounce window is published`() = runTest {
        // The ViewModel was created moments ago; this edit lands well inside the 800 ms window.
        vm.setDisplayName("Alice")
        coVerify(exactly = 0) { updateDisplayName(any()) }

        advanceTimeBy(801)

        coVerify(exactly = 1) { updateDisplayName("Alice") }
        assertEquals("Alice", vm.displayName.value)
    }

    @Test
    fun `keystrokes inside the window collapse into one publish of the final name`() = runTest {
        vm.setDisplayName("A")
        advanceTimeBy(300)
        vm.setDisplayName("Al")
        advanceTimeBy(300)
        vm.setDisplayName("Alice")
        advanceTimeBy(801)

        coVerify(exactly = 1) { updateDisplayName(any()) }
        coVerify(exactly = 1) { updateDisplayName("Alice") }
    }

    @Test
    fun `re-entering the already published name is not republished`() = runTest {
        vm.setDisplayName("Alice")
        advanceTimeBy(801)
        vm.setDisplayName("Alic")
        advanceTimeBy(100)
        vm.setDisplayName("Alice")
        advanceTimeBy(801)

        coVerify(exactly = 1) { updateDisplayName(any()) }
    }

    @Test
    fun `the initially persisted name is never republished on its own`() = runTest {
        advanceTimeBy(5_000)

        coVerify(exactly = 0) { updateDisplayName(any()) }
    }

    @Test
    fun `clearExportState returns to idle`() = runTest {
        every { contentResolver.openOutputStream(uri, "wt") } returns ByteArrayOutputStream()
        coEvery { exportGroup(any()) } returns "{}"
        vm.exportAllGroups(uri)
        assertEquals(ExportState.Done, vm.exportState.value)

        vm.clearExportState()

        assertEquals(ExportState.Idle, vm.exportState.value)
    }

    @Test
    fun `clearRevokeState drops a finished outcome but never an in-flight rotation`() = runTest {
        coEvery { revokeKeyUseCase() } throws IllegalStateException("relay refused")
        vm.revokeKey()
        assertEquals(RevokeState.Error("relay refused"), vm.revokeState.value)

        vm.clearRevokeState()
        assertEquals(RevokeState.Idle, vm.revokeState.value)

        coEvery { revokeKeyUseCase() } returns "newpub"
        vm.revokeKey()
        assertEquals(RevokeState.Done("newpub"), vm.revokeState.value)
        assertEquals("newpub", vm.npub.value)
        vm.clearRevokeState()
        assertEquals(RevokeState.Idle, vm.revokeState.value)

        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        coEvery { revokeKeyUseCase() } coAnswers { gate.await() }
        vm.revokeKey()
        assertEquals(RevokeState.InProgress, vm.revokeState.value)
        vm.clearRevokeState()
        assertEquals("An in-flight rotation must stay visible", RevokeState.InProgress, vm.revokeState.value)
        gate.complete("later")
        assertEquals(RevokeState.Done("later"), vm.revokeState.value)
    }
}
