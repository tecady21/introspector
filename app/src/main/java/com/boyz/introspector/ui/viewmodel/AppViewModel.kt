package com.boyz.introspector.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boyz.introspector.data.model.InstalledApp
import com.boyz.introspector.data.repository.AppRepository
import com.boyz.introspector.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppRepository(application)

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps

    private val _isRooted = MutableStateFlow(false)
    val isRooted: StateFlow<Boolean> = _isRooted

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Running a command initializes the shell and triggers the SU grant prompt.
            // Shell.isAppGrantedRoot() returns null before the shell is created, so we check
            // the actual id output instead.
            _isRooted.value = RootManager.runCommand("id").contains("uid=0")
            _apps.value = repo.getInstalledUserApps()
        }
    }
}
