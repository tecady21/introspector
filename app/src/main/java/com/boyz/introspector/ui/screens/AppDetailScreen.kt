package com.boyz.introspector.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boyz.introspector.data.repository.AppRepository
import com.boyz.introspector.ui.viewmodel.ScanState
import com.boyz.introspector.ui.viewmodel.SessionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    packageName: String,
    session: SessionViewModel,
    onMemoryClick: () -> Unit,
    onSourceClick: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appRepo = remember { AppRepository(context) }
    val appName = remember(packageName) { appRepo.getAppName(packageName) }

    val attachedSession by session.session.collectAsStateWithLifecycle()
    val scanState by session.scanState.collectAsStateWithLifecycle()

    val isThisAppAttached = attachedSession?.packageName == packageName
    val isAttaching = scanState is ScanState.Attaching

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(appName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(packageName, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(4.dp))

            // ── Attach card ──────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isThisAppAttached)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (isThisAppAttached) "Attached" else "Not attached",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (isThisAppAttached)
                                "PID: ${attachedSession!!.pid}"
                            else
                                "Attach to inspect live memory",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isThisAppAttached)
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isAttaching) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else if (isThisAppAttached) {
                        FilledTonalButton(onClick = { session.detach() }) {
                            Icon(Icons.Default.LinkOff, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Detach")
                        }
                    } else {
                        Button(onClick = { session.attach(packageName) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Attach")
                        }
                    }
                }

                // Show error inline if it happened during attach for this app
                if (!isThisAppAttached && scanState is ScanState.Error && !isAttaching) {
                    Text(
                        (scanState as ScanState.Error).message,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ── Feature cards ─────────────────────────────────────────────────
            FeatureCard(
                icon = { Icon(Icons.Default.Memory, contentDescription = null) },
                title = "Memory Engine",
                description = if (isThisAppAttached)
                    "Scan and patch live memory — ${
                        when (val s = scanState) {
                            is ScanState.Results -> "round ${s.round}, ${s.addresses.size} candidates"
                            else -> "ready"
                        }
                    }"
                else "Attach first to use memory scanning",
                enabled = isThisAppAttached,
                onClick = onMemoryClick
            )

            FeatureCard(
                icon = { Icon(Icons.Default.Code, contentDescription = null) },
                title = "Source Viewer",
                description = "Decompile DEX bytecode with jadx",
                enabled = true,
                onClick = onSourceClick
            )
        }
    }
}

@Composable
private fun FeatureCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.5f))
            }
        }
    }
}
