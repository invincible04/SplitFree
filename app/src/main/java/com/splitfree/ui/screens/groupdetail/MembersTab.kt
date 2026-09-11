package com.splitfree.ui.screens.groupdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens

@Composable
fun MembersTab(
    members: List<String>,
    createdBy: String,
    isCreator: Boolean,
    myPubkey: String,
    memberNames: Map<String, String> = emptyMap(),
    onRemove: (String) -> Unit
) {
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()
    val uniqueMembers = remember(members) { members.distinct() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = tokens.itemSpacing)
    ) {
        items(uniqueMembers, key = { it }) { pubkey ->
            val displayName = memberNames[pubkey]?.ifBlank { null }
            val shortKey = pubkey.take(8) + "…" + pubkey.takeLast(4)
            ListItem(
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(tokens.listAvatarSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            displayName?.first()?.uppercase() ?: "#",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                },
                headlineContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)
                    ) {
                        Text(
                            text = displayName ?: shortKey,
                            maxLines = if (adaptive.isCompact) 1 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (pubkey == createdBy) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    stringResource(R.string.creator_badge),
                                    modifier = Modifier.padding(horizontal = tokens.itemSpacing, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        if (pubkey == myPubkey) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    stringResource(R.string.you_badge),
                                    modifier = Modifier.padding(horizontal = tokens.itemSpacing, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                },
                supportingContent = if (displayName != null) {
                    {
                        Text(
                            shortKey,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    null
                },
                trailingContent = {
                    if (isCreator && pubkey != myPubkey) {
                        IconButton(onClick = { onRemove(pubkey) }) {
                            Icon(
                                Icons.Outlined.PersonRemove,
                                stringResource(R.string.remove_member),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    }
}
