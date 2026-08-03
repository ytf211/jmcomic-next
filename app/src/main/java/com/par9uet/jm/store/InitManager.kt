package com.par9uet.jm.store

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

sealed interface StartupState {
    data object Initializing : StartupState
    data object Ready : StartupState
    data class Failed(val message: String) : StartupState
}

class InitManager {
    private val attempt = AtomicLong(0L)
    private val _startupState = MutableStateFlow<StartupState>(StartupState.Initializing)
    val startupState = _startupState.asStateFlow()

    fun currentAttempt(): Long = attempt.get()

    fun begin(): Long {
        val nextAttempt = attempt.incrementAndGet()
        _startupState.value = StartupState.Initializing
        return nextAttempt
    }

    fun completeReady(attemptId: Long) {
        if (attempt.get() == attemptId) {
            _startupState.value = StartupState.Ready
        }
    }

    fun completeFailure(attemptId: Long, message: String) {
        if (attempt.get() == attemptId) {
            _startupState.value = StartupState.Failed(message)
        }
    }

    suspend fun awaitStartup(): StartupState {
        return startupState.first { it !is StartupState.Initializing }
    }
}
