package com.boyz.introspector.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boyz.introspector.data.repository.AppRepository
import com.boyz.introspector.data.repository.ClassInfo
import com.boyz.introspector.data.repository.ResourceCategory
import com.boyz.introspector.data.repository.ResourceInfo
import com.boyz.introspector.ui.viewmodel.SourceUiState
import com.boyz.introspector.ui.viewmodel.SourceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ── Resource tree data model ───────────────────────────────────────────────────

private data class ResourceFolderNode(
    val segment: String,
    val fullPath: String,
    val children: List<ResourceFolderNode>,
    val files: List<ResourceInfo>
)

private fun buildResourceTree(resources: List<ResourceInfo>): ResourceFolderNode {
    class MutableFolderNode {
        val children: MutableMap<String, MutableFolderNode> = sortedMapOf()
        val files: MutableList<ResourceInfo> = mutableListOf()
    }

    val root = MutableFolderNode()
    for (res in resources) {
        val parts = res.apkPath.split("/")
        if (parts.size == 1) {
            root.files += res
        } else {
            var node = root
            for (i in 0 until parts.size - 1) {
                node = node.children.getOrPut(parts[i]) { MutableFolderNode() }
            }
            node.files += res
        }
    }

    fun toNode(mNode: MutableFolderNode, path: String): ResourceFolderNode {
        val seg = path.substringAfterLast('/')
        return ResourceFolderNode(
            segment  = seg,
            fullPath = path,
            children = mNode.children.map { (childSeg, child) ->
                toNode(child, if (path.isEmpty()) childSeg else "$path/$childSeg")
            },
            files    = mNode.files
        )
    }

    return ResourceFolderNode(
        segment  = "",
        fullPath = "",
        children = root.children.map { (seg, child) -> toNode(child, seg) },
        files    = root.files
    )
}

// ── Class tree data model ──────────────────────────────────────────────────────

private data class ClassNode(
    val info: ClassInfo,
    val displayName: String,
    val innerClasses: List<ClassNode> = emptyList()
)

private data class PackageNode(
    val segment: String,
    val fullPath: String,
    val subPackages: List<PackageNode>,
    val classes: List<ClassNode>
)

private fun buildClassTree(classes: List<ClassInfo>): List<ClassNode> {
    if (classes.isEmpty()) return emptyList()

    class MutableClassNode {
        var classInfo: ClassInfo? = null
        val children: MutableMap<String, MutableClassNode> = sortedMapOf()
    }

    val root = MutableClassNode()
    for (cls in classes) {
        val segments = cls.simpleName.split("$")
        var node = root
        for (seg in segments) {
            node = node.children.getOrPut(seg) { MutableClassNode() }
        }
        node.classInfo = cls
    }

    fun toNodes(mNode: MutableClassNode, seg: String, pkg: String): ClassNode {
        val info = mNode.classInfo ?: ClassInfo(
            fullName    = if (pkg == "(default)") seg else "$pkg.$seg",
            simpleName  = seg,
            packageName = pkg
        )
        return ClassNode(
            info         = info,
            displayName  = seg,
            innerClasses = mNode.children.map { (childSeg, childNode) -> toNodes(childNode, childSeg, pkg) }
        )
    }

    val pkg = classes.first().packageName
    return root.children.map { (seg, node) -> toNodes(node, seg, pkg) }
}

private fun buildPackageTree(classes: List<ClassInfo>): Pair<List<ClassNode>, List<PackageNode>> {
    class MutableNode {
        val children: MutableMap<String, MutableNode> = sortedMapOf()
        val classes: MutableList<ClassInfo> = mutableListOf()
    }

    val root = MutableNode()
    val defaultClasses = mutableListOf<ClassInfo>()

    for (cls in classes) {
        if (cls.packageName == "(default)") { defaultClasses += cls; continue }
        var node = root
        for (seg in cls.packageName.split(".")) {
            node = node.children.getOrPut(seg) { MutableNode() }
        }
        node.classes += cls
    }

    fun toNodes(node: MutableNode, path: String): List<PackageNode> =
        node.children.map { (seg, child) ->
            val full = if (path.isEmpty()) seg else "$path.$seg"
            PackageNode(
                segment     = seg,
                fullPath    = full,
                subPackages = toNodes(child, full),
                classes     = buildClassTree(child.classes)
            )
        }

    return Pair(buildClassTree(defaultClasses), toNodes(root, ""))
}

