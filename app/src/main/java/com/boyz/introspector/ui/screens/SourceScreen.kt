package com.boyz.introspector.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boyz.introspector.data.repository.AppRepository
import com.boyz.introspector.data.repository.ClassInfo
import com.boyz.introspector.data.repository.ResourceCategory
import com.boyz.introspector.data.repository.ResourceInfo
import com.boyz.introspector.ui.viewmodel.SourceUiState
import com.boyz.introspector.ui.viewmodel.SourceViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(
    packageName: String,
    onBack: () -> Unit
) {
    // Activity-scoped ViewModel: survives back navigation so the user never
    // has to wait for JADX again when returning to a previously-opened package.
    val context  = LocalContext.current
    val activity = context as ComponentActivity
    val vm: SourceViewModel = viewModel(viewModelStoreOwner = activity)
    val appRepo  = remember { AppRepository(context) }
    val uiState  by vm.uiState.collectAsStateWithLifecycle()
    val snackbar  = remember { SnackbarHostState() }

    val appName = remember(packageName) {
        try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) { packageName }
    }

    LaunchedEffect(packageName) {
        vm.load(packageName, appRepo.getSourceFiles(packageName))
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope       = rememberCoroutineScope()

    val isViewingSource = uiState is SourceUiState.ViewingClass ||
                          uiState is SourceUiState.ViewingResource
    var searchVisible by remember { mutableStateOf(false) }

    // ── Derived state ──────────────────────────────────────────────────────────
    val classList: List<ClassInfo>? = when (val s = uiState) {
        is SourceUiState.ClassList       -> s.all
        is SourceUiState.ViewingClass    -> s.classList
        is SourceUiState.ViewingResource -> s.classList
        else                             -> null
    }
    val resourceList: List<ResourceInfo>? = when (val s = uiState) {
        is SourceUiState.ClassList       -> s.resources
        is SourceUiState.ViewingClass    -> s.resources
        is SourceUiState.ViewingResource -> s.resources
        else                             -> null
    }
    val drawerAvailable = classList != null || resourceList != null

    val selectedKey: String? = when (val s = uiState) {
        is SourceUiState.ViewingClass    -> "cls:${s.info.fullName}"
        is SourceUiState.ViewingResource -> "res-file:${s.resource.apkPath}"
        else                             -> null
    }

    // ── Auto-reveal: expand ancestor nodes when a file is opened ──────────────
    LaunchedEffect(selectedKey) {
        val key = selectedKey ?: return@LaunchedEffect
        val keysToOpen: Set<String> = when {
            key.startsWith("cls:") -> {
                val fullName = key.removePrefix("cls:")
                val dotParts = fullName.split(".")
                dotParts.dropLast(1)
                    .runningFold("") { acc, seg -> if (acc.isEmpty()) seg else "$acc.$seg" }
                    .filter { it.isNotEmpty() }
                    .map { "pkg:$it" }
                    .toSet()
            }
            key.startsWith("res-file:") -> {
                val apkPath = key.removePrefix("res-file:")
                apkPath.split("/").dropLast(1)
                    .runningFold("") { acc, seg -> if (acc.isEmpty()) seg else "$acc/$seg" }
                    .filter { it.isNotEmpty() }
                    .map { "res:$it" }
                    .toSet()
            }
            else -> emptySet()
        }
        vm.expandDrawerPath(keysToOpen)
    }

    // Auto-open drawer on ClassList; auto-navigate to AndroidManifest once
    var hasAutoNavigated by remember(packageName) { mutableStateOf(false) }
    LaunchedEffect(uiState) {
        if (uiState is SourceUiState.ClassList) {
            drawerState.open()
            if (!hasAutoNavigated) {
                hasAutoNavigated = true
                val manifest = (uiState as SourceUiState.ClassList).resources
                    .find { it.category == ResourceCategory.MANIFEST }
                if (manifest != null) vm.selectResource(manifest)
            }
        }
    }

    LaunchedEffect(isViewingSource) {
        if (!isViewingSource) searchVisible = false
    }

    BackHandler(enabled = isViewingSource) {
        vm.backToList()
        scope.launch { drawerState.open() }
    }

    val searchQuery  = when (val s = uiState) {
        is SourceUiState.ViewingClass    -> s.searchQuery
        is SourceUiState.ViewingResource -> s.searchQuery
        else                             -> ""
    }
    val matchLines   = when (val s = uiState) {
        is SourceUiState.ViewingClass    -> s.matchLines
        is SourceUiState.ViewingResource -> s.matchLines
        else                             -> emptyList()
    }
    val currentMatch = when (val s = uiState) {
        is SourceUiState.ViewingClass    -> s.currentMatch
        is SourceUiState.ViewingResource -> s.currentMatch
        else                             -> -1
    }

    val title = when (val s = uiState) {
        is SourceUiState.ViewingClass    -> s.info.simpleName
        is SourceUiState.ViewingResource -> s.resource.name
        else                             -> "Source Viewer"
    }

    // RTL wrapper makes ModalNavigationDrawer open from the right edge.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    ModalNavigationDrawer(
        drawerState     = drawerState,
        gesturesEnabled = drawerAvailable,
        drawerContent   = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                ModalDrawerSheet {
                    if (drawerAvailable) {
                        ContentTreeDrawer(
                            appName            = appName,
                            packageName        = packageName,
                            classes            = classList ?: emptyList(),
                            resources          = resourceList ?: emptyList(),
                            expanded           = vm.drawerExpanded,
                            onToggle           = vm::toggleDrawer,
                            selectedKey        = selectedKey,
                            scope              = scope,
                            drawerState        = drawerState,
                            onClassSelected    = { cls -> vm.selectClass(cls) },
                            onResourceSelected = { res -> vm.selectResource(res) }
                        )
                    }
                }
            }
        }
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                packageName,
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isViewingSource) {
                                vm.backToList()
                                scope.launch { drawerState.open() }
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (drawerAvailable) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Browse files")
                            }
                        }
                        if (isViewingSource) {
                            IconButton(onClick = { searchVisible = !searchVisible }) {
                                Icon(Icons.Default.Search, contentDescription = "Search in code")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {

                AnimatedVisibility(visible = searchVisible && isViewingSource) {
                    CodeSearchBar(
                        query         = searchQuery,
                        matchCount    = matchLines.size,
                        currentMatch  = currentMatch,
                        onQueryChange = vm::search,
                        onPrev        = vm::prevMatch,
                        onNext        = vm::nextMatch
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {

                    when (val state = uiState) {
                        is SourceUiState.Loading -> {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    text  = state.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        is SourceUiState.Error -> {
                            Text(
                                state.message,
                                modifier = Modifier.padding(16.dp),
                                color    = MaterialTheme.colorScheme.error
                            )
                        }
                        is SourceUiState.ClassList -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                )
                                Text(
                                    "← Open the panel to browse files",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                )
                            }
                        }
                        is SourceUiState.ViewingClass -> CodeView(
                            code              = state.code,
                            isXml             = false,
                            searchQuery       = state.searchQuery,
                            matchLines        = state.matchLines,
                            currentMatch      = state.currentMatch,
                            classList         = state.classList,
                            packageName       = packageName,
                            onNavigateToClass = { cls -> vm.selectClass(cls) },
                            onSearch          = { query ->
                                searchVisible = true
                                vm.search(query)
                            },
                            onFindAll         = { word -> vm.findAllOccurrences(word) },
                            onRenameSymbol    = vm::renameSymbol
                        )
                        is SourceUiState.ViewingResource -> CodeView(
                            code              = state.content,
                            isXml             = state.resource.category == ResourceCategory.MANIFEST ||
                                               state.resource.category == ResourceCategory.RES_XML,
                            searchQuery       = state.searchQuery,
                            matchLines        = state.matchLines,
                            currentMatch      = state.currentMatch,
                            classList         = state.classList,
                            packageName       = packageName,
                            onNavigateToClass = { cls -> vm.selectClass(cls) },
                            onSearch          = { query ->
                                searchVisible = true
                                vm.search(query)
                            },
                            onFindAll         = { word -> vm.findAllOccurrences(word) },
                            onRenameSymbol    = vm::renameSymbol
                        )
                        else -> {}
                    }
                }

                // ── Find-all occurrences modal (Dialog overlay) ─────────────
                vm.findAllState?.let { state ->
                    FindAllModal(
                        state                = state,
                        onFilter             = vm::filterFindAll,
                        onNavigateToClass    = { cls -> vm.dismissFindAll(); vm.selectClass(cls) },
                        onNavigateToResource = { res -> vm.dismissFindAll(); vm.selectResource(res) },
                        onDismiss            = vm::dismissFindAll
                    )
                }
            }
        }
        } // CompositionLocalProvider(Ltr) — Scaffold
    }
    } // CompositionLocalProvider(Rtl) — drawer slides from right
}
