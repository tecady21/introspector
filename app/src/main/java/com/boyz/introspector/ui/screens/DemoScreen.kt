package com.boyz.introspector.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boyz.introspector.ui.viewmodel.DemoUiState
import com.boyz.introspector.ui.viewmodel.DemoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(
    onBack: () -> Unit,
    vm: DemoViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val currentValue by vm.currentValue.collectAsStateWithLifecycle()
    var patchAddress by remember { mutableStateOf<String?>(null) }

    if (patchAddress != null) {
        PatchDialog(
            address = patchAddress!!,
            onDismiss = { patchAddress = null },
            onPatch = { v ->
                vm.patchAddress(patchAddress!!, v)
                patchAddress = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory Demo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.reset() }, enabled = uiState !is DemoUiState.Scanning) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Current value card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val label = when (val s = uiState) {
                        is DemoUiState.Candidates -> "New value — scan again (round ${s.round + 1})"
                        is DemoUiState.Found -> "Value in memory (patched)"
                        else -> "Scan for this value"
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$currentValue",
                        fontSize = 56.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text("PID: ${vm.pid}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
                }
            }

            // State-specific content
            when (val state = uiState) {
                is DemoUiState.Idle -> {
                    Text(
                        "Tap Scan to search all writable memory for this value.\n" +
                        "After each scan the value regenerates automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is DemoUiState.Scanning -> {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text("Scanning memory…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                is DemoUiState.Candidates -> {
                    CandidatesPanel(state = state)
                }

                is DemoUiState.Found -> {
                    FoundPanel(address = state.address, onPatch = { patchAddress = state.address })
                }

                is DemoUiState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(state.message, modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Scan button — hidden when found or error
            if (uiState !is DemoUiState.Found) {
                val scanLabel = when (val s = uiState) {
                    is DemoUiState.Candidates -> "Scan for $currentValue (round ${s.round + 1})"
                    else -> "Scan for $currentValue"
                }
                Button(
                    onClick = { vm.scan() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState !is DemoUiState.Scanning
                ) {
                    Text(scanLabel)
                }
            }

            OutlinedButton(
                onClick = { vm.reset() },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                enabled = uiState !is DemoUiState.Scanning
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset (new random value)")
            }
        }
    }
}

@Composable
private fun CandidatesPanel(state: DemoUiState.Candidates) {
    val show = state.addresses.take(8)
    val extra = state.addresses.size - show.size

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Round ${state.round}: ${state.addresses.size} candidates",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "Value ${state.lastValue} found at ${state.addresses.size} addresses. " +
                "Value regenerated — scan the new value to narrow down.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            show.forEach { addr ->
                Text(
                    addr,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (extra > 0) {
                Text("… and $extra more", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FoundPanel(address: String, onPatch: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Address found!", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(address, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                textAlign = TextAlign.Center)
            Button(onClick = onPatch) { Text("Patch value") }
        }
    }
}

@Composable
private fun PatchDialog(address: String, onDismiss: () -> Unit, onPatch: (Int) -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Patch Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(address, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("New value") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { input.toIntOrNull()?.let { onPatch(it) } },
                enabled = input.toIntOrNull() != null
            ) { Text("Write") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