// ── Screen ─────────────────────────────────────────────────────────────────────

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

    // Key of the currently-opened item — used to highlight it in the drawer tree
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
                // Build cumulative package paths: "com", "com.example", …
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
                            // ViewModel-backed: persists across back-navigation & reloads
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

                // ── In-code search bar ────────────────────────────────────────
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

                // ── Main content ──────────────────────────────────────────────
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
                            onRenameSymbol    = vm::renameSymbol
                        )
                        else -> {}
                    }
                }
            }
        }
        } // CompositionLocalProvider(Ltr) — Scaffold
    }
    } // CompositionLocalProvider(Rtl) — drawer slides from right
}

// ── Content tree drawer ────────────────────────────────────────────────────────

@Composable
private fun ContentTreeDrawer(
    appName: String,
    packageName: String,
    classes: List<ClassInfo>,
    resources: List<ResourceInfo>,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    selectedKey: String?,
    scope: CoroutineScope,
    drawerState: DrawerState,
    onClassSelected: (ClassInfo) -> Unit,
    onResourceSelected: (ResourceInfo) -> Unit
) {
    val resourceTree            = remember(resources) { buildResourceTree(resources) }
    val (rootClasses, packages) = remember(classes)   { buildPackageTree(classes) }

    val onClassClick: (ClassInfo) -> Unit = { cls ->
        onClassSelected(cls)
        scope.launch { drawerState.close() }
    }
    val onResourceClick: (ResourceInfo) -> Unit = { res ->
        onResourceSelected(res)
        scope.launch { drawerState.close() }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text     = appName,
                style    = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text     = packageName,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxSize()) {
            // ── Top-level resource files (AndroidManifest.xml, etc.) ──────────
            resourceTree.files.forEach { res ->
                item(key = "res-file:${res.apkPath}") {
                    ResourceLeafRow(
                        res        = res,
                        depth      = 0,
                        isSelected = selectedKey == "res-file:${res.apkPath}",
                        onClick    = { onResourceClick(res) }
                    )
                }
            }
            // ── Resource sub-folders (res/, assets/, lib/) ────────────────────
            resourceTree.children.forEach { folder ->
                resourceFolderTree(
                    node            = folder,
                    depth           = 0,
                    expanded        = expanded,
                    onToggle        = onToggle,
                    selectedKey     = selectedKey,
                    onResourceClick = onResourceClick
                )
            }
            // ── "Source Code" section header ──────────────────────────────────
            if (classes.isNotEmpty() || resources.isNotEmpty()) {
                item(key = "section:source-code") {
                    SectionHeader("Source Code  (${classes.size})")
                }
            }
            // ── Root/default-package classes ──────────────────────────────────
            rootClasses.forEach { classNode ->
                classNodeTree(
                    node         = classNode,
                    depth        = 0,
                    expanded     = expanded,
                    onToggle     = onToggle,
                    selectedKey  = selectedKey,
                    onClassClick = onClassClick
                )
            }
            // ── Package tree ──────────────────────────────────────────────────
            packages.forEach { pkg ->
                packageTree(
                    node         = pkg,
                    depth        = 0,
                    expanded     = expanded,
                    onToggle     = onToggle,
                    selectedKey  = selectedKey,
                    onClassClick = onClassClick
                )
            }
        }
    }
}

// ── LazyListScope extensions ───────────────────────────────────────────────────

private fun LazyListScope.resourceFolderTree(
    node: ResourceFolderNode,
    depth: Int,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    selectedKey: String?,
    onResourceClick: (ResourceInfo) -> Unit
) {
    val resKey     = "res:${node.fullPath}"
    val isExpanded = resKey in expanded

    item(key = resKey) {
        ResourceFolderRow(
            node       = node,
            depth      = depth,
            isExpanded = isExpanded,
            onToggle   = { onToggle(resKey) }
        )
    }

    if (isExpanded) {
        node.children.forEach { child ->
            resourceFolderTree(child, depth + 1, expanded, onToggle, selectedKey, onResourceClick)
        }
        node.files.forEach { res ->
            item(key = "res-file:${res.apkPath}") {
                ResourceLeafRow(
                    res        = res,
                    depth      = depth + 1,
                    isSelected = selectedKey == "res-file:${res.apkPath}",
                    onClick    = { onResourceClick(res) }
                )
            }
        }
    }
}

