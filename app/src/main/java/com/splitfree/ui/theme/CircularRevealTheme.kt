package com.splitfree.ui.theme

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.drawToBitmap
import kotlin.math.hypot

/**
 * Circular reveal: captures old theme as screenshot overlay, then the new theme
 * content expands from center via a growing circle clipped on top.
 *
 * To avoid blink, [onBeforeChange] must capture the bitmap BEFORE the theme state changes.
 * Call [ThemeTransitionState.captureAndChange] from the button click.
 */
object ThemeTransitionState {
    var overlay by mutableStateOf<Bitmap?>(null)
        private set
    var animating by mutableStateOf(false)
        private set

    private var pendingChange: (() -> Unit)? = null

    /**
     * Called from the theme toggle button. Captures the current screen,
     * then applies the theme change. The composable picks up the overlay
     * and animates the reveal.
     */
    fun captureAndChange(view: android.view.View, change: () -> Unit) {
        if (animating) return
        overlay = try { view.drawToBitmap() } catch (_: Exception) { null }
        animating = true
        view.post { change() }
    }

    fun clear() {
        overlay?.recycle()
        overlay = null
        animating = false
    }
}

@Composable
fun CircularRevealTheme(content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(ThemeTransitionState.animating) {
        if (ThemeTransitionState.animating && ThemeTransitionState.overlay != null) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(600))
            ThemeTransitionState.clear()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // New theme content always renders underneath
        content()

        // Old theme screenshot on top, with an inverted circular hole that grows
        ThemeTransitionState.overlay?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(InvertedCircleShape(progress.value)),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

/**
 * Fills the entire rect EXCEPT a circle in the center.
 * At fraction=0 the hole is zero (old theme fully visible).
 * At fraction=1 the hole covers everything (old theme gone, new theme visible).
 */
private class InvertedCircleShape(private val fraction: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = hypot(size.width, size.height) / 2f
        val radius = maxRadius * fraction
        val path = Path().apply {
            // Full rectangle
            addRect(Rect(Offset.Zero, size))
            // Subtract circle (even-odd fill makes this a hole)
            addOval(Rect(center = center, radius = radius))
        }
        path.fillType = PathFillType.EvenOdd
        return Outline.Generic(path)
    }
}
