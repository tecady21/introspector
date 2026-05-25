package com.boyz.introspector.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boyz.introspector.data.repository.ClassInfo
import com.boyz.introspector.data.repository.ResourceCategory
import com.boyz.introspector.data.repository.ResourceInfo
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

// ── Content tree drawer ────────────────────────────────────────────────────────

@Composable
internal fun ContentTreeDrawer(
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
    val bgColor   = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val textColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface

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
    val indent    = (12 + depth * 16).dp
    val bgColor   = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val textColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val iconTint  = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary

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
