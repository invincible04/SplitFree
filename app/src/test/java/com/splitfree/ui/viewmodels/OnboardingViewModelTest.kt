package com.splitfree.ui.viewmodels

import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.export.ImportGroupUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The backup import flow must always report an outcome — never a silent success. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val identity = mockk<IdentityContract>(relaxed = true)
    private val settings = mockk<SettingsContract>(relaxed = true)
    private val importGroup = mockk<ImportGroupUseCase>(relaxed = true)

    private val vm = OnboardingViewModel(identity, settings, importGroup)

    @Test
    fun `unreadable backup file is reported as a failed import`() {
        vm.reportUnreadableBackup()

        assertEquals("Import failed: could not read the backup file", vm.importStatus.value)
    }

    @Test
    fun `rejected backup content is reported as a failed import`() = runTest {
        coEvery { importGroup(any()) } throws IllegalArgumentException("integrity check failed")

        vm.importBackup("{\"version\":1}")

        assertEquals("Import failed: integrity check failed", vm.importStatus.value)
    }
}
