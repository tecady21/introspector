package com.boyz.introspector.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boyz.introspector.data.model.InstalledApp
import com.boyz.introspector.ui.viewmodel.AppViewModel
import com.boyz.introspector.ui.viewmodel.SessionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    session: SessionViewModel,
    onAppClick: (packageName: String) -> Unit,
    onDemoClick: () -> Unit,
    vm: AppViewModel = viewModel()
) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val showAllApps by vm.showAllApps.collectAsStateWithLifecycle()
    val isRooted by session.isRooted.collectAsStateWithLifecycle()
    val attachedSession by session.session.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Introspector") },
                actions = {
                    if (attachedSession != null) {
                        Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                            Text(
                                "● ${attachedSession!!.appName}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    // Root badge — only shown after a root check has been attempted
                    isRooted?.let { rooted ->
                        Badge(
                            containerColor = if (rooted)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        ) {
                            Text(
                                text = if (rooted) "ROOTED" else "NO ROOT",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    // ⋮ overflow menu
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Show all apps") },
                                onClick = { vm.toggleShowAllApps(); menuExpanded = false },
                                leadingIcon = {
                                    Checkbox(
                                        checked = showAllApps,
                                        onCheckedChange = null
                                    )
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (apps.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding()
                )
            ) {
                item {
                    Card(
                        onClick = onDemoClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Column {
                                Text("Memory Demo", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text("Scan & patch a live value in this app", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                    HorizontalDivider()
                }
                items(apps, key = { it.packageName }) { app ->
                    AppListItem(app = app, onClick = { onAppClick(app.packageName) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AppListItem(app: InstalledApp, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(app.packageName) {
        try {
            val drawable = context.packageManager.getApplicationIcon(app.packageName)
            if (drawable is BitmapDrawable) {
                drawable.bitmap.asImageBitmap()
            } else {
                val bmp = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp.asImageBitmap()
            }
        } catch (e: Exception) { null }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
        } else {
            Icon(
                Icons.Default.Android,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
