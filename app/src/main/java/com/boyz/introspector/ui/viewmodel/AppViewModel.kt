package com.boyz.introspector.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boyz.introspector.data.model.InstalledApp
import com.boyz.introspector.data.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppRepository(application)

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps

    private val _showAllApps = MutableStateFlow(false)
    val showAllApps: StateFlow<Boolean> = _showAllApps

    init {
        // Root is only needed for memory features — do NOT prompt for SU here.
        // Root status is determined lazily in SessionViewModel when the user taps Attach.
        viewModelScope.launch(Dispatchers.IO) {
            _apps.value = repo.getInstalledUserApps()
        }
    }

    fun toggleShowAllApps() {
        val newValue = !_showAllApps.value
        _showAllApps.value = newValue
        viewModelScope.launch(Dispatchers.IO) {
            _apps.value = if (newValue) repo.getAllInstalledApps()
                          else repo.getInstalledUserApps()
        }
    }
}
