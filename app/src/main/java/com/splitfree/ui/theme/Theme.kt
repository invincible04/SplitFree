package com.splitfree.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// SplitFree brand colors — green/teal for money
private val Green40 = Color(0xFF1B8C5A)
private val Green80 = Color(0xFF6EDAA0)
private val Green90 = Color(0xFFB8F0D0)
private val GreenDark = Color(0xFF005234)
private val Teal40 = Color(0xFF006B5E)
private val Teal80 = Color(0xFF5DDBC0)
private val Red40 = Color(0xFFBA1A1A)
private val Red80 = Color(0xFFFFB4AB)

private val LightColors = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = Green90,
    onPrimaryContainer = GreenDark,
    secondary = Teal40,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCF2E5),
    onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFF4A6267),
    error = Red40,
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF414942),
    outline = Color(0xFF717972),
    outlineVariant = Color(0xFFC0C9C1),
)

private val DarkColors = darkColorScheme(
    primary = Green80,
    onPrimary = Color(0xFF003822),
    primaryContainer = Green40,
    onPrimaryContainer = Green90,
    secondary = Teal80,
    onSecondary = Color(0xFF003730),
    secondaryContainer = Teal40,
    onSecondaryContainer = Color(0xFFCCF2E5),
    tertiary = Color(0xFFB0CCD1),
    error = Red80,
    surface = Color(0xFF191C1A),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC0C9C1),
    outline = Color(0xFF8B938C),
    outlineVariant = Color(0xFF414942),
)

private val SplitFreeTypography = Typography(
    headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

@Composable
fun SplitFreeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val dynamic = if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
            dynamic.copy(
                primary = if (darkTheme) Green80 else Green40,
                primaryContainer = if (darkTheme) Green40 else Green90,
                onPrimaryContainer = if (darkTheme) Green90 else GreenDark,
                secondary = if (darkTheme) Teal80 else Teal40
            )
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = SplitFreeTypography,
        content = content
    )
}
