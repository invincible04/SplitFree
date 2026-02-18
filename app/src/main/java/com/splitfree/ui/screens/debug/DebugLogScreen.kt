package com.splitfree.ui.screens.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splitfree.util.DebugLog
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var filterTag by remember { mutableStateOf("") }
    var showFilter by remember { mutableStateOf(false) }

    // Poll for new entries every 500ms
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
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
    // Auto-scroll to bottom on new entries
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Logs (${entries.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showFilter = !showFilter }) {
                        Icon(Icons.Outlined.FilterList, "Filter")
                    }
                    IconButton(onClick = {
                        val text = entries.joinToString("\n") { it.format() }
                        val clip = ClipData.newPlainText("debug_logs", text)
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(clip)
                        Toast.makeText(context, "Copied ${entries.size} log lines", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.ContentCopy, "Copy All")
                    }
                    IconButton(onClick = { DebugLog.clear() }) {
                        Icon(Icons.Outlined.Delete, "Clear")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (showFilter) {
                FilterBar(tags, filterTag) { filterTag = it }
            }
            LazyColumn(
                state = listState,
                modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(entries.size, key = { it }) { index ->
                    LogLine(entries[index])
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(tags: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FilterChip(
            selected = selected.isBlank(),
            onClick = { onSelect("") },
            label = { Text("All", style = MaterialTheme.typography.labelSmall) }
        )
        tags.forEach { tag ->
            FilterChip(
                selected = selected == tag,
                onClick = { onSelect(if (selected == tag) "" else tag) },
                label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@Composable
private fun LogLine(entry: DebugLog.Entry) {
    val color =
        when (entry.level) {
            'E' -> Color(0xFFFF6B6B)
            'W' -> Color(0xFFFFD93D)
            'I' -> Color(0xFF6BCB77)
            'D' -> Color(0xFF8B8B8B)
            else -> Color(0xFFCCCCCC)
        }
    Text(
        text = entry.format(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = color,
        modifier =
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 1.dp)
    )
}
