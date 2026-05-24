package com.boyz.introspector.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boyz.introspector.data.repository.AppRepository
import com.boyz.introspector.data.repository.ClassInfo
import com.boyz.introspector.ui.viewmodel.SourceUiState
import com.boyz.introspector.ui.viewmodel.SourceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(
    packageName: String,
    onBack: () -> Unit,
    vm: SourceViewModel = viewModel()
) {
    val context = LocalContext.current
    val appRepo = remember { AppRepository(context) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(packageName) {
        vm.load(packageName, appRepo.getSourceDir(packageName))
    }

    // Override back button when viewing a class
    BackHandler(enabled = uiState is SourceUiState.ViewingClass) {
        vm.backToList()
    }

    val title = when (uiState) {
        is SourceUiState.ViewingClass -> (uiState as SourceUiState.ViewingClass).info.simpleName
        else -> "Source Viewer"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(packageName, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState is SourceUiState.ViewingClass) vm.backToList() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                is SourceUiState.Loading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is SourceUiState.Error -> {
                    Text(state.message, modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error)
                }
                is SourceUiState.ClassList -> ClassListView(state, vm)
                is SourceUiState.ViewingClass -> CodeView(state.code)
                else -> {}
            }
        }
    }
}

@Composable
private fun ClassListView(state: SourceUiState.ClassList, vm: SourceViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.filter,
            onValueChange = vm::filter,
            label = { Text("Search classes") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true
        )
        Text(
            "${state.filtered.size} classes",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyColumn {
            items(state.filtered, key = { it.fullName }) { info ->
                ClassRow(info = info, onClick = { vm.selectClass(info) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ClassRow(info: ClassInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(info.simpleName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(info.packageName, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CodeView(code: String) {
    val lines = remember(code) { code.lines() }
    val hScroll = rememberScrollState()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(hScroll)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        itemsIndexed(lines) { index, line ->
            Row {
                Text(
                    text = "%4d".format(index + 1),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.width(36.dp)
                )
                Text(
                    text = highlightLine(line),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ── Syntax highlighting ───────────────────────────────────────────────────────

private val KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "if", "implements", "import",
    "instanceof", "int", "interface", "long", "native", "new", "null", "package",
    "private", "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws", "transient",
    "try", "void", "volatile", "while", "true", "false"
)

private val KEYWORD_STYLE  = SpanStyle(color = Color(0xFF569CD6), fontWeight = FontWeight.Bold)
private val TYPE_STYLE     = SpanStyle(color = Color(0xFF4EC9B0))
private val STRING_STYLE   = SpanStyle(color = Color(0xFFCE9178))
private val COMMENT_STYLE  = SpanStyle(color = Color(0xFF6A9955), fontStyle = FontStyle.Italic)
private val NUMBER_STYLE   = SpanStyle(color = Color(0xFFB5CEA8))

private fun highlightLine(line: String): AnnotatedString {
    val trimmed = line.trimStart()
    // Full-line comment
    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
        return buildAnnotatedString { withStyle(COMMENT_STYLE) { append(line) } }
    }
    return buildAnnotatedString {
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                // String literal
                ch == '"' -> {
                    var j = i + 1
                    while (j < line.length && line[j] != '"') {
                        if (line[j] == '\\') j++ // skip escaped char
                        j++
                    }
                    withStyle(STRING_STYLE) { append(line.substring(i, minOf(j + 1, line.length))) }
                    i = minOf(j + 1, line.length)
                }
                // Identifier or keyword
                ch.isLetter() || ch == '_' -> {
                    var j = i
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    val word = line.substring(i, j)
                    when {
                        word in KEYWORDS -> withStyle(KEYWORD_STYLE) { append(word) }
                        word[0].isUpperCase() -> withStyle(TYPE_STYLE) { append(word) }
                        else -> append(word)
                    }
                    i = j
                }
                // Number literal
                ch.isDigit() -> {
                    var j = i
                    while (j < line.length && (line[j].isDigit() || line[j] == '.' || line[j] == 'L' || line[j] == 'f')) j++
                    withStyle(NUMBER_STYLE) { append(line.substring(i, j)) }
                    i = j
                }
                else -> { append(ch); i++ }
            }
        }
    }
}
