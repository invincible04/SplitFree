package com.splitfree.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.splitFree
import kotlin.math.abs

private const val INITIAL_SCALE = 0.42f
private val StackOverlap = (-6).dp
private val StackBorder = 2.dp

/**
 * Circular member avatar. Fill comes from `splitFree.avatarPalette` indexed by the pubkey
 * hash so the same member is always the same colour; the initial is derived from [name] (falls back to the
 * pubkey). The initial scales with [size]; [borderColor] adds a 2dp ring (used by [MemberStack]).
 */
@Composable
fun MemberAvatar(
    pubkey: String,
    name: String?,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    borderColor: Color? = null
) {
    val label = name?.takeIf { it.isNotBlank() } ?: pubkey
    val fill = avatarColor(pubkey)
    val fontSize = with(LocalDensity.current) { (size * INITIAL_SCALE).toSp() }
    Box(
        modifier =
        modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .then(if (borderColor != null) Modifier.border(StackBorder, borderColor, CircleShape) else Modifier)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Text(
            avatarInitial(label),
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontFamily = MaterialTheme.typography.labelLarge.fontFamily,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Overlapping row of up to [max] avatars with a 2dp `surface` ring so they read as a stack. Extra members
 * collapse into a "+N" bubble.
 */
@Composable
fun MemberStack(
    pubkeys: List<String>,
    names: Map<String, String>,
    modifier: Modifier = Modifier,
    max: Int = 4,
    size: Dp = 28.dp
) {
    val ring = MaterialTheme.colorScheme.surface
    val shown = pubkeys.take(max)
    val overflow = pubkeys.size - shown.size
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(StackOverlap)) {
        shown.forEach { key -> MemberAvatar(pubkey = key, name = names[key], size = size, borderColor = ring) }
        if (overflow > 0) {
            val fontSize = with(LocalDensity.current) { (size * INITIAL_SCALE).toSp() }
            Box(
                modifier =
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(StackBorder, ring, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "+$overflow",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = MaterialTheme.typography.labelLarge.fontFamily,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/** Deterministic avatar colour for [pubkey] from the current theme's `avatarPalette`. */
@Composable
fun avatarColor(pubkey: String): Color {
    val palette = MaterialTheme.splitFree.avatarPalette
    return palette[avatarPaletteIndex(pubkey, palette.size)]
}

/** `abs(hashCode) % size`, widened to Long so `Int.MIN_VALUE` cannot produce a negative index. */
internal fun avatarPaletteIndex(pubkey: String, paletteSize: Int): Int =
    (abs(pubkey.hashCode().toLong()) % paletteSize).toInt()

/**
 * First user-perceived character of [name] for an avatar, upper-cased. Uses the first code point so a
 * leading emoji or other supplementary-plane character is not split into a lone surrogate.
 */
fun avatarInitial(name: String): String {
    if (name.isEmpty()) return ""
    val codePoint = name.codePointAt(0)
    return String(Character.toChars(codePoint)).uppercase()
}
