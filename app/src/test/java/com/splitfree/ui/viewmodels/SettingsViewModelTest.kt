package com.splitfree.ui.viewmodels

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.DisplayNamePublishResult
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.export.ExportGroupUseCase
import com.splitfree.domain.usecase.group.DisplayNamePublisher
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Export outcomes and best-effort document cleanup, plus shared name/identity-operation state.
 * Collaborators are mocked; cleanup failure must not mask the original export error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val identity = mockk<IdentityContract>(relaxed = true)
    private val giftWrap = mockk<GiftWrapService>(relaxed = true)
    private val revokeKeyUseCase = mockk<RevokeKeyUseCase>(relaxed = true)
    private val namePublisher = mockk<DisplayNamePublisher>(relaxed = true)
    private val desiredName = MutableStateFlow("")
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
        coEvery { groupRepo.getAll() } returns groups

        every { namePublisher.displayName } returns desiredName
        every { namePublisher.result } returns MutableStateFlow(DisplayNamePublishResult())
        every { namePublisher.submit(any()) } answers { desiredName.value = firstArg() }
        vm = createViewModel()
    }

    private fun createViewModel() = SettingsViewModel(
        identity, giftWrap, revokeKeyUseCase, namePublisher,
        groupRepo, exportGroup, context, testDispatcher, outboxDao
    )

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

    @Test
    fun `every edit immediately reaches the application publisher before a debounce can lose it`() = runTest {
        vm.setDisplayName("Alice")
        verify(exactly = 1) { namePublisher.submit("Alice") }
        assertEquals("Alice", vm.displayName.value)
    }

    @Test
    fun `failed durable name save reports failure without crashing or changing displayed value`() = runTest {
        every { namePublisher.submit(any()) } throws IllegalStateException("storage unavailable")
        vm.setDisplayName("Alice")
        assertTrue(vm.nameSaveFailed.value)
        assertEquals("", vm.displayName.value)
        vm.clearNameSaveFailure()
        assertEquals(false, vm.nameSaveFailed.value)
    }

    @Test
    fun `successful retry clears an earlier name save failure`() = runTest {
        every { namePublisher.submit(any()) } throws IllegalStateException("disk full")
        vm.setDisplayName("Alice")
        every { namePublisher.submit(any()) } answers { desiredName.value = firstArg() }
        vm.setDisplayName("Alice")
        assertEquals(false, vm.nameSaveFailed.value)
        assertEquals("Alice", vm.displayName.value)
    }

    @Test
    fun `multiple settings screens share one desired name and publication owner`() = runTest {
        val reopened = createViewModel()
        vm.setDisplayName("Bob")
        reopened.setDisplayName("Carol")
        assertEquals("Carol", vm.displayName.value)
        assertEquals("Carol", reopened.displayName.value)
        verify(exactly = 1) { namePublisher.submit("Bob") }
        verify(exactly = 1) { namePublisher.submit("Carol") }
    }

    @Test
    fun `closing an old settings screen never resubmits its previous name`() = runTest {
        val reopened = createViewModel()
        vm.setDisplayName("Bob")
        reopened.setDisplayName("Carol")
        androidx.lifecycle.ViewModelStore().apply { put("settings", vm) }.clear()
        verify(exactly = 2) { namePublisher.submit(any()) }
        assertEquals("Carol", reopened.displayName.value)
    }

    @Test
    fun `opening settings requests recovery instead of assuming persisted name was published`() = runTest {
        verify(exactly = 1) { namePublisher.start() }
    }

    @Test
    fun `a second replace-identity tap while one is running is ignored`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        coEvery { revokeKeyUseCase() } coAnswers { gate.await() }

        vm.revokeKey()
        vm.revokeKey()
        gate.complete("newpub")

        coVerify(exactly = 1) { revokeKeyUseCase() }
        assertEquals(RevokeState.Done("newpub"), vm.revokeState.value)
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
