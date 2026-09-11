package com.splitfree.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.splitfree.ui.theme.SplitFreeTheme

private const val GALLERY_HEIGHT_DP = 3400

/** Studio previews of the whole kit; the same gallery is rendered to PNG by `UiKitRenderTest`. */
@Preview(name = "Light", widthDp = 390, heightDp = GALLERY_HEIGHT_DP, showBackground = true)
@Composable
private fun UiKitGalleryLightPreview() {
    SplitFreeTheme(darkTheme = false) { UiKitGallery() }
}

@Preview(name = "Dark", widthDp = 390, heightDp = GALLERY_HEIGHT_DP, uiMode = 0x20, showBackground = true)
@Composable
private fun UiKitGalleryDarkPreview() {
    SplitFreeTheme(darkTheme = true) { UiKitGallery() }
}

@Preview(name = "Large text", widthDp = 360, heightDp = GALLERY_HEIGHT_DP + 2200, fontScale = 2f, showBackground = true)
@Composable
private fun UiKitGalleryLargeTextPreview() {
    SplitFreeTheme(darkTheme = false) { UiKitGallery() }
}
