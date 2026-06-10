package com.mitm.shadowtrack.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mitm.shadowtrack.AppState

class ConnectionViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ConnectionViewModel::class.java)) {
            return AppState.viewModel as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
