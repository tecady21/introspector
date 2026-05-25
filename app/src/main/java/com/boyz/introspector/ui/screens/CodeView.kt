package com.boyz.introspector.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boyz.introspector.data.repository.ClassInfo

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
    classList.find { it.fullName == word }?.let { return it }
    classList.find { it.simpleName == word }?.let { return it }
    if (word.startsWith(".")) {
        val stripped = word.removePrefix(".")
        classList.find { it.fullName == "$packageName.$stripped" }?.let { return it }
        classList.find { it.simpleName == stripped }?.let { return it }
    } else {
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
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis

                var lastTapTime = 0L
                var lastTapPos  = Offset.Zero
                var downPos     = Offset.Zero
                var downTime    = 0L
                var pointerDown = false

                awaitPointerEventScope {
                    while (true) {
                        // PointerEventPass.Initial = parent-first (top-down).
                        // We observe events BEFORE SelectionContainer (child), but never
                        // consume them — SelectionContainer sees the same events unmodified.
                        val event  = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue

                        when {
                            change.pressed && !change.previousPressed -> {
                                pointerDown = true
                                downPos     = change.position
                                downTime    = System.currentTimeMillis()
                            }
                            !change.pressed && change.previousPressed && pointerDown -> {
                                pointerDown = false
                                val now    = System.currentTimeMillis()
                                val holdMs = now - downTime

                                if (holdMs < longPressMs) {
                                    val dx = downPos.x - lastTapPos.x
                                    val dy = downPos.y - lastTapPos.y
                                    if (now - lastTapTime <= doubleTapMs &&
                                        dx * dx + dy * dy < 80f * 80f) {
                                        // ── Double-tap ──────────────────────────────
                                        val charIdx = layoutResult?.getOffsetForPosition(downPos)
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

// ── In-code search bar ─────────────────────────────────────────────────────────

@Composable
internal fun CodeSearchBar(
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

// ── Code view ──────────────────────────────────────────────────────────────────

@Composable
internal fun CodeView(
    code: String,
    isXml: Boolean = false,
    searchQuery: String = "",
    matchLines: List<Int> = emptyList(),
    currentMatch: Int = -1,
    classList: List<ClassInfo> = emptyList(),
    packageName: String = "",
    onNavigateToClass: (ClassInfo) -> Unit = {},
    onSearch: (String) -> Unit = {},
    onFindAll: (String) -> Unit = {},
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
                    val isMatch       = index in matchLineSet
                    val isCurrentLine = matchLines.getOrNull(currentMatch) == index
                    val rowBg = when {
                        isCurrentLine -> activeMatchBg
                        isMatch       -> passiveMatchBg
                        else          -> Color.Transparent
                    }

                    // Memoize highlighting: recompute only when content or search state changes,
                    // not on every scroll or unrelated recomposition.
                    val highlighted = remember(line, isXml, searchQuery, isMatch) {
                        val base = if (isXml) highlightXmlLine(line) else highlightLine(line)
                        if (searchQuery.isNotBlank() && isMatch)
                            highlightWithSearch(base, line, searchQuery)
                        else
                            base
                    }

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
                            // ── Syntax-highlighted code + double-tap interaction ─
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
                                    onClick     = { onFindAll(menu.word); symbolMenu = null },
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
