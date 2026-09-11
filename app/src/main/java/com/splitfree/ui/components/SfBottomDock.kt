package com.splitfree.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.splitfree.ui.util.adaptiveSizeTokens

/**
 * Bottom-anchored action area: a vertical gradient from transparent into `surface`
 * so scrolling content fades out beneath the primary button. Pads 14dp above, 18dp below plus the bottom
 * safe-drawing inset (navigation bar or IME). Put it in Scaffold's `bottomBar` and keep the list's bottom
 * content padding at least the dock height.
 */
@Composable
fun SfBottomDock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    val horizontal = adaptiveSizeTokens().screenPaddingHorizontal
    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.25f to surface, 1f to surface))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(start = horizontal, end = horizontal, top = 14.dp, bottom = 18.dp),
        content = content
    )
}
