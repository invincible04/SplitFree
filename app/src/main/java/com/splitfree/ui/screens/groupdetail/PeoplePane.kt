package com.splitfree.ui.screens.groupdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.ui.components.MemberAvatar
import com.splitfree.ui.components.SectionHead
import com.splitfree.ui.components.SfDivider
import com.splitfree.ui.components.SfIconButton
import com.splitfree.ui.components.SfListCard
import com.splitfree.ui.components.SfTextButton
import com.splitfree.ui.util.disambiguatedMemberName
import com.splitfree.ui.viewmodels.GroupDetailUiState

private val MemberRowMinHeight = 64.dp
private const val SHORT_KEY_HEAD = 8
private const val SHORT_KEY_TAIL = 4

/** People tab: every member with their role; the creator can remove others. */
@Composable
internal fun PeoplePane(state: GroupDetailUiState, onInvite: () -> Unit, onRemove: (String) -> Unit) {
    val members = remember(state.members) { state.members.distinct() }
    val rows = remember(members, state.memberNames) {
        members.map { pubkey ->
            val displayName = state.memberNames[pubkey]?.takeIf { it.isNotBlank() }
            MemberRowModel(
                pubkey = pubkey,
                displayName = displayName,
                shortKey = shortPubkey(pubkey),
                title = if (displayName != null) disambiguatedMemberName(pubkey, state.memberNames, members) else null
            )
        }
    }
    Column(Modifier.fillMaxWidth().testTag("group_pane_people")) {
        SectionHead(title = pluralStringResource(R.plurals.group_people_heading, members.size, members.size)) {
            SfTextButton(
                text = stringResource(R.string.group_invite),
                onClick = onInvite,
                modifier = Modifier.testTag("group_people_invite")
            )
        }
        if (members.isNotEmpty()) {
            SfListCard {
                rows.forEachIndexed { index, row ->
                    if (index > 0) SfDivider()
                    MemberRow(
                        row = row,
                        state = state,
                        onRemove =
                        if (state.isCreator && row.pubkey != state.myPubkey) ({ onRemove(row.pubkey) }) else null
                    )
                }
            }
        }
    }
}

/** Precomputed labels for one member row; [title] is null when the member has no display name. */
internal data class MemberRowModel(
    val pubkey: String,
    val displayName: String?,
    val shortKey: String,
    val title: String?
)

@Composable
private fun MemberRow(row: MemberRowModel, state: GroupDetailUiState, onRemove: (() -> Unit)?) {
    val pubkey = row.pubkey
    val displayName = row.displayName
    val shortKey = row.shortKey
    val title = row.title ?: shortKey
    val isMe = pubkey == state.myPubkey
    val isCreator = pubkey == state.createdBy
    val role =
        when {
            isMe && isCreator -> stringResource(R.string.group_you_creator)
            isCreator -> stringResource(R.string.group_creator)
            isMe -> stringResource(R.string.group_you)
            displayName != null -> shortKey
            else -> null
        }
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = MemberRowMinHeight)
            .padding(start = 14.dp, end = if (onRemove != null) 6.dp else 14.dp, top = 8.dp, bottom = 8.dp)
            .testTag("group_member_$pubkey"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MemberAvatar(pubkey = pubkey, name = displayName, size = 38.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (role != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    role,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onRemove != null) {
            SfIconButton(
                icon = Icons.Outlined.PersonRemove,
                contentDescription = stringResource(R.string.remove_member),
                onClick = onRemove,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("group_remove_$pubkey")
            )
        }
    }
}

/** `abcdefgh…wxyz` for a member without a display name. */
internal fun shortPubkey(pubkey: String): String = if (pubkey.length <=
    SHORT_KEY_HEAD + SHORT_KEY_TAIL
) {
    pubkey
} else {
    "${pubkey.take(SHORT_KEY_HEAD)}…${pubkey.takeLast(SHORT_KEY_TAIL)}"
}
