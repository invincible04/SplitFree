package com.splitfree.ui.theme

import android.app.Application
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ThemeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `light scheme keeps readable contrast on its core role pairs`() {
        assertScheme(LightColorScheme, LightSplitFreeColors)
    }

    @Test
    fun `dark scheme keeps readable contrast on its core role pairs`() {
        assertScheme(DarkColorScheme, DarkSplitFreeColors)
    }

    @Test
    fun `dark card fill sits one clear step above the background`() {
        val scheme = DarkColorScheme
        val step = luminance(scheme.surfaceContainerLowest) - luminance(scheme.background)
        assertTrue("card minus background luminance %.4f must be at least 0.007".format(step), step >= 0.007)
        assertContrast("surfaceContainerLowest/background", scheme.surfaceContainerLowest, scheme.background, 1.12)
    }

    @Test
    fun `dark surface ladder climbs from background to card to container to raised pill`() {
        val scheme = DarkColorScheme
        val ladder =
            listOf(
                "background" to scheme.background,
                "surfaceContainerLowest" to scheme.surfaceContainerLowest,
                "surfaceContainerLow" to scheme.surfaceContainerLow,
                "surfaceContainer" to scheme.surfaceContainer,
                "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                "surfaceContainerHighest" to scheme.surfaceContainerHighest,
                "surfaceBright" to scheme.surfaceBright
            )
        ladder.zipWithNext { (lowerName, lower), (upperName, upper) ->
            assertTrue(
                "$upperName must be brighter than $lowerName",
                luminance(upper) > luminance(lower)
            )
        }
        assertContrast("surfaceBright/surfaceContainer", scheme.surfaceBright, scheme.surfaceContainer, 1.4)
        assertContrast(
            "surfaceContainer/surfaceContainerLowest",
            scheme.surfaceContainer,
            scheme.surfaceContainerLowest,
            1.1
        )
        assertEquals("surfaceDim is the background in dark", scheme.background, scheme.surfaceDim)
        assertEquals("surface is the background in dark", scheme.background, scheme.surface)
    }

    @Test
    fun `dark hairline is visible on the card fill`() {
        val scheme = DarkColorScheme
        assertContrast(
            "outlineVariant/surfaceContainerLowest",
            scheme.outlineVariant,
            scheme.surfaceContainerLowest,
            1.3
        )
        assertContrast("outline/surfaceContainerLowest", scheme.outline, scheme.surfaceContainerLowest, 3.0)
        assertEquals("line mirrors outlineVariant", scheme.outlineVariant, DarkSplitFreeColors.line)
    }

    @Test
    fun `dark hero, brand wash and card are three distinct fills`() {
        val scheme = DarkColorScheme
        val hero = DarkSplitFreeColors.hero
        val card = scheme.surfaceContainerLowest
        val wash = scheme.primaryContainer
        assertContrast("hero/primaryContainer", hero, wash, 1.15)
        assertContrast("hero/surfaceContainerLowest", hero, card, 1.15)
        assertContrast("primaryContainer/surfaceContainerLowest", wash, card, 1.15)
        assertTrue("hero must be greener than the card", greenness(hero) > greenness(card) + 0.03f)
        assertTrue("brand wash must be greener than the card", greenness(wash) > greenness(card) + 0.03f)
        assertContrast("onSurfaceVariant/primaryContainer", scheme.onSurfaceVariant, wash, 4.5)
        assertContrast("onSurfaceVariant/surfaceContainer", scheme.onSurfaceVariant, scheme.surfaceContainer, 4.5)
        assertContrast(
            "onSurfaceVariant/warningContainer",
            scheme.onSurfaceVariant,
            DarkSplitFreeColors.warningContainer,
            4.5
        )
        assertContrast("errorContainer/background", scheme.errorContainer, scheme.background, 1.3)
        assertContrast("warningContainer/background", DarkSplitFreeColors.warningContainer, scheme.background, 1.3)
    }

    @Test
    fun `dark charcoal surfaces leave saturated green to the hero and actions`() {
        val scheme = DarkColorScheme
        val palette = DarkSplitFreeColors
        assertTrue(
            "Everyday cards must stay close to charcoal rather than becoming green panels",
            greenness(scheme.surfaceContainerLowest) in 0.01f..0.025f
        )
        assertTrue("Background must stay near black", luminance(scheme.background) < 0.005)
        assertTrue("Main action must be a saturated green", greenness(palette.action) >= 0.25f)
        assertEquals(scheme.primary, palette.action)
        assertEquals(scheme.onPrimary, palette.onAction)
        assertEquals("Create actions keep their citron identity", LightSplitFreeColors.lime, palette.lime)
        assertContrast("action/card", palette.action, scheme.surfaceContainerLowest, 4.5)
        assertContrast("hero/gradientEnd", palette.hero, palette.heroGradientEnd, 1.5)
        assertTrue("Hero fade ends darker", luminance(palette.heroGradientEnd) < luminance(palette.hero))
    }

    @Test
    fun `dark hero text stays readable across the gradient and maximum glow`() {
        val palette = DarkSplitFreeColors
        // Check the full fade and the worst case where the glow overlaps a label at large text or in RTL.
        for (step in 0..20) {
            val fill = lerp(palette.hero, palette.heroGradientEnd, step / 20f)
            val decorated = palette.heroAccent.copy(alpha = palette.heroAccentAlpha).compositeOver(fill)
            for (background in listOf(fill, decorated)) {
                assertContrast("onHero/gradient[$step]", palette.onHero, background, 4.5)
                assertContrast("heroMuted/gradient[$step]", palette.heroMuted, background, 4.5)
            }
        }
    }

    @Test
    fun `dark secondary copy remains readable on fields sheets and selected pills`() {
        val scheme = DarkColorScheme
        for (fill in listOf(scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh)) {
            assertContrast("onSurfaceVariant/container", scheme.onSurfaceVariant, fill, 4.5)
            assertContrast("faint/container", DarkSplitFreeColors.faint, fill, 4.5)
        }
        assertContrast("selected tab label", scheme.onSurface, scheme.surfaceBright, 4.5)
        assertContrast("unselected tab label", scheme.onSurfaceVariant, scheme.surfaceContainer, 4.5)
    }

    @Test
    fun `light palette keeps its ivory values`() {
        val scheme = LightColorScheme
        assertEquals(Color(0xFFF6F5EF), scheme.background)
        assertEquals(Color(0xFFF6F5EF), scheme.surface)
        assertEquals(Color(0xFFFDFCF8), scheme.surfaceContainerLowest)
        assertEquals(Color(0xFFEEEEE7), scheme.surfaceContainer)
        assertEquals(Color(0xFFFDFCF8), scheme.surfaceBright)
        assertEquals(Color(0xFFDCDED5), scheme.outlineVariant)
        assertEquals(Color(0xFF2D5E45), scheme.primary)
        assertEquals(Color(0xFFE7F0E5), scheme.primaryContainer)
        assertEquals(Color(0xFF182019), scheme.onSurface)
        assertEquals(Color(0xFF697169), scheme.onSurfaceVariant)
        assertEquals(Color(0xFF223B2C), LightSplitFreeColors.hero)
        assertEquals(Color(0xFFC9ED71), LightSplitFreeColors.lime)
        assertEquals(Color(0xFF687067), LightSplitFreeColors.faint)
        assertEquals(Color(0xFFDCDED5), LightSplitFreeColors.line)
        assertEquals(scheme.inverseSurface, LightSplitFreeColors.action)
        assertEquals(scheme.inverseOnSurface, LightSplitFreeColors.onAction)
        assertEquals(LightSplitFreeColors.hero, LightSplitFreeColors.heroGradientEnd)
        assertEquals(0.55f, LightSplitFreeColors.heroAccentAlpha)
    }

    @Test
    fun `theme provides the extended palette, Inter typography and brand shapes`() {
        var light: SplitFreeColors? = null
        var dark: SplitFreeColors? = null
        var primary: Color? = null
        var titleFamily = false
        var largeShape: androidx.compose.ui.graphics.Shape? = null
        compose.setContent {
            SplitFreeTheme(darkTheme = false) {
                light = MaterialTheme.splitFree
                primary = MaterialTheme.colorScheme.primary
                titleFamily = MaterialTheme.typography.titleLarge.fontFamily == InterFontFamily
                largeShape = MaterialTheme.shapes.large
            }
            SplitFreeTheme(darkTheme = true) { dark = MaterialTheme.splitFree }
        }
        compose.runOnIdle {
            assertSame(LightSplitFreeColors, light)
            assertSame(DarkSplitFreeColors, dark)
            assertEquals(LightColorScheme.primary, primary)
            assertTrue("Typography must be built on Inter", titleFamily)
            assertEquals(SplitFreeShapes.large, largeShape)
        }
    }

    @Test
    fun `type scale keeps readable sizes and the eyebrow letter spacing`() {
        val t = SplitFreeTypography
        listOf(
            t.displayLarge, t.displayMedium, t.displaySmall, t.headlineLarge, t.headlineMedium, t.headlineSmall,
            t.titleLarge, t.titleMedium, t.titleSmall, t.bodyLarge, t.bodyMedium, t.bodySmall,
            t.labelLarge, t.labelMedium
        ).forEach { style ->
            assertEquals(InterFontFamily, style.fontFamily)
            assertTrue("${style.fontSize} must be at least 12sp", style.fontSize.value >= 12f)
        }
        assertEquals(11f, t.labelSmall.fontSize.value)
        assertEquals(1.2f, t.labelSmall.letterSpacing.value)
        assertEquals(FontWeight.Bold, t.labelSmall.fontWeight)
        assertEquals("tnum", t.displayLarge.tabular().fontFeatureSettings)
    }

    private fun assertScheme(scheme: ColorScheme, extended: SplitFreeColors) {
        val card = scheme.surfaceContainerLowest
        assertContrast("onSurface/surface", scheme.onSurface, scheme.surface, 4.5)
        assertContrast("onBackground/background", scheme.onBackground, scheme.background, 4.5)
        assertContrast("onSurface/surfaceContainerLowest", scheme.onSurface, card, 4.5)
        assertContrast("onSurface/surfaceContainer", scheme.onSurface, scheme.surfaceContainer, 4.5)
        assertContrast("onSurface/surfaceBright", scheme.onSurface, scheme.surfaceBright, 4.5)
        assertContrast("onSurfaceVariant/surface", scheme.onSurfaceVariant, scheme.surface, 4.5)
        assertContrast("onSurfaceVariant/surfaceContainerLowest", scheme.onSurfaceVariant, card, 4.5)
        assertContrast("onPrimary/primary", scheme.onPrimary, scheme.primary, 4.5)
        assertContrast("onPrimaryContainer/primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer, 4.5)
        assertContrast("primary/primaryContainer", scheme.primary, scheme.primaryContainer, 4.5)
        assertContrast(
            "onTertiaryContainer/tertiaryContainer",
            scheme.onTertiaryContainer,
            scheme.tertiaryContainer,
            4.5
        )
        assertContrast("onError/error", scheme.onError, scheme.error, 4.5)
        assertContrast("onErrorContainer/errorContainer", scheme.onErrorContainer, scheme.errorContainer, 4.5)
        assertContrast("inverseOnSurface/inverseSurface", scheme.inverseOnSurface, scheme.inverseSurface, 4.5)
        assertContrast("primary/surface", scheme.primary, scheme.surface, 4.5)
        assertContrast("primary/surfaceContainerLowest", scheme.primary, card, 4.5)
        assertContrast("error/surface", scheme.error, scheme.surface, 4.5)
        assertContrast("outlineVariant/surfaceContainerLowest", scheme.outlineVariant, card, 1.25)
        assertContrast("onHero/hero", extended.onHero, extended.hero, 4.5)
        assertContrast("heroMuted/hero", extended.heroMuted, extended.hero, 4.5)
        assertContrast("onLime/lime", extended.onLime, extended.lime, 4.5)
        assertContrast("onAction/action", extended.onAction, extended.action, 4.5)
        assertContrast("positive/surface", extended.positive, scheme.surface, 4.5)
        assertContrast("negative/surface", extended.negative, scheme.surface, 4.5)
        assertContrast("positive/surfaceContainerLowest", extended.positive, card, 4.5)
        assertContrast("negative/surfaceContainerLowest", extended.negative, card, 4.5)
        assertContrast("positive/primaryContainer", extended.positive, scheme.primaryContainer, 4.5)
        assertContrast("negative/primaryContainer", extended.negative, scheme.primaryContainer, 4.5)
        assertContrast("faint/surface", extended.faint, scheme.surface, 4.5)
        assertContrast("faint/surfaceContainerLowest", extended.faint, card, 4.5)
        assertContrast("warning/warningContainer", extended.warning, extended.warningContainer, 3.0)
        assertEquals(6, extended.avatarPalette.size)
        extended.avatarPalette.forEachIndexed { i, fill -> assertContrast("white/avatar[$i]", Color.White, fill, 3.0) }
    }

    private fun assertContrast(pair: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$pair contrast %.2f must be at least $minimum".format(ratio), ratio >= minimum)
    }

    /** Relative-luminance contrast ratio: (lighter + 0.05) / (darker + 0.05). */
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    /** How far the green channel leads the red and blue average; higher means a more saturated green. */
    private fun greenness(color: Color): Float = color.green - (color.red + color.blue) / 2f
}
