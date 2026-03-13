package com.splitfree.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens

/** Profile card at the top of Settings — avatar initial + editable display name. */
@Composable
fun ProfileSection(displayName: String, onNameChange: (String) -> Unit) {
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()

    Row(
        modifier = Modifier.fillMaxWidth().padding(tokens.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.sectionSpacing)
    ) {
        // Avatar circle with initial
        Box(
            modifier = Modifier
                .size(tokens.avatarSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            val initial = displayName.firstOrNull()?.uppercase()
            if (initial != null) {
                Text(
                    initial,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = null,
                    modifier = Modifier.size(tokens.avatarFallbackIconSize),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = displayName,
                onValueChange = onNameChange,
                label = { Text("Display name") },
                placeholder = { Text("How friends see you") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = if (adaptive.isCompact) MaterialTheme.shapes.small else MaterialTheme.shapes.medium
            )
        }
    }
}
