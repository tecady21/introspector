package com.boyz.introspector.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boyz.introspector.data.repository.ClassInfo
import com.boyz.introspector.data.repository.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface SourceUiState {
    data object Idle : SourceUiState
    data object Loading : SourceUiState
    data class ClassList(
        val all: List<ClassInfo>,
        val filtered: List<ClassInfo>,
        val filter: String = ""
    ) : SourceUiState
    data class ViewingClass(
        val info: ClassInfo,
        val code: String,
        val classList: List<ClassInfo>
    ) : SourceUiState
    data class Error(val message: String) : SourceUiState
}

class SourceViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SourceRepository()
    private val _uiState = MutableStateFlow<SourceUiState>(SourceUiState.Idle)
    val uiState: StateFlow<SourceUiState> = _uiState

    fun load(packageName: String, sourceDir: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = SourceUiState.Loading
            runCatching { repo.loadClasses(sourceDir) }
                .onSuccess { classes ->
                    _uiState.value = SourceUiState.ClassList(classes, classes)
                }
                .onFailure { e ->
                    _uiState.value = SourceUiState.Error(e.message ?: "Failed to load classes")
                }
        }
    }

    fun selectClass(info: ClassInfo) {
        val classList = (_uiState.value as? SourceUiState.ClassList)?.all ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = SourceUiState.Loading
            runCatching { repo.decompileClass(info.fullName) }
                .onSuccess { code ->
                    _uiState.value = SourceUiState.ViewingClass(info, code, classList)
                }
                .onFailure { e ->
                    _uiState.value = SourceUiState.Error(e.message ?: "Decompilation failed")
                }
        }
    }

    fun filter(query: String) {
        val state = _uiState.value as? SourceUiState.ClassList ?: return
        _uiState.value = state.copy(
            filter = query,
            filtered = if (query.isBlank()) state.all
                       else state.all.filter { it.fullName.contains(query, ignoreCase = true) }
        )
    }

    fun backToList() {
        val state = _uiState.value as? SourceUiState.ViewingClass ?: return
        _uiState.value = SourceUiState.ClassList(state.classList, state.classList)
    }

    override fun onCleared() {
        super.onCleared()
        repo.close()
    }
}
