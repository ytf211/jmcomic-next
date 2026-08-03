package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import com.par9uet.jm.store.AppInitializationCoordinator

class GlobalViewModel(
    private val initializationCoordinator: AppInitializationCoordinator,
) : ViewModel() {
    fun init() {
        initializationCoordinator.ensureStarted()
    }

    fun retryInitialization() {
        initializationCoordinator.retry()
    }
}
