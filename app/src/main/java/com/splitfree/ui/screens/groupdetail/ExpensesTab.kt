package com.splitfree.ui.screens.groupdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.splitfree.domain.model.expense.Expense
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.util.CurrencyFormatter

@Composable
fun ExpensesTab(expenses: List<Expense>, memberNames: Map<String, String> = emptyMap()) {
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()

    if (expenses.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(tokens.emptyStatePadding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.Receipt,
                    contentDescription = null,
                    modifier = Modifier.size(tokens.emptyStateIcon),
                    tint = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.height(tokens.fieldSpacing))
                Text(
                    "No expenses yet",
                    style = if (adaptive.isCompact) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Tap + to add the first expense",
                    style = if (adaptive.isCompact) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(tokens.screenPaddingHorizontal),
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing)
        ) {
            items(expenses) { expense ->
                ExpenseRow(expense, memberNames)
            }
            item { Spacer(Modifier.height(tokens.listBottomSpacer)) }
        }
    }
}

@Composable
private fun ExpenseRow(expense: Expense, memberNames: Map<String, String> = emptyMap()) {
    val tokens = adaptiveSizeTokens()

    val categoryEmoji =
        when (expense.category.lowercase()) {
            "food" -> "🍕"
            "transport", "travel" -> "🚗"
            "shopping" -> "🛍️"
            "entertainment" -> "🎬"
            "utilities" -> "💡"
            "rent", "housing" -> "🏠"
            "health" -> "💊"
            else -> "💰"
        }

    ListItem(
        headlineContent = {
            Text(expense.description, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                "Paid by ${memberNames[expense.paidBy]?.ifBlank { null } ?: (expense.paidBy.take(6) + "…")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        },
        leadingContent = {
            Box(
                modifier =
                Modifier
                    .size(tokens.listAvatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(categoryEmoji)
            }
        },
        trailingContent = {
            Text(
                CurrencyFormatter.format(expense.amount, expense.currency),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    )
}
