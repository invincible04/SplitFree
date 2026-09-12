package com.splitfree.ui.screens.group

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.components.HintCard
import com.splitfree.ui.components.MiniLabel
import com.splitfree.ui.components.RelayCheckStatus
import com.splitfree.ui.components.RelayEditor
import com.splitfree.ui.components.RelayInfo
import com.splitfree.ui.components.SettingsChevron
import com.splitfree.ui.components.SettingsRow
import com.splitfree.ui.components.SfAccentButton
import com.splitfree.ui.components.SfBottomDock
import com.splitfree.ui.components.SfDivider
import com.splitfree.ui.components.SfTopBar
import com.splitfree.ui.theme.SfMotion
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.util.asString
import com.splitfree.ui.viewmodels.CreateGroupViewModel

private const val CHEVRON_EXPANDED_DEGREES = 90f

/** Relay list, statuses and callbacks the expandable relay section needs; kept together to keep the API short. */
data class CreateGroupRelays(
    val relays: List<String>,
    val statuses: Map<String, RelayCheckStatus> = emptyMap(),
    val info: Map<String, RelayInfo> = emptyMap(),
    val onAdd: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onCheck: (String) -> Unit = {}
)

/**
 * Create-group route: owns the draft name and the relay-section toggle, connects [CreateGroupViewModel] and
 * renders [CreateGroupContent]. ViewModel errors are shown inline under the form (not as a snackbar).
 */
@Composable
fun CreateGroupScreen(
    onGroupCreated: (String) -> Unit,
    onBack: () -> Unit = {},
    viewModel: CreateGroupViewModel = hiltViewModel()
) {
    var name by rememberSaveable { mutableStateOf("") }
    var showRelays by rememberSaveable { mutableStateOf(false) }
    val relays by viewModel.relays.collectAsStateWithLifecycle()
    val relayStatuses by viewModel.relayStatuses.collectAsStateWithLifecycle()
    val relayInfo by viewModel.relayInfo.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()

    LaunchedEffect(showRelays) { if (showRelays) viewModel.checkAllRelays() }

    CreateGroupContent(
        name = name,
        onName = {
            name = it
            if (error != null) viewModel.clearError()
        },
        relays =
        CreateGroupRelays(
            relays = relays,
            statuses = relayStatuses,
            info = relayInfo,
            onAdd = viewModel::addRelay,
            onRemove = viewModel::removeRelay,
            onCheck = viewModel::checkRelay
        ),
        showRelays = showRelays,
        onToggleRelays = { showRelays = !showRelays },
        error = error?.asString(),
        isCreating = isCreating,
        onCreate = { viewModel.createGroup(name) { onGroupCreated(it) } },
        onBack = onBack
    )
}

/**
 * Stateless create-group form: intro copy, the name field, a collapsible
 * "Sync relays" row framed by hairlines, the encryption hint, an inline error and the citron dock button.
 */
@Composable
internal fun CreateGroupContent(
    name: String,
    onName: (String) -> Unit,
    relays: CreateGroupRelays,
    showRelays: Boolean,
    onToggleRelays: () -> Unit,
    error: String?,
    isCreating: Boolean,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = adaptiveSizeTokens()
    val horizontal = tokens.screenPaddingHorizontal

    Scaffold(
        modifier = modifier,
        topBar = { SfTopBar(title = stringResource(R.string.new_group), onBack = onBack, backEnabled = !isCreating) },
        bottomBar = {
            SfBottomDock {
                SfAccentButton(
                    text = stringResource(R.string.create_group),
                    onClick = onCreate,
                    enabled = name.isNotBlank() && !isCreating,
                    loading = isCreating,
                    modifier = Modifier.testTag("create_group_submit")
                )
            }
        }
    ) { padding ->
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(start = horizontal, end = horizontal, bottom = padding.calculateBottomPadding() + 12.dp)
                .testTag("create_group_form")
        ) {
            FormIntro()

            MiniLabel(stringResource(R.string.group_name))
            Spacer(Modifier.height(7.dp))
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                modifier = Modifier.fillMaxWidth().testTag("create_group_name"),
                placeholder = {
                    Text(
                        stringResource(R.string.group_name_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                singleLine = true,
                enabled = !isCreating,
                isError = error != null,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions =
                KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                shape = MaterialTheme.shapes.medium,
                colors =
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    errorContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            Spacer(Modifier.height(18.dp))
            RelaySection(relays = relays, expanded = showRelays, onToggle = onToggleRelays)

            Spacer(Modifier.height(18.dp))
            HintCard(text = stringResource(R.string.encrypted_on_device_hint), icon = Icons.Outlined.Shield)

            if (error != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("create_group_error")
                )
            }
        }
    }
}

/** Eyebrow, two-line headline and a muted sentence. */
@Composable
private fun FormIntro() {
    Column(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 27.dp)) {
        MiniLabel(stringResource(R.string.create_group_eyebrow))
        Spacer(Modifier.height(5.dp))
        Text(
            stringResource(R.string.create_group_headline),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.create_group_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * "Sync relays" toggle row between two hairlines, expanding into the relay hint and
 * [RelayEditor]. The chevron turns 90° while open and the row reports its expanded state to TalkBack.
 */
@Composable
private fun RelaySection(relays: CreateGroupRelays, expanded: Boolean, onToggle: () -> Unit) {
    val isDefault = relays.relays == RelayDefaults.DEFAULT_RELAYS
    val subtitle =
        if (isDefault) {
            stringResource(R.string.relay_default_configuration)
        } else {
            pluralStringResource(R.plurals.relays_count, relays.relays.size, relays.relays.size)
        }
    val expandedState = stringResource(if (expanded) R.string.cd_expanded else R.string.cd_collapsed)
    val rotation by animateFloatAsState(
        targetValue = if (expanded) CHEVRON_EXPANDED_DEGREES else 0f,
        animationSpec = tween(SfMotion.Base, easing = SfMotion.Ease),
        label = "relayChevron"
    )

    Column(Modifier.fillMaxWidth()) {
        SfDivider()
        SettingsRow(
            icon = Icons.Outlined.CellTower,
            title = stringResource(R.string.sync_relays),
            subtitle = subtitle,
            onClick = onToggle,
            modifier = Modifier.semantics { stateDescription = expandedState }.testTag("create_group_relays_toggle"),
            trailing = { SettingsChevron(modifier = Modifier.rotate(rotation)) }
        )
        SfDivider()
        AnimatedVisibility(
            visible = expanded,
            enter =
            fadeIn(tween(SfMotion.Base, easing = SfMotion.Ease)) +
                expandVertically(tween(SfMotion.Base, easing = SfMotion.Ease)),
            exit =
            fadeOut(tween(SfMotion.Fast, easing = SfMotion.Ease)) +
                shrinkVertically(tween(SfMotion.Base, easing = SfMotion.Ease))
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("create_group_relays_panel")) {
                Text(
                    stringResource(R.string.relay_section_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                RelayEditor(
                    relays = relays.relays,
                    relayStatuses = relays.statuses,
                    relayInfo = relays.info,
                    onAdd = relays.onAdd,
                    onRemove = relays.onRemove,
                    onCheck = relays.onCheck
                )
            }
        }
    }
}
