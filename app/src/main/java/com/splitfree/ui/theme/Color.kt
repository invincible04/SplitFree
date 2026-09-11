package com.splitfree.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Brand palette. Light: warm ivory surfaces, off-white cards, deep forest-green brand, citron/lime for the
 * primary create action, "ink" near-black for the main button and an inverted dark-green hero card.
 * Dark: the same warm green-black hue on a surface ladder that steps up clearly from background to card to
 * container to raised pill, so cards, tracks and washes never merge into one field.
 *
 * Kit mapping: `surfaceContainerLowest` is the card fill in both themes; in dark it sits one clear step above
 * the background.
 */

// ---- Light --------------------------------------------------------------------------------------

private val LightIvory = Color(0xFFF6F5EF)
private val LightCard = Color(0xFFFDFCF8)
private val LightInk = Color(0xFF182019)
private val LightMuted = Color(0xFF697169)
private val LightBrand = Color(0xFF2D5E45)
private val LightBrandWash = Color(0xFFE7F0E5)
private val LightHero = Color(0xFF223B2C)
private val Citron = Color(0xFFC9ED71)
private val LightNegative = Color(0xFFA7493F)

val LightColorScheme: ColorScheme =
    lightColorScheme(
        primary = LightBrand,
        onPrimary = LightIvory,
        primaryContainer = LightBrandWash,
        onPrimaryContainer = LightHero,
        inversePrimary = Color(0xFFA6DDB8),
        secondary = LightHero,
        onSecondary = Color(0xFFF5F7ED),
        secondaryContainer = LightBrandWash,
        onSecondaryContainer = LightInk,
        tertiary = Color(0xFF5C7A1F),
        onTertiary = Color.White,
        tertiaryContainer = Citron,
        onTertiaryContainer = Color(0xFF172019),
        background = LightIvory,
        onBackground = LightInk,
        surface = LightIvory,
        onSurface = LightInk,
        surfaceVariant = Color(0xFFE7E8DF),
        onSurfaceVariant = LightMuted,
        surfaceTint = LightBrand,
        inverseSurface = LightInk,
        inverseOnSurface = LightIvory,
        error = LightNegative,
        onError = Color.White,
        errorContainer = Color(0xFFF7E4E0),
        onErrorContainer = Color(0xFF5A1F18),
        outline = Color(0xFF929990),
        outlineVariant = Color(0xFFDCDED5),
        scrim = Color(0xFF0C120D),
        surfaceBright = Color(0xFFFDFCF8),
        surfaceDim = Color(0xFFDCDED5),
        surfaceContainer = Color(0xFFEEEEE7),
        surfaceContainerHigh = Color(0xFFE7E8DF),
        surfaceContainerHighest = Color(0xFFDCDED5),
        surfaceContainerLow = Color(0xFFF3F2EB),
        surfaceContainerLowest = LightCard
    )

// ---- Dark ---------------------------------------------------------------------------------------

// Surface ladder, darkest to brightest. Each step is far enough from its neighbours to read at a glance.
private val DarkBackground = Color(0xFF101410)
private val DarkCard = Color(0xFF1D251D)
private val DarkContainerLow = Color(0xFF222B22)
private val DarkContainer = Color(0xFF293229)
private val DarkContainerHigh = Color(0xFF313C31)
private val DarkContainerHighest = Color(0xFF3B473B)
private val DarkRaised = Color(0xFF445244)
private val DarkLine = Color(0xFF3D493D)
private val DarkInk = Color(0xFFF1F2EB)
private val DarkMuted = Color(0xFFA7AFA5)
private val DarkBrand = Color(0xFFA6DDB8)

// Greener than the neutral ladder so the wash card and the hero each stand apart from plain cards.
private val DarkBrandWash = Color(0xFF203829)
private val DarkHero = Color(0xFF23492F)
private val DarkNegative = Color(0xFFFFAAA0)

