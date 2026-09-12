package com.splitfree.ui.screens.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.ui.components.EmptyState
import com.splitfree.ui.components.SfCard
import com.splitfree.ui.components.SfIconButton
import com.splitfree.ui.components.SfTopBar
import com.splitfree.ui.theme.splitFree
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.util.DebugLog
import kotlinx.coroutines.delay

private const val POLL_INTERVAL_MS = 500L
private val LogPadding = 14.dp
private val ChipGap = 8.dp

/**
 * Live view of the in-app [DebugLog] buffer (debug builds only). No ViewModel: the ring buffer is polled every
 * [POLL_INTERVAL_MS] through [DebugLog.revision]. Filter, copy and clear live in the top bar; the filter chips
 * unfold beneath it; the lines sit monospace on a card frame and scroll under the navigation bar.
 */
@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tokens = adaptiveSizeTokens()
    var filterTag by rememberSaveable { mutableStateOf("") }
    var showFilter by rememberSaveable { mutableStateOf(false) }

    // Poll for new entries; the buffer has no flow of its own.
    var tick by remember { mutableLongStateOf(DebugLog.revision) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(POLL_INTERVAL_MS)
            tick = DebugLog.revision
        }
    }

    val allEntries = remember(tick) { DebugLog.entries }
    val entries =
        remember(allEntries, filterTag) {
            if (filterTag.isBlank()) {
                allEntries
            } else {
                allEntries.filter { it.tag.contains(filterTag, ignoreCase = true) }
            }
        }
    val tags = remember(allEntries) { allEntries.map { it.tag }.distinct().sorted() }

    val listState = rememberLazyListState()
    // Follow the newest line as entries arrive.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    val filterActive = showFilter || filterTag.isNotBlank()
    val copiedMsg = pluralStringResource(R.plurals.copied_log_lines, entries.size, entries.size)
    Scaffold(
        topBar = {
            SfTopBar(
                title = stringResource(R.string.debug_logs_title, entries.size),
                onBack = onBack,
                actions = {
                    SfIconButton(
                        icon = Icons.Outlined.FilterList,
                        contentDescription = stringResource(R.string.filter),
                        onClick = { showFilter = !showFilter },
                        modifier = Modifier.testTag("debug_filter"),
                        tint =
                        if (filterActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    SfIconButton(
                        icon = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.copy_all),
                        onClick = {
                            copyLines(context, entries)
                            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("debug_copy")
                    )
                    SfIconButton(
                        icon = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.clear),
                        onClick = { DebugLog.clear() },
                        modifier = Modifier.testTag("debug_clear")
                    )
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .padding(horizontal = tokens.screenPaddingHorizontal)
        ) {
            if (showFilter) {
                FilterBar(tags = tags, selected = filterTag, onSelect = { filterTag = it })
            }
            SfCard(modifier = Modifier.fillMaxWidth().weight(1f).testTag("debug_log_frame")) {
                if (entries.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.Terminal,
                        title = stringResource(R.string.debug_empty_title),
                        body = stringResource(R.string.debug_empty_body),
                        modifier = Modifier.testTag("debug_empty")
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("debug_log_list"),
                        contentPadding =
                        PaddingValues(
                            start = LogPadding,
                            end = LogPadding,
                            top = LogPadding,
                            bottom = LogPadding + padding.calculateBottomPadding()
                        )
                    ) {
                        items(entries, key = { it.seq }) { entry -> LogLine(entry) }
                    }
                }
            }
        }
    }
}

/** Tag chips in theme colours (`FilterChip` on the card fill; the selected one takes the brand wash). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(tags: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag("debug_filter_bar"),
        horizontalArrangement = Arrangement.spacedBy(ChipGap),
        verticalArrangement = Arrangement.spacedBy(ChipGap)
    ) {
        TagChip(label = stringResource(R.string.all), selected = selected.isBlank(), onClick = { onSelect("") })
        tags.forEach { tag ->
            TagChip(
                label = tag,
                selected = selected == tag,
                onClick = { onSelect(if (selected == tag) "" else tag) },
                modifier = Modifier.testTag("debug_tag_$tag")
            )
        }
    }
}

@Composable
private fun TagChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        colors =
        FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        border =
        FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = Color.Transparent
        )
    )
}

/** One monospace line coloured by level; long lines scroll sideways instead of wrapping the timestamp. */
@Composable
private fun LogLine(entry: DebugLog.Entry) {
    val palette = MaterialTheme.splitFree
    val color =
        when (entry.level) {
            'E' -> MaterialTheme.colorScheme.error
            'W' -> palette.warning
            'I' -> palette.positive
            'D' -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }
    Text(
        text = entry.format(),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        maxLines = 1,
        modifier =
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp)
            .testTag("debug_line_${entry.seq}")
    )
}

private fun copyLines(context: Context, entries: List<DebugLog.Entry>) {
    val text = entries.joinToString("\n") { it.format() }
    val clip = ClipData.newPlainText("debug_logs", text)
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}
