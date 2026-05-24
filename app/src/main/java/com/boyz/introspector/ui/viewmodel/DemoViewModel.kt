package com.boyz.introspector.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boyz.introspector.data.model.DemoValue
import com.boyz.introspector.data.repository.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface DemoUiState {
    data object Idle : DemoUiState
    data object Scanning : DemoUiState
    data class Candidates(
        val addresses: List<String>,
        val round: Int,
        val lastValue: Int   // value that was scanned to produce this candidate list
    ) : DemoUiState
    data class Found(val address: String) : DemoUiState
    data class Error(val message: String) : DemoUiState
}

class DemoViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = MemoryRepository(application)

    private val _uiState = MutableStateFlow<DemoUiState>(DemoUiState.Idle)
    val uiState: StateFlow<DemoUiState> = _uiState

    // The value currently stored in DemoValue's native buffer — what the user should scan for.
    private val _currentValue = MutableStateFlow(DemoValue.current)
    val currentValue: StateFlow<Int> = _currentValue

    // PID of this process — no shell needed, just Android API.
    val pid: Int = android.os.Process.myPid()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repo.installBinary()
        }
    }

    fun scan() {
        val value = _currentValue.value
        val state = _uiState.value

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = DemoUiState.Scanning

            val addresses: List<String> = when (state) {
                is DemoUiState.Candidates -> repo.narrowDown(pid, state.addresses, value)
                else -> repo.scan(pid, value).map { it.hex }
            }

            val round = if (state is DemoUiState.Candidates) state.round + 1 else 1

            when {
                addresses.isEmpty() -> _uiState.value = DemoUiState.Error(
                    "No matches — value may have changed. Tap Reset."
                )
                addresses.size == 1 -> {
                    _uiState.value = DemoUiState.Found(addresses.first())
                }
                else -> {
                    // Regenerate value so the next scan round uses a fresh number
                    _currentValue.value = DemoValue.randomize()
                    _uiState.value = DemoUiState.Candidates(addresses, round, value)
                }
            }
        }
    }

    fun reset() {
        _currentValue.value = DemoValue.randomize()
        _uiState.value = DemoUiState.Idle
    }

    fun patchAddress(address: String, newValue: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.write(pid, address, newValue)
            _currentValue.value = DemoValue.current  // reflect the write in the UI
        }
    }
}