private fun LazyListScope.classNodeTree(
    node: ClassNode,
    depth: Int,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    selectedKey: String?,
    onClassClick: (ClassInfo) -> Unit
) {
    val clsKey     = "cls:${node.info.fullName}"
    val isExpanded = clsKey in expanded

    item(key = clsKey) {
        ClassNodeRow(
            node       = node,
            depth      = depth,
            isExpanded = isExpanded,
            isSelected = selectedKey == clsKey,
            onToggle   = { onToggle(clsKey) },
            onClick    = { onClassClick(node.info) }
        )
    }

    if (isExpanded) {
        node.innerClasses.forEach { inner ->
            classNodeTree(inner, depth + 1, expanded, onToggle, selectedKey, onClassClick)
        }
    }
}

private fun LazyListScope.packageTree(
    node: PackageNode,
    depth: Int,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    selectedKey: String?,
    onClassClick: (ClassInfo) -> Unit
) {
    val pkgKey     = "pkg:${node.fullPath}"
    val isExpanded = pkgKey in expanded

    item(key = pkgKey) {
        PackageRow(
            node       = node,
            depth      = depth,
            isExpanded = isExpanded,
            onToggle   = { onToggle(pkgKey) }
        )
    }

    if (isExpanded) {
        node.subPackages.forEach { child ->
            packageTree(child, depth + 1, expanded, onToggle, selectedKey, onClassClick)
        }
        node.classes.forEach { classNode ->
            classNodeTree(classNode, depth + 1, expanded, onToggle, selectedKey, onClassClick)
        }
    }
}

// ── Row composables ────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Column {
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        Text(
            text     = title,
            style    = MaterialTheme.typography.labelMedium,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
        )
    }
}

