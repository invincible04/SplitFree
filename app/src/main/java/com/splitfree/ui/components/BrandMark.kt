package com.splitfree.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.splitfree.R

/** Adaptive launcher icons draw on a 108dp canvas; launchers show the central 72dp of it. */
private const val LAUNCHER_CANVAS_DP = 108f
private const val LAUNCHER_VISIBLE_DP = 72f

/** The ribbon itself spans about 66dp of the canvas, so this scale lets the bare mark fill its box. */
private const val LAUNCHER_MARK_DP = 66f

/** Corner radius as a share of the tile size; 96dp gives the 18dp coin corner. */
private const val CORNER_RATIO = 0.19f

/**
 * The app mark: the launcher's twisted-ribbon "S". With [tile] it sits on the launcher background colour,
 * cropped the way the home-screen icon is, with a 1dp `outlineVariant` hairline so the tile keeps an edge on
 * dark surfaces; without it only the ribbon is drawn, scaled to fill [size]. Decorative by default; pass
 * [contentDescription] when the mark is the only thing naming the app.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    shape: Shape = RoundedCornerShape(size * CORNER_RATIO),
    tile: Boolean = true,
    contentDescription: String? = null
) {
    if (!tile) {
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = contentDescription,
                modifier = Modifier.requiredSize(size * LAUNCHER_CANVAS_DP / LAUNCHER_MARK_DP)
            )
        }
        return
    }
    Surface(
        modifier = modifier.size(size),
        shape = shape,
        color = colorResource(R.color.ic_launcher_background),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        // The surface clips to [shape], so the oversized image is cropped like a masked launcher icon.
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = contentDescription,
                modifier = Modifier.requiredSize(size * LAUNCHER_CANVAS_DP / LAUNCHER_VISIBLE_DP)
            )
        }
    }
}
