package com.splitfree.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Glyph is 22dp on the 48dp Home tile and 25dp on the 55dp Settings hero tile. */
private const val GLYPH_SCALE = 0.46f

/**
 * Ink identity tile for the current user (Home top bar, Settings header): an `inverseSurface` rounded square
 * (corner = size / 3) showing the display name's [initial] in `titleLarge`, or a person glyph when there is
 * none. With [onClick] it is a button (Home's Settings entry); otherwise it is read as one node.
 * [contentDescription] names the glyph; the tile shows no text of its own then, so TalkBack needs the words.
 */
@Composable
fun IdentityTile(
    initial: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(size / 3)
    val color = MaterialTheme.colorScheme.inverseSurface
    val contentColor = MaterialTheme.colorScheme.inverseOnSurface
    val content: @Composable () -> Unit = {
        Box(contentAlignment = Alignment.Center) {
            if (!initial.isNullOrBlank()) {
                Text(initial, style = MaterialTheme.typography.titleLarge, color = contentColor, maxLines = 1)
            } else {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(size * GLYPH_SCALE),
                    tint = contentColor
                )
            }
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(size).semantics { role = Role.Button },
            shape = shape,
            color = color,
            contentColor = contentColor,
            content = content
        )
    } else {
        Surface(
            modifier = modifier.size(size).semantics(mergeDescendants = true) {},
            shape = shape,
            color = color,
            contentColor = contentColor,
            content = content
        )
    }
}
