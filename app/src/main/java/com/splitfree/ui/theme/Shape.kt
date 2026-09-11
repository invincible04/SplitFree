package com.splitfree.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii: 8 (chips, inputs inside rows), 12 (secondary buttons, currency pill),
 * 16 (text fields, list tiles), 20 (cards, primary buttons, FAB), 28 (hero card, sheet top).
 */
val SplitFreeShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp)
    )
