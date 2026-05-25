package com.boyz.introspector.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.boyz.introspector.data.repository.ClassInfo
import com.boyz.introspector.data.repository.ResourceInfo
import com.boyz.introspector.ui.viewmodel.FindAllState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FindAllModal(
    state: FindAllState,
    onFilter: (String) -> Unit,
    onNavigateToClass: (ClassInfo) -> Unit,
    onNavigateToResource: (ResourceInfo) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color    = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Top app bar ──────────────────────────────────────────────
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text     = "References to \"${state.query}\"",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!state.isLoading) {
                                val total = state.results.size
                                Text(
                                    text  = if (total == 0) "No results" else "$total result${if (total == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                        }
                    }
                )

                // ── Filter field ─────────────────────────────────────────────
                OutlinedTextField(
                    value         = state.filter,
                    onValueChange = onFilter,
                    modifier      = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    placeholder   = { Text("Filter results…") },
                    leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine    = true,
                    textStyle     = MaterialTheme.typography.bodyMedium
                )

                // ── Progress indicator while searching ───────────────────────
                if (state.isLoading) {
                    Column(
                        modifier            = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Searching…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ── Results list ─────────────────────────────────────────────
                val filterLower = state.filter.lowercase()
                val filtered = remember(state.results, state.filter) {
                    if (state.filter.isBlank()) state.results
                    else state.results.filter { r ->
                        r.displayName.lowercase().contains(filterLower) ||
                        r.lineText.lowercase().contains(filterLower)
                    }
                }

                // Group consecutive results by displayName
                val grouped = remember(filtered) {
                    filtered.groupBy { it.displayName }
                }

                if (!state.isLoading && filtered.isEmpty() && state.filter.isBlank() && state.results.isEmpty()) {
                    // Show "no occurrences" only once loading is done and nothing came back
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No occurrences found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (!state.isLoading && filtered.isEmpty() && state.filter.isNotBlank()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "0 matches for \"${state.filter}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        grouped.forEach { (displayName, hits) ->
                            // ── Section header ─────────────────────────────
                            item(key = "hdr:$displayName") {
                                Column {
                                    HorizontalDivider()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text     = displayName,
                                            style    = MaterialTheme.typography.labelMedium,
                                            color    = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text  = "${hits.size} hit${if (hits.size == 1) "" else "s"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // ── Result rows ────────────────────────────────
                            items(
                                items = hits,
                                key   = { r -> "${r.displayName}:${r.lineNumber}:${r.lineText.hashCode()}" }
                            ) { result ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            result.classInfo?.let    { onNavigateToClass(it) }
                                            result.resourceInfo?.let { onNavigateToResource(it) }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text       = "%5d".format(result.lineNumber),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize   = 12.sp,
                                        color      = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier   = Modifier.width(42.dp)
                                    )
                                    Text(
                                        text     = "│",
                                        color    = MaterialTheme.colorScheme.outlineVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    )
                                    Text(
                                        text       = result.lineText,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize   = 12.sp,
                                        maxLines   = 1,
                                        overflow   = TextOverflow.Ellipsis,
                                        modifier   = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
