package com.swims.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Generic factory that passes Application to AndroidViewModel subclasses.
 * Used wherever viewModels() delegate is not available (e.g. custom scoping).
 */
class SwimsViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(app) as T
            modelClass.isAssignableFrom(HistoryViewModel::class.java) -> HistoryViewModel(app) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(app) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
