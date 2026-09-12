package com.splitfree.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.splitfree.R

/**
 * "Balances in" line above the balance summary. Nothing at all while [selected] is null (no balance has a
 * currency yet), a plain [MiniLabel] naming the one currency, and an eyebrow plus a currency chip that opens
 * a [DropdownMenu] once balances exist in more than one. The chip announces itself as "Change balance
 * currency, X selected". [chipModifier] and [itemModifier] exist so screens can attach their test tags to
 * the chip and to each menu item.
 */
@Composable
fun CurrencyLine(
    currencies: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    chipModifier: Modifier = Modifier,
    itemModifier: (code: String) -> Modifier = { Modifier }
) {
    if (selected == null) return
    if (currencies.size <= 1) {
        MiniLabel(stringResource(R.string.balances_in_currency, selected), modifier = modifier.fillMaxWidth())
        return
    }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MiniLabel(stringResource(R.string.balances_in), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        CurrencyChip(
            currencies = currencies,
            selected = selected,
            onSelect = onSelect,
            modifier = chipModifier,
            itemModifier = itemModifier
        )
    }
}

@Composable
private fun CurrencyChip(
    currencies: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier,
    itemModifier: (code: String) -> Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.cd_change_balance_currency, selected)
    Box {
        SfSecondaryButton(
            text = selected,
            onClick = { expanded = true },
            leadingIcon = Icons.Outlined.ArrowDropDown,
            modifier = modifier.semantics { contentDescription = description }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            currencies.forEach { code ->
                DropdownMenuItem(
                    text = { Text(code, style = MaterialTheme.typography.titleSmall) },
                    onClick = {
                        expanded = false
                        onSelect(code)
                    },
                    trailingIcon = {
                        if (code == selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    modifier = itemModifier(code)
                )
            }
        }
    }
}
