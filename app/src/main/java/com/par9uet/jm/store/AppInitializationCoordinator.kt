package com.par9uet.jm.store

import android.util.Log
import com.par9uet.jm.task.AppInitTask
import com.par9uet.jm.task.runAppInitialization
import com.par9uet.jm.utils.log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AppInitializationCoordinator(
    private val appInitTasks: List<AppInitTask>,
    private val initManager: InitManager,
    private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private var initializationJob: Job? = null

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        initializationJob = launchInitialization()
    }

    fun retry() {
        initializationJob?.cancel()
        initializationJob = launchInitialization()
    }

    private fun launchInitialization() = scope.launch {
        val attemptId = initManager.begin()
        val startedAt = System.nanoTime()
        runAppInitialization(
            appInitTasks = appInitTasks,
            initManager = initManager,
            attemptId = attemptId,
            onTaskFailure = ::logTaskFailure,
            onStartupReady = {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                runCatching { log("启动初始化", "首屏初始化完成：${elapsedMs}ms") }
            },
        )
        if (initManager.startupState.value !is StartupState.Ready) return@launch
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        runCatching { log("全局初始化", "后台初始化完成：${elapsedMs}ms") }
    }

    private fun logTaskFailure(task: AppInitTask, throwable: Throwable) {
        try {
            log("初始化任务", "${task.getAppTaskInfo().taskName} 失败：${throwable.message}")
        } catch (_: Throwable) {
            runCatching {
                Log.e(
                    "[JM-MOBILE]",
                    "初始化任务 ${task.getAppTaskInfo().taskName} 失败：${throwable.message}"
                )
            }
        }
    }
}
