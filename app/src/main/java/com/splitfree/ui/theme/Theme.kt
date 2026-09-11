package com.splitfree.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import com.splitfree.ui.util.LocalAdaptiveLayoutInfo
import com.splitfree.ui.util.LocalAdaptiveSizeTokens
import com.splitfree.ui.util.rememberAdaptiveLayoutInfo
import com.splitfree.ui.util.rememberAdaptiveSizeTokens

/**
 * App theme. The brand palette ([LightColorScheme] / [DarkColorScheme]) is used on every API level; the
 * app deliberately does not adopt Material You dynamic colour, because mixing dynamic and brand roles
 * produces unreadable pairs.
 *
 * Also provides [LocalSplitFreeColors] (see [MaterialTheme.splitFree]) and the adaptive layout locals.
 */
@Composable
fun SplitFreeTheme(
    darkTheme: Boolean =
        when (ThemePreference.mode.collectAsState().value) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        },
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) DarkSplitFreeColors else LightSplitFreeColors
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val adaptiveTokens = rememberAdaptiveSizeTokens(adaptiveInfo)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SplitFreeTypography,
        shapes = SplitFreeShapes
    ) {
        CompositionLocalProvider(
            LocalSplitFreeColors provides extended,
            LocalAdaptiveLayoutInfo provides adaptiveInfo,
            LocalAdaptiveSizeTokens provides adaptiveTokens,
            content = content
        )
    }
}
