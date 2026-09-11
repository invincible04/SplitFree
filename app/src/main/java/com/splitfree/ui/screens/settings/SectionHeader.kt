package com.splitfree.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens

@Composable
fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()

    Row(
        modifier = Modifier
            .padding(
                horizontal = tokens.screenPaddingHorizontal,
                vertical = tokens.sectionHeaderVerticalPadding
            )
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(tokens.iconMedium),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = title,
            style = if (adaptive.isCompact) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = MaterialTheme.colorScheme.primary
        )
    }
}
