package com.boyz.introspector.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

// ── Span styles ────────────────────────────────────────────────────────────────

private val MATCH_BG_STYLE = SpanStyle(background = Color(0x66FFEE00))
private val KEYWORD_STYLE  = SpanStyle(color = Color(0xFF569CD6), fontWeight = FontWeight.Bold)
private val TYPE_STYLE     = SpanStyle(color = Color(0xFF4EC9B0))
private val STRING_STYLE   = SpanStyle(color = Color(0xFFCE9178))
private val COMMENT_STYLE  = SpanStyle(color = Color(0xFF6A9955), fontStyle = FontStyle.Italic)
private val NUMBER_STYLE   = SpanStyle(color = Color(0xFFB5CEA8))
private val XML_TAG_STYLE  = SpanStyle(color = Color(0xFF4EC9B0))
private val XML_ATTR_STYLE = SpanStyle(color = Color(0xFF92C5F7))
private val XML_DECL_STYLE = SpanStyle(color = Color(0xFF6A9955), fontStyle = FontStyle.Italic)

// ── Search-match overlay ───────────────────────────────────────────────────────

internal fun highlightWithSearch(base: AnnotatedString, rawLine: String, query: String): AnnotatedString {
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

internal fun highlightLine(line: String): AnnotatedString {
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

internal fun highlightXmlLine(line: String): AnnotatedString {
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
