package com.splitfree.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

private val TileShape = RoundedCornerShape(13.dp)

/**
 * Outlined line icon for an expense category key (the stable keys from `expenseCategories`). Unknown, blank
 * and `other` all resolve to a receipt so every expense row has an icon.
 */
fun expenseCategoryIcon(category: String): ImageVector = when (category.trim().lowercase(Locale.ROOT)) {
    "food" -> Icons.Outlined.Restaurant
    "transport", "travel" -> Icons.Outlined.DirectionsCar
    "shopping" -> Icons.Outlined.ShoppingBag
    "entertainment" -> Icons.Outlined.Theaters
    "utilities" -> Icons.Outlined.Lightbulb
    "rent", "housing" -> Icons.Outlined.Home
    "health" -> Icons.Outlined.MedicalServices
    else -> Icons.Outlined.Receipt
}

/**
 * 40dp `surfaceContainer` tile with the category's `primary`-tinted line icon, used on expense rows and
 * group cards. Decorative: the row text names the category, so the icon has no description.
 */
@Composable
fun CategoryIcon(category: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier = modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainer, TileShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            expenseCategoryIcon(category),
            contentDescription = null,
            modifier = Modifier.size(size / 2),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
