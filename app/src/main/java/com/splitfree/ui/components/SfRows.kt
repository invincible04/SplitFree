package com.splitfree.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.splitFree
import com.splitfree.ui.theme.tabular

private val ChoiceRowMinHeight = 56.dp
private val SettingsRowMinHeight = 64.dp
private val RadioSize = 20.dp

/**
 * Single-select row with a custom 20dp radio ring: ring and dot turn `primary` when
 * [selected]. Exposes `Role.RadioButton` + selected state; place several inside `Modifier.selectableGroup()`
 * and separate them with [SfDivider].
 */
@Composable
fun ChoiceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.splitFree.faint
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .heightIn(min = ChoiceRowMinHeight)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(RadioSize).border(1.5.dp, ring, CircleShape).padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary, CircleShape))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Navigation/settings row for [SfListCard]: [iconTint]ed [icon] (`primary` by default), `titleSmall` [title]
 * in [titleColor] (`onSurface`), muted `bodySmall` [subtitle] and a [trailing] slot that defaults to a
 * chevron. 64dp minimum. For a destructive or irreversible action pass `error` for both colours; the
 * subtitle and chevron stay muted so the row reads as a caution, not an alarm, and the wording must still
 * say what happens.
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: @Composable () -> Unit = { SettingsChevron() }
) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .heightIn(min = SettingsRowMinHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = iconTint)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** Default trailing chevron for [SettingsRow]; auto-mirrors in RTL. */
@Composable
fun SettingsChevron(modifier: Modifier = Modifier) {
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        modifier = modifier.size(22.dp),
        tint = MaterialTheme.splitFree.faint
    )
}

/**
 * Label → value row for read-only breakdowns in sheets: `bodyMedium` [label] taking the
 * width, 16dp gap, then the tabular `titleSmall` [value]. Rows own 14dp/15dp padding so several can sit in one
 * [SfListCard] with [SfDivider]s between them.
 */
@Composable
fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    DetailRow(label = label, modifier = modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.tabular(),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}

/** [DetailRow] with a composable [trailing] slot instead of a plain value, e.g. a [MoneyText]. */
@Composable
fun DetailRow(label: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}
