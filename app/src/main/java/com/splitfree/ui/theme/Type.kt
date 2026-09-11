package com.splitfree.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.splitfree.R

/**
 * Inter, bundled as a single variable font (SIL OFL 1.1, see `assets/licenses/Inter-OFL.txt`).
 *
 * Four weights are declared against the same resource so Compose's font matcher can pick the nearest
 * declared weight and apply the matching `wght` axis value.
 */
val InterFontFamily =
    FontFamily(
        Font(
            R.font.inter_variable,
            FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400))
        ),
        Font(
            R.font.inter_variable,
            FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500))
        ),
        Font(
            R.font.inter_variable,
            FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600))
        ),
        Font(
            R.font.inter_variable,
            FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700))
        )
    )

/** OpenType `tnum`: every digit takes the same advance so stacked amounts line up. Use on all money. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

private val M3Defaults = Typography()

private fun TextStyle.inter(size: Int, lineHeight: Int, weight: FontWeight, letterSpacing: Float = 0f): TextStyle =
    copy(
        fontFamily = InterFontFamily,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        fontWeight = weight,
        letterSpacing = letterSpacing.sp
    )

/**
 * SplitFree type scale. Sizes are in sp; nothing user-readable goes below 12sp.
 *
 * - display*: hero / summary money (pair with [tabular]).
 * - headline*: page greetings and form intros.
 * - title*: top-bar titles, sheet titles, section heads, card titles.
 * - body*: running copy; bodySmall is muted meta.
 * - label*: buttons (Large), chips (Medium) and the uppercase eyebrow (Small).
 */
val SplitFreeTypography =
    Typography(
        displayLarge = M3Defaults.displayLarge.inter(42, 46, FontWeight.SemiBold, -1.5f),
        displayMedium = M3Defaults.displayMedium.inter(36, 40, FontWeight.SemiBold, -1.2f),
        displaySmall = M3Defaults.displaySmall.inter(30, 36, FontWeight.SemiBold, -1.0f),
        headlineLarge = M3Defaults.headlineLarge.inter(31, 34, FontWeight.Bold, -1.0f),
        headlineMedium = M3Defaults.headlineMedium.inter(28, 32, FontWeight.SemiBold, -0.8f),
        headlineSmall = M3Defaults.headlineSmall.inter(24, 30, FontWeight.SemiBold, -0.6f),
        titleLarge = M3Defaults.titleLarge.inter(20, 26, FontWeight.Bold, -0.3f),
        titleMedium = M3Defaults.titleMedium.inter(16, 22, FontWeight.SemiBold, -0.1f),
        titleSmall = M3Defaults.titleSmall.inter(14, 20, FontWeight.SemiBold),
        bodyLarge = M3Defaults.bodyLarge.inter(16, 24, FontWeight.Normal),
        bodyMedium = M3Defaults.bodyMedium.inter(14, 20, FontWeight.Normal),
        bodySmall = M3Defaults.bodySmall.inter(12, 16, FontWeight.Normal),
        labelLarge = M3Defaults.labelLarge.inter(15, 20, FontWeight.Bold),
        labelMedium = M3Defaults.labelMedium.inter(13, 18, FontWeight.SemiBold),
        labelSmall = M3Defaults.labelSmall.inter(11, 16, FontWeight.Bold, 1.2f)
    )
