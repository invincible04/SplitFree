package com.splitfree.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WidthClass {
    Compact,
    Medium,
    Expanded
}

enum class HeightClass {
    Compact,
    Medium,
    Expanded
}

@Immutable
data class AdaptiveLayoutInfo(val widthClass: WidthClass, val heightClass: HeightClass, val fontScale: Float) {
    val isCompact: Boolean
        get() = widthClass == WidthClass.Compact || heightClass == HeightClass.Compact
}

/**
 * Screen-level spacing that follows the window class. The kit owns every other dimension (card padding, row
 * heights, icon sizes), so only the two values screens still read are kept here.
 */
@Immutable
data class AdaptiveSizeTokens(
    /** Horizontal inset of every screen's content: 14 / 20 / 24dp by width class. Never hard-code 16. */
    val screenPaddingHorizontal: Dp,
    /** Breathing room under the last element of a scrolling screen. */
    val screenBottomSpacer: Dp
)

val LocalAdaptiveLayoutInfo = compositionLocalOf<AdaptiveLayoutInfo?> { null }
val LocalAdaptiveSizeTokens = compositionLocalOf<AdaptiveSizeTokens?> { null }

@Composable
fun adaptiveLayoutInfo(): AdaptiveLayoutInfo = LocalAdaptiveLayoutInfo.current ?: rememberAdaptiveLayoutInfo()

@Composable
fun adaptiveSizeTokens(): AdaptiveSizeTokens = LocalAdaptiveSizeTokens.current ?: rememberAdaptiveSizeTokens()

@Composable
fun rememberAdaptiveLayoutInfo(): AdaptiveLayoutInfo {
    val density = LocalDensity.current
    val containerSize = LocalWindowInfo.current.containerSize
    val fontScale = density.fontScale.takeIf { it > 0f } ?: 1f

    // The window container size is the real available space (Configuration.screenWidthDp is rounded and
    // its inset handling depends on targetSdk). Treat larger font scales as less effective space and
    // shift to compact sizing sooner.
    val effectiveWidthDp = with(density) { containerSize.width.toDp() }.value / fontScale
    val effectiveHeightDp = with(density) { containerSize.height.toDp() }.value / fontScale

    val widthClass =
        when {
            effectiveWidthDp < 360f -> WidthClass.Compact
            effectiveWidthDp < 600f -> WidthClass.Medium
            else -> WidthClass.Expanded
        }

    val heightClass =
        when {
            effectiveHeightDp < 640f -> HeightClass.Compact
            effectiveHeightDp < 840f -> HeightClass.Medium
            else -> HeightClass.Expanded
        }

    return remember(widthClass, heightClass, fontScale) {
        AdaptiveLayoutInfo(widthClass = widthClass, heightClass = heightClass, fontScale = fontScale)
    }
}

@Composable
fun rememberAdaptiveSizeTokens(adaptive: AdaptiveLayoutInfo = rememberAdaptiveLayoutInfo()): AdaptiveSizeTokens {
    val screenPaddingHorizontal =
        when (adaptive.widthClass) {
            WidthClass.Compact -> 14.dp
            WidthClass.Medium -> 20.dp
            WidthClass.Expanded -> 24.dp
        }
    val screenBottomSpacer =
        when {
            adaptive.isCompact -> 24.dp
            adaptive.widthClass == WidthClass.Expanded -> 40.dp
            else -> 32.dp
        }
    return remember(adaptive.widthClass, adaptive.heightClass, adaptive.fontScale) {
        AdaptiveSizeTokens(screenPaddingHorizontal = screenPaddingHorizontal, screenBottomSpacer = screenBottomSpacer)
    }
}
