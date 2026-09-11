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

@Immutable
data class AdaptiveSizeTokens(
    val screenPaddingHorizontal: Dp,
    val screenPaddingVertical: Dp,
    val screenBottomSpacer: Dp,
    val sectionSpacing: Dp,
    val itemSpacing: Dp,
    val denseSpacing: Dp,
    val fieldSpacing: Dp,
    val cardPadding: Dp,
    val chipSpacing: Dp,
    val chipContentSpacing: Dp,
    val buttonHeight: Dp,
    val inlineButtonMinHeight: Dp,
    val iconTiny: Dp,
    val iconSmall: Dp,
    val iconMedium: Dp,
    val iconLarge: Dp,
    val avatarSize: Dp,
    val listAvatarSize: Dp,
    val avatarFallbackIconSize: Dp,
    val emptyStatePadding: Dp,
    val emptyStateIcon: Dp,
    val listBottomSpacer: Dp,
    val dropdownWidth: Dp,
    val sectionHeaderVerticalPadding: Dp,
    val seedWordVerticalPadding: Dp,
    val nearbyTopSectionVerticalPadding: Dp,
    val nearbyPeersHeaderVerticalPadding: Dp,
    val nearbyProgressIndicatorSize: Dp,
    val nearbyProgressStrokeWidth: Dp,
    val relayRemoveButtonSize: Dp,
    val relayStatusDotSize: Dp,
    val dialogProgressPadding: Dp,
    val pubkeyChipAvatarSize: Dp
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
    val compact = adaptive.widthClass == WidthClass.Compact
    val expanded = adaptive.widthClass == WidthClass.Expanded

    val screenPaddingHorizontal =
        when (adaptive.widthClass) {
            WidthClass.Compact -> 14.dp
            WidthClass.Medium -> 20.dp
            WidthClass.Expanded -> 24.dp
        }
    val screenPaddingVertical =
        when (adaptive.heightClass) {
            HeightClass.Compact -> 10.dp
            HeightClass.Medium -> 16.dp
            HeightClass.Expanded -> 20.dp
        }
    val screenBottomSpacer =
        when {
            adaptive.isCompact -> 24.dp
            expanded -> 40.dp
            else -> 32.dp
        }
    val sectionSpacing = when {
        adaptive.isCompact -> 12.dp
        expanded -> 20.dp
        else -> 16.dp
    }
    val itemSpacing = when {
        adaptive.isCompact -> 6.dp
        expanded -> 10.dp
        else -> 8.dp
    }
    val denseSpacing = if (compact) 4.dp else 6.dp
    val fieldSpacing = when {
        adaptive.isCompact -> 12.dp
        expanded -> 18.dp
        else -> 16.dp
    }
    val cardPadding = when {
        adaptive.isCompact -> 12.dp
        expanded -> 20.dp
        else -> 16.dp
    }
    val chipSpacing =
        when (adaptive.widthClass) {
            WidthClass.Compact -> 6.dp
            WidthClass.Medium -> 8.dp
            WidthClass.Expanded -> 10.dp
        }
    val chipContentSpacing = if (compact) 4.dp else 6.dp
    val buttonHeight =
        when {
            adaptive.isCompact -> 48.dp
            expanded -> 56.dp
            else -> 52.dp
        }
    val inlineButtonMinHeight = if (compact) 40.dp else 44.dp
    val iconTiny = if (compact) 10.dp else 12.dp
    val iconSmall =
        when (adaptive.widthClass) {
            WidthClass.Compact -> 16.dp
            WidthClass.Medium -> 18.dp
            WidthClass.Expanded -> 20.dp
        }
    val iconMedium =
        when (adaptive.widthClass) {
            WidthClass.Compact -> 20.dp
            WidthClass.Medium -> 24.dp
            WidthClass.Expanded -> 28.dp
        }
    val iconLarge =
        when {
            adaptive.isCompact -> 52.dp
            expanded -> 72.dp
            else -> 60.dp
        }
    val avatarSize =
        when {
            adaptive.isCompact -> 44.dp
            expanded -> 60.dp
            else -> 52.dp
        }
    val listAvatarSize = if (compact) 36.dp else 40.dp
    val avatarFallbackIconSize = when {
        compact -> 24.dp
        expanded -> 32.dp
        else -> 28.dp
    }
    val emptyStatePadding =
        when {
            adaptive.isCompact -> 24.dp
            expanded -> 56.dp
            else -> 40.dp
        }
    val emptyStateIcon =
        when {
            adaptive.isCompact -> 48.dp
            expanded -> 72.dp
            else -> 56.dp
        }
    val listBottomSpacer =
        when {
            adaptive.isCompact -> 72.dp
            expanded -> 96.dp
            else -> 80.dp
        }
    val dropdownWidth =
        when (adaptive.widthClass) {
            WidthClass.Compact -> 94.dp
            WidthClass.Medium -> 110.dp
            WidthClass.Expanded -> 122.dp
        }
    val sectionHeaderVerticalPadding = if (compact) 10.dp else 12.dp
    val seedWordVerticalPadding = if (compact) 4.dp else 6.dp
    val nearbyTopSectionVerticalPadding = if (compact) 12.dp else 16.dp
    val nearbyPeersHeaderVerticalPadding = if (compact) 2.dp else 4.dp
    val nearbyProgressIndicatorSize = if (compact) 28.dp else 32.dp
    val nearbyProgressStrokeWidth = 3.dp
    val relayRemoveButtonSize = if (compact) 24.dp else 28.dp
    val relayStatusDotSize = iconTiny
    val dialogProgressPadding = if (compact) 24.dp else 32.dp
    val pubkeyChipAvatarSize = if (compact) 16.dp else 18.dp

    return remember(adaptive.widthClass, adaptive.heightClass, adaptive.fontScale) {
        AdaptiveSizeTokens(
            screenPaddingHorizontal = screenPaddingHorizontal,
            screenPaddingVertical = screenPaddingVertical,
            screenBottomSpacer = screenBottomSpacer,
            sectionSpacing = sectionSpacing,
            itemSpacing = itemSpacing,
            denseSpacing = denseSpacing,
            fieldSpacing = fieldSpacing,
            cardPadding = cardPadding,
            chipSpacing = chipSpacing,
            chipContentSpacing = chipContentSpacing,
            buttonHeight = buttonHeight,
            inlineButtonMinHeight = inlineButtonMinHeight,
            iconTiny = iconTiny,
            iconSmall = iconSmall,
            iconMedium = iconMedium,
            iconLarge = iconLarge,
            avatarSize = avatarSize,
            listAvatarSize = listAvatarSize,
            avatarFallbackIconSize = avatarFallbackIconSize,
            emptyStatePadding = emptyStatePadding,
            emptyStateIcon = emptyStateIcon,
            listBottomSpacer = listBottomSpacer,
            dropdownWidth = dropdownWidth,
            sectionHeaderVerticalPadding = sectionHeaderVerticalPadding,
            seedWordVerticalPadding = seedWordVerticalPadding,
            nearbyTopSectionVerticalPadding = nearbyTopSectionVerticalPadding,
            nearbyPeersHeaderVerticalPadding = nearbyPeersHeaderVerticalPadding,
            nearbyProgressIndicatorSize = nearbyProgressIndicatorSize,
            nearbyProgressStrokeWidth = nearbyProgressStrokeWidth,
            relayRemoveButtonSize = relayRemoveButtonSize,
            relayStatusDotSize = relayStatusDotSize,
            dialogProgressPadding = dialogProgressPadding,
            pubkeyChipAvatarSize = pubkeyChipAvatarSize
        )
    }
}
