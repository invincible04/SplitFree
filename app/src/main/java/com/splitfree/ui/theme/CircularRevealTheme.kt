package com.splitfree.ui.theme

import android.graphics.Bitmap
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.drawToBitmap
import com.splitfree.R
import com.splitfree.ui.theme.ThemeTransitionState.captureAndChange
import kotlin.math.hypot

/**
 * Circular-reveal theme transition.
 *
 * Flow:
 * 1. [captureAndChange] captures bitmap and stores the pending change.
 * 2. [CircularRevealTheme] sees the overlay, renders it on top (hiding content).
 * 3. Once the overlay is drawn (first frame
 *    callback), the pending change is applied — new theme renders underneath.
 * 4. The circle animates open, revealing the new theme. No blink because the
 *    overlay covers the old→new transition.
 */
object ThemeTransitionState {
    var overlay by mutableStateOf<Bitmap?>(null)
        private set
    var pendingChange by mutableStateOf<(() -> Unit)?>(null)
        private set
    var animationDone by mutableStateOf(false)

    fun captureAndChange(view: View, change: () -> Unit) {
        if (overlay != null) return
        animationDone = false
        val bmp =
            try {
                view.drawToBitmap()
            } catch (_: Exception) {
                null
            }
        if (bmp != null) {
            pendingChange = change
            overlay = bmp
        } else {
            // Bitmap capture failed — apply change immediately without animation
            change()
        }
    }

    fun consumePendingChange() {
        pendingChange?.invoke()
        pendingChange = null
    }

    fun clear() {
        // No recycle(): the renderer may still draw the last composed frame from this
        // bitmap. Dropping the reference is enough — GC reclaims it once nothing draws it.
        overlay = null
        pendingChange = null
    }
}

@Composable
fun CircularRevealTheme(content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    val currentOverlay = ThemeTransitionState.overlay

    // When overlay appears: apply the theme change (underneath), then animate the reveal
    LaunchedEffect(currentOverlay) {
        if (currentOverlay != null) {
            // Frame 0: overlay is rendered covering everything.
            // Now safe to switch theme — user won't see it until the circle opens.
            ThemeTransitionState.consumePendingChange()
            progress.snapTo(0f)
            progress.animateTo(1f, tween(600))
            ThemeTransitionState.clear()
            // Signal that animation is done — status bar can now update
            ThemeTransitionState.animationDone = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        content()

        currentOverlay?.let { bmp ->
            if (!bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_theme_overlay),
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(InvertedCircleShape(progress.value)),
                    contentScale = ContentScale.FillBounds
                )
            }
        }
    }
}

private class InvertedCircleShape(private val fraction: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = hypot(size.width, size.height) / 2f
        val radius = maxRadius * fraction
        val path =
            Path().apply {
                addRect(Rect(Offset.Zero, size))
                addOval(Rect(center = center, radius = radius))
            }
        path.fillType = PathFillType.EvenOdd
        return Outline.Generic(path)
    }
}