@Composable
private fun ResourceFolderRow(
    node: ResourceFolderNode,
    depth: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(
                start  = (12 + depth * 16).dp,
                end    = 8.dp,
                top    = 7.dp,
                bottom = 7.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier           = Modifier.size(18.dp),
            tint               = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text     = node.segment,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ResourceLeafRow(
    res: ResourceInfo,
    depth: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val icon = when (res.category) {
        ResourceCategory.MANIFEST -> Icons.Default.Description
        ResourceCategory.LIB      -> Icons.Default.Storage
        else                      -> Icons.Default.Code
    }
    val iconTint = when (res.category) {
        ResourceCategory.MANIFEST -> MaterialTheme.colorScheme.tertiary
        ResourceCategory.LIB      -> MaterialTheme.colorScheme.onSurfaceVariant
        else                      -> MaterialTheme.colorScheme.primary
    }
    val bgColor  = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                   else Color.Transparent
    val textColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(
                start  = (28 + depth * 16).dp,
                end    = 12.dp,
                top    = 6.dp,
                bottom = 6.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null,
            modifier = Modifier.size(14.dp), tint = iconTint)
        Text(
            text       = res.name,
            style      = MaterialTheme.typography.bodySmall,
            color      = textColor,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PackageRow(
    node: PackageNode,
    depth: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(
                start  = (12 + depth * 16).dp,
                end    = 8.dp,
                top    = 8.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = if (isExpanded) Icons.Default.KeyboardArrowDown
                                 else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier           = Modifier.size(18.dp),
            tint               = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text     = node.segment,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ClassNodeRow(
    node: ClassNode,
    depth: Int,
    isExpanded: Boolean,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val indent   = (12 + depth * 16).dp
    val bgColor  = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                   else Color.Transparent
    val textColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface
    val iconTint  = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(indent + 28.dp)
                .heightIn(min = 36.dp)
                .then(
                    if (node.innerClasses.isNotEmpty()) Modifier.clickable(onClick = onToggle)
                    else Modifier
                ),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (node.innerClasses.isNotEmpty()) {
                Icon(
                    imageVector        = if (isExpanded) Icons.Default.KeyboardArrowDown
                                        else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier           = Modifier.size(18.dp),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 36.dp)
                .clickable(onClick = onClick)
                .padding(end = 12.dp, top = 6.dp, bottom = 6.dp, start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector        = Icons.Default.Code,
                contentDescription = null,
                modifier           = Modifier.size(14.dp),
                tint               = iconTint
            )
            Text(
                text       = node.displayName,
                style      = MaterialTheme.typography.bodySmall,
                color      = textColor,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
        }
    }
}

// ── In-code search bar ─────────────────────────────────────────────────────────

@Composable
private fun CodeSearchBar(
    query: String,
    matchCount: Int,
    currentMatch: Int,
    onQueryChange: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value         = query,
                onValueChange = onQueryChange,
                modifier      = Modifier.weight(1f),
                placeholder   = { Text("Search in code…") },
                singleLine    = true,
                textStyle     = MaterialTheme.typography.bodySmall
            )
            val counterText = when {
                query.isBlank() -> ""
                matchCount == 0 -> "0 results"
                else            -> "${currentMatch + 1}/$matchCount"
            }
            if (counterText.isNotEmpty()) {
                Text(
                    counterText,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 40.dp)
                )
            }
            IconButton(onClick = onPrev, enabled = matchCount > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match",
                    modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onNext, enabled = matchCount > 0) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match",
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── Symbol interaction helpers ─────────────────────────────────────────────────

private data class SymbolMenuState(
    val word: String,
    val lineIndex: Int,
    val matchingClass: ClassInfo?
)

/** Extracts a word (letters/digits/underscores) around [offset] in [text]. */
private fun extractIdentifier(text: String, offset: Int): String {
    if (offset < 0 || offset >= text.length) return ""
    var start = offset
    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
    var end = offset
    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
    return text.substring(start, end)
}

/**
 * Like [extractIdentifier] but also allows dots — used for XML attribute values
 * where class names appear as "com.example.MainActivity".
 */
private fun extractQualifiedName(text: String, offset: Int): String {
    if (offset < 0 || offset >= text.length) return ""
    var start = offset
    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' ||
                          text[start - 1] == '.')) start--
    var end = offset
    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' ||
                                 text[end] == '.')) end++
    return text.substring(start, end).trim('.')
}

/**
 * Resolves a word to a [ClassInfo].  Handles:
 *  - fully-qualified name  (com.example.MainActivity)
 *  - simple name           (MainActivity)
 *  - leading-dot reference (.MainActivity  →  packageName.MainActivity)
 */
private fun resolveClass(
    word: String,
    packageName: String,
    classList: List<ClassInfo>
): ClassInfo? {
    if (word.isBlank()) return null
    // Direct full-name match
    classList.find { it.fullName == word }?.let { return it }
    // Simple name match
    classList.find { it.simpleName == word }?.let { return it }
    // Leading-dot relative reference: .MainActivity → packageName.MainActivity
    if (word.startsWith(".")) {
        val stripped = word.removePrefix(".")
        classList.find { it.fullName == "$packageName.$stripped" }?.let { return it }
        classList.find { it.simpleName == stripped }?.let { return it }
    } else {
        // Unqualified name relative to current package
        classList.find { it.fullName == "$packageName.$word" }?.let { return it }
    }
    return null
}

// ── Interactive code line ──────────────────────────────────────────────────────

private val SYMBOL_GLOW_STYLE = SpanStyle(background = Color.White.copy(alpha = 0.22f))

/**
 * A single line of syntax-highlighted code that:
 *  - Overlays a white-glow highlight on every whole-word occurrence of [selectedWord].
 *  - Fires [onSymbolTap] on a **double-tap** (symbol menu: Go to class / rename / etc.).
 *  - Wraps the text in [SelectionContainer] so a **long-press** triggers Android's
 *    native text-selection UI (drag handles + system Copy action).
 *
 * Gesture design — no conflicts:
 *  The `pointerInput` on the outer [Box] uses [PointerEventPass.Initial] (parent-first,
 *  top-down).  It observes DOWN/UP events to detect double-tap timing WITHOUT ever calling
 *  `.consume()`.  [SelectionContainer] (a child) independently receives the same events
 *  in the Main pass (child-first, bottom-up) and manages long-press selection by itself.
 */
@Composable
private fun InteractiveCodeLine(
    annotatedString: AnnotatedString,
    selectedWord: String?,
    lineIndex: Int,
    isXml: Boolean,
    onSymbolTap: (word: String, lineIndex: Int) -> Unit
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val displayText = remember(annotatedString, selectedWord) {
        if (selectedWord != null && selectedWord.length >= 2) {
            buildAnnotatedString {
                append(annotatedString)
                val raw   = annotatedString.text
                val regex = Regex("\\b${Regex.escape(selectedWord)}\\b")
                for (match in regex.findAll(raw)) {
                    addStyle(SYMBOL_GLOW_STYLE, match.range.first, match.range.last + 1)
                }
            }
        } else {
            annotatedString
        }
    }

    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .pointerInput(annotatedString.text) {
                // Read longPress/doubleTap thresholds here (PointerInputScope), then
                // enter the restricted AwaitPointerEventScope below.
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis

                var lastTapTime = 0L
                var lastTapPos  = Offset.Zero
                var downPos     = Offset.Zero
                var downTime    = 0L
                var pointerDown = false

                awaitPointerEventScope {
                    // Run for the lifetime of the composable, one event at a time.
                    while (true) {
                        // PointerEventPass.Initial = parent-first (top-down).
                        // We receive events BEFORE SelectionContainer (child) does.
                        // We never call change.consume(), so SelectionContainer sees
                        // the same events unmodified in the Main pass.
                        val event  = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue

                        when {
                            // ── Finger down ────────────────────────────────────
                            change.pressed && !change.previousPressed -> {
                                pointerDown = true
                                downPos     = change.position
                                downTime    = System.currentTimeMillis()
                            }
                            // ── Finger up ──────────────────────────────────────
                            !change.pressed && change.previousPressed && pointerDown -> {
                                pointerDown = false
                                val now      = System.currentTimeMillis()
                                val holdMs   = now - downTime

                                // Count as a tap only if the finger lifted quickly
                                // (not a long-press that SelectionContainer is handling).
                                if (holdMs < longPressMs) {
                                    val dx = downPos.x - lastTapPos.x
                                    val dy = downPos.y - lastTapPos.y
                                    if (now - lastTapTime <= doubleTapMs &&
                                        dx * dx + dy * dy < 80f * 80f) {
                                        // ── Double-tap ──────────────────────────
                                        val charIdx = layoutResult
                                            ?.getOffsetForPosition(downPos)
                                        if (charIdx != null) {
                                            val word = if (isXml)
                                                extractQualifiedName(annotatedString.text, charIdx)
                                            else
                                                extractIdentifier(annotatedString.text, charIdx)
                                            if (word.length >= 2) onSymbolTap(word, lineIndex)
                                        }
                                        lastTapTime = 0L
                                    } else {
                                        lastTapTime = now
                                        lastTapPos  = downPos
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        Text(
            text         = displayText,
            fontFamily   = FontFamily.Monospace,
            fontSize     = 13.sp,
            onTextLayout = { layoutResult = it }
        )
    }
}

// ── Code view ──────────────────────────────────────────────────────────────────

@Composable
private fun CodeView(
    code: String,
    isXml: Boolean = false,
    searchQuery: String = "",
    matchLines: List<Int> = emptyList(),
    currentMatch: Int = -1,
    classList: List<ClassInfo> = emptyList(),
    packageName: String = "",
    onNavigateToClass: (ClassInfo) -> Unit = {},
    onSearch: (String) -> Unit = {},
    onRenameSymbol: (from: String, to: String) -> Unit = { _, _ -> }
) {
    val lines        = remember(code) { code.lines() }
    val hScroll      = rememberScrollState()
    val listState    = rememberLazyListState()
    val matchLineSet = remember(matchLines) { matchLines.toHashSet() }

    // ── Symbol menu state ──────────────────────────────────────────────────────
    var symbolMenu       by remember { mutableStateOf<SymbolMenuState?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput      by remember { mutableStateOf("") }
    var renameOriginal   by remember { mutableStateOf("") }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    // ── Rename dialog ──────────────────────────────────────────────────────────
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title   = { Text("Rename symbol") },
            text    = {
                OutlinedTextField(
                    value         = renameInput,
                    onValueChange = { renameInput = it },
                    label         = { Text("New name for \"$renameOriginal\"") },
                    singleLine    = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameInput.isNotBlank() && renameInput != renameOriginal)
                        onRenameSymbol(renameOriginal, renameInput)
                    showRenameDialog = false
                    symbolMenu       = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── Scroll to current search match ────────────────────────────────────────
    LaunchedEffect(currentMatch) {
        if (currentMatch >= 0 && currentMatch < matchLines.size)
            listState.animateScrollToItem(matchLines[currentMatch])
    }

    // ── Theme colours (resolved once, not per-item) ────────────────────────────
    val activeMatchBg   = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
    val passiveMatchBg  = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.30f)
    val gutterLineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val lineNumColor    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // SelectionContainer wraps the whole list so the user can long-press
            // and drag selection handles across multiple visible lines.
            SelectionContainer(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state    = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll)
                    .padding(vertical = 4.dp)
            ) {
                itemsIndexed(lines) { index, line ->
                    val rowBg = when {
                        matchLines.getOrNull(currentMatch) == index -> activeMatchBg
                        index in matchLineSet                       -> passiveMatchBg
                        else                                        -> Color.Transparent
                    }

                    // Each row is wrapped in a Box so the DropdownMenu is anchored here
                    Box {
                        Row(
                            modifier          = Modifier.fillMaxWidth().background(rowBg),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // ── Line number gutter (not selectable) ────────────
                            // DisableSelection keeps numbers + divider out of the
                            // clipboard when the user copies selected code.
                            DisableSelection {
                                Text(
                                    text       = "%4d".format(index + 1),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize   = 12.sp,
                                    textAlign  = TextAlign.End,
                                    color      = lineNumColor,
                                    modifier   = Modifier.width(40.dp).padding(start = 4.dp)
                                )
                                Box(
                                    Modifier
                                        .padding(horizontal = 6.dp)
                                        .width(1.dp)
                                        .height(16.dp)
                                        .background(gutterLineColor)
                                )
                            }
                            // ── Syntax-highlighted code + long-press interaction ─
                            val highlighted =
                                if (searchQuery.isNotBlank() && index in matchLineSet)
                                    highlightWithSearch(
                                        if (isXml) highlightXmlLine(line) else highlightLine(line),
                                        line, searchQuery
                                    )
                                else
                                    if (isXml) highlightXmlLine(line) else highlightLine(line)

                            InteractiveCodeLine(
                                annotatedString = highlighted,
                                selectedWord    = symbolMenu?.word,
                                lineIndex       = index,
                                isXml           = isXml,
                                onSymbolTap     = { word, lineIdx ->
                                    symbolMenu = SymbolMenuState(
                                        word          = word,
                                        lineIndex     = lineIdx,
                                        matchingClass = resolveClass(word, packageName, classList)
                                    )
                                }
                            )
                        }

                        // ── Context menu anchored to this row ──────────────────
                        if (symbolMenu?.lineIndex == index) {
                            val menu = symbolMenu!!
                            DropdownMenu(
                                expanded         = true,
                                onDismissRequest = { symbolMenu = null }
                            ) {
                                if (menu.matchingClass != null) {
                                    DropdownMenuItem(
                                        text        = { Text("Go to class  ${menu.matchingClass.simpleName}") },
                                        onClick     = {
                                            onNavigateToClass(menu.matchingClass)
                                            symbolMenu = null
                                        },
                                        leadingIcon = { Icon(Icons.Default.Code, null) }
                                    )
                                }
                                DropdownMenuItem(
                                    text        = { Text("Find all occurrences") },
                                    onClick     = { onSearch(menu.word); symbolMenu = null },
                                    leadingIcon = { Icon(Icons.Default.Search, null) }
                                )
                                DropdownMenuItem(
                                    text        = { Text("Rename…") },
                                    onClick     = {
                                        renameOriginal   = menu.word
                                        renameInput      = menu.word
                                        showRenameDialog = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text        = { Text("Copy") },
                                    onClick     = {
                                        clipboard.setText(AnnotatedString(menu.word))
                                        symbolMenu = null
                                    },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                                )
                            }
                        }
                    }
                }
            }
            } // SelectionContainer

            // ── Status bar ────────────────────────────────────────────────────
            HorizontalDivider()
            Text(
                text     = "Line ${listState.firstVisibleItemIndex + 1} / ${lines.size}",
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

// ── Syntax highlighting ────────────────────────────────────────────────────────

private val MATCH_BG_STYLE = SpanStyle(background = Color(0x66FFEE00))
private val KEYWORD_STYLE  = SpanStyle(color = Color(0xFF569CD6), fontWeight = FontWeight.Bold)
private val TYPE_STYLE     = SpanStyle(color = Color(0xFF4EC9B0))
private val STRING_STYLE   = SpanStyle(color = Color(0xFFCE9178))
private val COMMENT_STYLE  = SpanStyle(color = Color(0xFF6A9955), fontStyle = FontStyle.Italic)
private val NUMBER_STYLE   = SpanStyle(color = Color(0xFFB5CEA8))
private val XML_TAG_STYLE  = SpanStyle(color = Color(0xFF4EC9B0))
private val XML_ATTR_STYLE = SpanStyle(color = Color(0xFF92C5F7))
private val XML_DECL_STYLE = SpanStyle(color = Color(0xFF6A9955), fontStyle = FontStyle.Italic)

private fun highlightWithSearch(base: AnnotatedString, rawLine: String, query: String): AnnotatedString {
    if (query.isBlank()) return base
    return buildAnnotatedString {
        append(base)
        val lower      = rawLine.lowercase()
        val lowerQuery = query.lowercase()
        var start = 0
        while (true) {
            val idx = lower.indexOf(lowerQuery, start)
            if (idx < 0) break
            addStyle(MATCH_BG_STYLE, idx, idx + query.length)
            start = idx + query.length
        }
    }
}

// ── Java / Kotlin highlighting ─────────────────────────────────────────────────

private val KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "if", "implements", "import",
    "instanceof", "int", "interface", "long", "native", "new", "null", "package",
    "private", "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws", "transient",
    "try", "void", "volatile", "while", "true", "false"
)

private fun highlightLine(line: String): AnnotatedString {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
        return buildAnnotatedString { withStyle(COMMENT_STYLE) { append(line) } }
    }
    return buildAnnotatedString {
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    var j = i + 1
                    while (j < line.length && line[j] != '"') {
                        if (line[j] == '\\') j++; j++
                    }
                    withStyle(STRING_STYLE) { append(line.substring(i, minOf(j + 1, line.length))) }
                    i = minOf(j + 1, line.length)
                }
                ch.isLetter() || ch == '_' -> {
                    var j = i
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    val word = line.substring(i, j)
                    when {
                        word in KEYWORDS      -> withStyle(KEYWORD_STYLE) { append(word) }
                        word[0].isUpperCase() -> withStyle(TYPE_STYLE) { append(word) }
                        else                  -> append(word)
                    }
                    i = j
                }
                ch.isDigit() -> {
                    var j = i
                    while (j < line.length && (line[j].isDigit() || line[j] == '.'
                           || line[j] == 'L' || line[j] == 'f')) j++
                    withStyle(NUMBER_STYLE) { append(line.substring(i, j)) }
                    i = j
                }
                else -> { append(ch); i++ }
            }
        }
    }
}

// ── XML highlighting ───────────────────────────────────────────────────────────

private fun highlightXmlLine(line: String): AnnotatedString {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("<!--") || trimmed.startsWith("*")) {
        return buildAnnotatedString { withStyle(COMMENT_STYLE) { append(line) } }
    }
    if (trimmed.startsWith("<?")) {
        return buildAnnotatedString { withStyle(XML_DECL_STYLE) { append(line) } }
    }
    return buildAnnotatedString {
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("<!--", i) -> {
                    val end = line.indexOf("-->", i + 4).let { if (it < 0) line.length else it + 3 }
                    withStyle(COMMENT_STYLE) { append(line.substring(i, end)) }
                    i = end
                }
                line[i] == '<' -> {
                    append('<'); i++
                    if (i < line.length && line[i] == '/') { append('/'); i++ }
                    val tagStart = i
                    while (i < line.length && line[i] != ' ' && line[i] != '>' && line[i] != '/') i++
                    withStyle(XML_TAG_STYLE) { append(line.substring(tagStart, i)) }
                    while (i < line.length && line[i] != '>') {
                        when {
                            line[i] == '"' -> {
                                var j = i + 1
                                while (j < line.length && line[j] != '"') j++
                                withStyle(STRING_STYLE) {
                                    append(line.substring(i, minOf(j + 1, line.length)))
                                }
                                i = minOf(j + 1, line.length)
                            }
                            line[i] == '/' || line[i] == '=' -> { append(line[i]); i++ }
                            line[i].isLetter() || line[i] == '_' || line[i] == ':' -> {
                                val attrStart = i
                                while (i < line.length && line[i] != '=' && line[i] != ' '
                                       && line[i] != '>' && line[i] != '/') i++
                                withStyle(XML_ATTR_STYLE) { append(line.substring(attrStart, i)) }
                            }
                            else -> { append(line[i]); i++ }
                        }
                    }
                    if (i < line.length) { append(line[i]); i++ }   // '>'
                }
                line[i] == '"' -> {
                    var j = i + 1
                    while (j < line.length && line[j] != '"') j++
                    withStyle(STRING_STYLE) { append(line.substring(i, minOf(j + 1, line.length))) }
                    i = minOf(j + 1, line.length)
                }
                else -> { append(line[i]); i++ }
            }
        }
    }
}
