package com.boyz.introspector.ui.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boyz.introspector.data.model.AttachedSession
import com.boyz.introspector.data.model.MemoryAddress
import com.boyz.introspector.data.repository.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ScanState {
    data object Idle : ScanState
    data object Attaching : ScanState
    data object Scanning : ScanState
    data class Results(val addresses: List<MemoryAddress>, val round: Int = 1) : ScanState
    data class Error(val message: String) : ScanState
}

class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = MemoryRepository(application)

    private val _session = MutableStateFlow<AttachedSession?>(null)
    val session: StateFlow<AttachedSession?> = _session

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    fun attach(packageName: String) {
        // Same app already attached — keep all existing scan rounds, just refresh PID.
        val alreadyAttached = _session.value?.packageName == packageName
        if (!alreadyAttached) {
            _scanState.value = ScanState.Idle   // clear rounds when switching apps
        }

        viewModelScope.launch(Dispatchers.IO) {
            _scanState.value = ScanState.Attaching
            repo.installBinary()

            val appName = try {
                getApplication<Application>().packageManager
                    .getApplicationInfo(packageName, 0)
                    .let { getApplication<Application>().packageManager.getApplicationLabel(it).toString() }
            } catch (e: PackageManager.NameNotFoundException) { packageName }

            val pid = repo.getPid(packageName)
            if (pid > 0) {
                _session.value = AttachedSession(packageName, appName, pid)
                // Restore Idle only if we weren't already in Results (alreadyAttached case)
                if (_scanState.value is ScanState.Attaching) {
                    _scanState.value = ScanState.Idle
                }
            } else {
                _scanState.value = ScanState.Error(
                    "Process not found — is $appName running?"
                )
            }
        }
    }

    fun detach() {
        _session.value = null
        _scanState.value = ScanState.Idle
    }

    // Full scan — replaces any previous results.
    fun firstScan(value: Int) {
        val pid = _session.value?.pid ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _scanState.value = ScanState.Scanning
            val results = repo.scan(pid, value)
            _scanState.value = if (results.isEmpty())
                ScanState.Error("No matches found for $value")
            else
                ScanState.Results(results, round = 1)
        }
    }

    // Narrow the existing candidate list to addresses whose current value == value.
    fun nextScan(value: Int) {
        val pid = _session.value?.pid ?: return
        val prev = _scanState.value as? ScanState.Results ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _scanState.value = ScanState.Scanning
            val narrowed = repo.narrowDown(pid, prev.addresses.map { it.hex }, value)
            _scanState.value = when {
                narrowed.isEmpty() -> ScanState.Error(
                    "No candidates matched $value — try a First Scan with the current value"
                )
                else -> ScanState.Results(
                    addresses = narrowed.map { MemoryAddress(it, value) },
                    round = prev.round + 1
                )
            }
        }
    }

    fun write(address: String, value: Int) {
        val pid = _session.value?.pid ?: return
        viewModelScope.launch(Dispatchers.IO) { repo.write(pid, address, value) }
    }
}
