package com.splitfree.ui.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.splitfree.R
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.viewmodels.ImportStatus

@Composable
private fun OnboardingPreviewHost(step: OnboardingStep, state: OnboardingUiState = OnboardingUiState()) {
    SplitFreeTheme {
        OnboardingContent(
            state = state,
            step = step,
            onStep = {},
            sheet = null,
            onSheet = {},
            actions = OnboardingActions()
        )
    }
}

@Preview(name = "Light", widthDp = 390, heightDp = 844, showBackground = true)
@Preview(name = "Dark", widthDp = 390, heightDp = 844, uiMode = 0x20, showBackground = true)
@Preview(name = "Large text", widthDp = 360, heightDp = 800, fontScale = 2f, showBackground = true)
@Composable
private fun OnboardingWelcomePreview() {
    OnboardingPreviewHost(OnboardingStep.Welcome)
}

@Preview(name = "Restore key + error", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun OnboardingRestoreKeyPreview() {
    OnboardingPreviewHost(
        OnboardingStep.RestoreKey,
        OnboardingUiState(error = UiMessage.Res(R.string.invalid_key_input))
    )
}

@Preview(name = "Restore backup", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun OnboardingRestoreBackupPreview() {
    OnboardingPreviewHost(
        OnboardingStep.RestoreBackup,
        OnboardingUiState(keyImported = true, importStatus = ImportStatus.Restored(42))
    )
}
