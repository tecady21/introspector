package com.boyz.introspector.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boyz.introspector.data.repository.ClassInfo
import com.boyz.introspector.data.repository.OccurrenceResult
import com.boyz.introspector.data.repository.ResourceCategory
import com.boyz.introspector.data.repository.ResourceInfo
import com.boyz.introspector.data.repository.SourceRepository
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

// ── Find-all overlay state ─────────────────────────────────────────────────────

data class FindAllState(
    val query: String,
    val results: List<OccurrenceResult>,
    val isLoading: Boolean,
    val filter: String = ""
)

// ── Main UI state ──────────────────────────────────────────────────────────────

sealed interface SourceUiState {
    data object Idle : SourceUiState
    data class Loading(val message: String = "Loading…") : SourceUiState
    data class ClassList(
        val all: List<ClassInfo>,
        val filtered: List<ClassInfo>,
        val filter: String = "",
        val resources: List<ResourceInfo> = emptyList()
    ) : SourceUiState
    data class ViewingClass(
        val info: ClassInfo,
        val code: String,
        val classList: List<ClassInfo>,
        val resources: List<ResourceInfo> = emptyList(),
        val searchQuery: String = "",
        val matchLines: List<Int> = emptyList(),   // 0-based line indices containing a match
        val currentMatch: Int = -1                  // index into matchLines
    ) : SourceUiState
    data class ViewingResource(
        val resource: ResourceInfo,
        val content: String,
        val classList: List<ClassInfo>,
        val resources: List<ResourceInfo>,
        val searchQuery: String = "",
        val matchLines: List<Int> = emptyList(),
        val currentMatch: Int = -1
    ) : SourceUiState
    data class Error(val message: String) : SourceUiState
}

class SourceViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SourceRepository(application)
    private val _uiState = MutableStateFlow<SourceUiState>(SourceUiState.Idle)
    val uiState: StateFlow<SourceUiState> = _uiState

    /** Cached resource list so it can be threaded into each new state without re-querying. */
    private var loadedResources: List<ResourceInfo> = emptyList()

    /**
     * The package most recently passed to [load].  Used to suppress redundant
     * reload calls when navigating back to an already-loaded screen.
     */
    private var currentPackage: String? = null

    // ── Local renames (session-scoped, applied on-the-fly at display time) ──────

    /**
     * User-defined renames for the current session.  Stored here so every
     * subsequent class/resource load has the renames applied automatically.
     * Cleared when a new package is loaded.
     */
    private val localRenames = mutableMapOf<String, String>()

    fun renameSymbol(from: String, to: String) {
        if (from == to || from.isBlank() || to.isBlank()) return
        localRenames[from] = to
        // Apply immediately to whatever is currently on screen
        when (val s = _uiState.value) {
            is SourceUiState.ViewingClass ->
                _uiState.value = s.copy(code    = applyRename(s.code, from, to))
            is SourceUiState.ViewingResource ->
                _uiState.value = s.copy(content = applyRename(s.content, from, to))
            else -> {}
        }
    }

    private fun applyRename(text: String, from: String, to: String): String =
        text.replace(Regex("\\b${Regex.escape(from)}\\b"), to)

    private fun applyAllRenames(text: String): String {
        var result = text
        for ((f, t) in localRenames) result = applyRename(result, f, t)
        return result
    }

    // ── Drawer expansion state ────────────────────────────────────────────────
    //
    // Lives in the ViewModel (Activity-scoped) so it persists across back-
    // navigation, screen recomposition, and package reloads.

    var drawerExpanded: Set<String> by mutableStateOf(emptySet())
        private set

    fun toggleDrawer(key: String) {
        drawerExpanded = if (key in drawerExpanded) drawerExpanded - key
                         else drawerExpanded + key
    }

    /** Add [keys] to the expanded set without collapsing anything already open. */
    fun expandDrawerPath(keys: Set<String>) {
        if (keys.isEmpty()) return
        drawerExpanded = drawerExpanded + keys
    }

    // ── Find-all occurrences overlay ───────────────────────────────────────────

    var findAllState: FindAllState? by mutableStateOf(null)
        private set

    fun findAllOccurrences(word: String) {
        val classList = currentClassList() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            findAllState = FindAllState(word, emptyList(), isLoading = true)
            val results  = repo.findAllOccurrences(word, classList)
            findAllState = FindAllState(word, results, isLoading = false)
        }
    }

    fun filterFindAll(filter: String) {
        findAllState = findAllState?.copy(filter = filter)
    }

    fun dismissFindAll() {
        findAllState = null
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun currentClassList(): List<ClassInfo>? = when (val s = _uiState.value) {
        is SourceUiState.ClassList       -> s.all
        is SourceUiState.ViewingClass    -> s.classList
        is SourceUiState.ViewingResource -> s.classList
        else                             -> null
    }

    // ── Load ───────────────────────────────────────────────────────────────────

    fun load(packageName: String, sourceFiles: List<File>) {
        // If this package is already loaded (or currently loading), do nothing.
        // This lets the Activity-scoped ViewModel preserve state across back-navigation.
        val s = _uiState.value
        if (packageName == currentPackage &&
            s !is SourceUiState.Idle &&
            s !is SourceUiState.Error) return
        currentPackage = packageName
        localRenames.clear()    // fresh slate for each new package

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = SourceUiState.Loading("Scanning APK files…")
            runCatching {
                repo.loadClasses(sourceFiles) { msg -> _uiState.value = SourceUiState.Loading(msg) }
            }
                .onSuccess { classes ->
                    val resources = repo.getResources()
                    loadedResources = resources
                    // Transition to ClassList; SourceScreen's LaunchedEffect will
                    // then auto-open the drawer and navigate to AndroidManifest.xml.
                    _uiState.value = SourceUiState.ClassList(classes, classes, resources = resources)
                }
                .onFailure { e ->
                    val detail = e.message?.takeIf { it.isNotBlank() }
                        ?: e.cause?.message
                        ?: e.javaClass.simpleName
                    _uiState.value = SourceUiState.Error("Failed to load classes: $detail")
                }
        }
    }

    // ── Class selection ────────────────────────────────────────────────────────

    fun selectClass(info: ClassInfo) {
        val classList = currentClassList() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = SourceUiState.Loading("Decompiling ${info.simpleName}…")
            runCatching { repo.decompileClass(info.fullName) }
                .onSuccess { code ->
                    _uiState.value = SourceUiState.ViewingClass(
                        info      = info,
                        code      = applyAllRenames(code),
                        classList = classList,
                        resources = loadedResources
                    )
                }
                .onFailure { e ->
                    _uiState.value = SourceUiState.Error(e.message ?: "Decompilation failed")
                }
        }
    }

    // ── Resource selection ─────────────────────────────────────────────────────

    fun selectResource(resource: ResourceInfo) {
        val classList = currentClassList() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = SourceUiState.Loading("Loading ${resource.name}…")
            runCatching { repo.getResourceContent(resource.apkPath) }
                .onSuccess { content ->
                    _uiState.value = SourceUiState.ViewingResource(
                        resource  = resource,
                        content   = applyAllRenames(content),
                        classList = classList,
                        resources = loadedResources
                    )
                }
                .onFailure { e ->
                    _uiState.value = SourceUiState.Error(e.message ?: "Resource load failed")
                }
        }
    }

    // ── Class list filter ──────────────────────────────────────────────────────

    fun filter(query: String) {
        val state = _uiState.value as? SourceUiState.ClassList ?: return
        _uiState.value = state.copy(
            filter   = query,
            filtered = if (query.isBlank()) state.all
                       else state.all.filter { it.fullName.contains(query, ignoreCase = true) }
        )
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    fun backToList() {
        val classList = currentClassList() ?: return
        _uiState.value = SourceUiState.ClassList(classList, classList, resources = loadedResources)
    }

    // ── In-code search ─────────────────────────────────────────────────────────

    fun search(query: String)  { _uiState.value = _uiState.value.withSearch(query) }
    fun nextMatch()             { _uiState.value = _uiState.value.withNextMatch()   }
    fun prevMatch()             { _uiState.value = _uiState.value.withPrevMatch()   }

    private fun SourceUiState.withSearch(query: String): SourceUiState {
        val text = when (this) {
            is SourceUiState.ViewingClass    -> code
            is SourceUiState.ViewingResource -> content
            else -> return this
        }
        if (query.isBlank()) return when (this) {
            is SourceUiState.ViewingClass    -> copy(searchQuery = "", matchLines = emptyList(), currentMatch = -1)
            is SourceUiState.ViewingResource -> copy(searchQuery = "", matchLines = emptyList(), currentMatch = -1)
            else -> this
        }
        val lines = text.lines()
        val hits  = lines.indices.filter { lines[it].contains(query, ignoreCase = true) }
        return when (this) {
            is SourceUiState.ViewingClass    -> copy(searchQuery = query, matchLines = hits, currentMatch = if (hits.isEmpty()) -1 else 0)
            is SourceUiState.ViewingResource -> copy(searchQuery = query, matchLines = hits, currentMatch = if (hits.isEmpty()) -1 else 0)
            else -> this
        }
    }

    private fun SourceUiState.withNextMatch(): SourceUiState = when (this) {
        is SourceUiState.ViewingClass    -> if (matchLines.isEmpty()) this else copy(currentMatch = (currentMatch + 1) % matchLines.size)
        is SourceUiState.ViewingResource -> if (matchLines.isEmpty()) this else copy(currentMatch = (currentMatch + 1) % matchLines.size)
        else -> this
    }

    private fun SourceUiState.withPrevMatch(): SourceUiState = when (this) {
        is SourceUiState.ViewingClass    -> if (matchLines.isEmpty()) this else copy(currentMatch = (currentMatch - 1 + matchLines.size) % matchLines.size)
        is SourceUiState.ViewingResource -> if (matchLines.isEmpty()) this else copy(currentMatch = (currentMatch - 1 + matchLines.size) % matchLines.size)
        else -> this
    }

    override fun onCleared() {
        super.onCleared()
        repo.close()
    }
}