val DarkColorScheme: ColorScheme =
    darkColorScheme(
        primary = DarkBrand,
        onPrimary = Color(0xFF0F2A1B),
        primaryContainer = DarkBrandWash,
        onPrimaryContainer = Color(0xFFC9E8D2),
        inversePrimary = LightBrand,
        secondary = Color(0xFFC9E8D2),
        onSecondary = Color(0xFF1B2C21),
        secondaryContainer = DarkBrandWash,
        onSecondaryContainer = DarkInk,
        tertiary = Citron,
        onTertiary = Color(0xFF172019),
        tertiaryContainer = Citron,
        onTertiaryContainer = Color(0xFF172019),
        background = DarkBackground,
        onBackground = DarkInk,
        surface = DarkBackground,
        onSurface = DarkInk,
        surfaceVariant = DarkContainerHigh,
        onSurfaceVariant = DarkMuted,
        surfaceTint = DarkBrand,
        inverseSurface = DarkInk,
        inverseOnSurface = DarkBackground,
        error = DarkNegative,
        onError = Color(0xFF4A1712),
        errorContainer = Color(0xFF4A2420),
        onErrorContainer = Color(0xFFFFD9D4),
        outline = Color(0xFF8A948A),
        outlineVariant = DarkLine,
        scrim = Color.Black,
        surfaceBright = DarkRaised,
        surfaceDim = DarkBackground,
        surfaceContainer = DarkContainer,
        surfaceContainerHigh = DarkContainerHigh,
        surfaceContainerHighest = DarkContainerHighest,
        surfaceContainerLow = DarkContainerLow,
        surfaceContainerLowest = DarkCard
    )

// ---- Extended palette ---------------------------------------------------------------------------

/**
 * Colour roles the Material scheme does not model. Read them via [MaterialTheme.splitFree].
 *
 * @property positive money owed to you / connected state. Never the only cue: pair with words.
 * @property negative money you owe (same hue as `error`, kept separate so error styling can diverge later).
 * @property warning offline / attention state; [warningContainer] is its soft wash.
 * @property hero inverted dark-green hero card background; [onHero] its text, [heroMuted] its eyebrow and
 *   stat labels, [heroAccent] the decorative citron blob.
 * @property lime the citron primary-action fill; [onLime] its text.
 * @property faint the quietest readable text (eyebrows, footnotes). Readable on `surface` and on the card fill.
 * @property line hairline borders and dividers (same as `outlineVariant`).
 * @property avatarPalette deterministic member-avatar fills, indexed by pubkey hash.
 */
@Immutable
data class SplitFreeColors(
    val positive: Color,
    val negative: Color,
    val warning: Color,
    val warningContainer: Color,
    val hero: Color,
    val onHero: Color,
    val heroMuted: Color,
    val heroAccent: Color,
    val lime: Color,
    val onLime: Color,
    val faint: Color,
    val line: Color,
    val avatarPalette: List<Color>
)

val LightSplitFreeColors =
    SplitFreeColors(
        positive = Color(0xFF246744),
        negative = LightNegative,
        warning = Color(0xFF9B6328),
        warningContainer = Color(0xFFF3EADF),
        hero = LightHero,
        onHero = Color(0xFFF5F7ED),
        heroMuted = Color(0xFFD0DDCF),
        heroAccent = Citron,
        lime = Citron,
        onLime = LightInk,
        // Slightly darker than the muted text so eyebrows stay readable on ivory.
        faint = Color(0xFF687067),
        line = Color(0xFFDCDED5),
        avatarPalette =
        listOf(
            Color(0xFF456B56),
            Color(0xFF95614F),
            Color(0xFF5D668C),
            Color(0xFF957E47),
            Color(0xFF6B5A8A),
            Color(0xFF4F7A7D)
        )
    )

val DarkSplitFreeColors =
    SplitFreeColors(
        positive = Color(0xFF8ED4A7),
        negative = DarkNegative,
        warning = Color(0xFFEFBE78),
        warningContainer = Color(0xFF3A2E1E),
        hero = DarkHero,
        onHero = Color(0xFFF5F7ED),
        heroMuted = Color(0xFFD0DDCF),
        heroAccent = Color(0xFF8AA85A),
        lime = Citron,
        onLime = LightInk,
        faint = Color(0xFFA1AAA0),
        line = DarkLine,
        avatarPalette =
        listOf(
            Color(0xFF557F68),
            Color(0xFFA8735F),
            Color(0xFF6F789E),
            Color(0xFFA7905A),
            Color(0xFF7E6C9C),
            Color(0xFF618C8F)
        )
    )

val LocalSplitFreeColors = staticCompositionLocalOf { LightSplitFreeColors }

/** Extended brand roles for the current theme. Provided by `SplitFreeTheme`. */
val MaterialTheme.splitFree: SplitFreeColors
    @Composable
    @ReadOnlyComposable
    get() = LocalSplitFreeColors.current
