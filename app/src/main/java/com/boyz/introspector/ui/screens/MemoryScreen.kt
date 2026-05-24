package com.boyz.introspector.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boyz.introspector.data.model.MemoryAddress
import com.boyz.introspector.ui.viewmodel.ScanState
import com.boyz.introspector.ui.viewmodel.SessionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    packageName: String,
    session: SessionViewModel,
    onBack: () -> Unit
) {
    val attachedSession by session.session.collectAsStateWithLifecycle()
    val scanState by session.scanState.collectAsStateWithLifecycle()

    var searchValue by remember { mutableStateOf("") }
    var selectedAddress by remember { mutableStateOf<MemoryAddress?>(null) }

    if (selectedAddress != null) {
        WriteDialog(
            address = selectedAddress!!.hex,
            onDismiss = { selectedAddress = null },
            onWrite = { value ->
                session.write(selectedAddress!!.hex, value)
                selectedAddress = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Memory Engine")
                        attachedSession?.let {
                            Text("${it.appName}  •  PID ${it.pid}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        // Guard: no active session
        if (attachedSession == null && scanState !is ScanState.Attaching && scanState !is ScanState.Error) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("No process attached", style = MaterialTheme.typography.titleMedium)
                    Text("Go back and tap Attach", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onBack) { Text("Go back") }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            val valueInt = searchValue.toIntOrNull()
            val busy = scanState is ScanState.Scanning || scanState is ScanState.Attaching
            val hasResults = scanState is ScanState.Results

            OutlinedTextField(
                value = searchValue,
                onValueChange = { searchValue = it },
                label = { Text("Value to find") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = {
                    valueInt?.let { session.firstScan(it) }
                }),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { valueInt?.let { session.firstScan(it) } },
                    modifier = Modifier.weight(1f),
                    enabled = valueInt != null && !busy
                ) {
                    Text(if (hasResults) "New Scan" else "First Scan")
                }
                if (hasResults) {
                    Button(
                        onClick = { valueInt?.let { session.nextScan(it) } },
                        modifier = Modifier.weight(1f),
                        enabled = valueInt != null && !busy
                    ) {
                        Text("Next Scan")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            when (val state = scanState) {
                is ScanState.Attaching -> {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text("Attaching to process…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                is ScanState.Scanning -> {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text("Scanning memory…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                is ScanState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(state.message, modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }

                is ScanState.Results -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${state.addresses.size} address(es)",
                            style = MaterialTheme.typography.labelMedium)
                        SuggestionChip(onClick = {}, label = { Text("Round ${state.round}") })
                    }

                    if (state.round == 1) {
                        Text(
                            "Change the value in the target app, type the new value, then tap Next Scan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    LazyColumn {
                        items(state.addresses, key = { it.hex }) { addr ->
                            AddressRow(addr = addr, onClick = { selectedAddress = addr })
                            HorizontalDivider()
                        }
                    }
                }

                is ScanState.Idle -> {
                    Text("Enter a value and tap First Scan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AddressRow(addr: MemoryAddress, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(addr.hex, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium)
        Text("${addr.currentValue}", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun WriteDialog(address: String, onDismiss: () -> Unit, onWrite: (Int) -> Unit) {
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
                onClick = { input.toIntOrNull()?.let { onWrite(it) } },
                enabled = input.toIntOrNull() != null
            ) { Text("Write") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
