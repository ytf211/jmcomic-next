package com.par9uet.jm.task

import com.par9uet.jm.store.InitManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val STARTUP_INITIALIZATION_TIMEOUT_MS = 8_000L

suspend fun runAppInitialization(
    appInitTasks: List<AppInitTask>,
    initManager: InitManager,
    attemptId: Long = initManager.currentAttempt(),
    onTaskFailure: (AppInitTask, Throwable) -> Unit,
    onStartupReady: () -> Unit = {},
) {
    val sortedTasks = appInitTasks.sortedBy { it.getAppTaskInfo().sort }
    val startupTasks = sortedTasks.filter { it.getAppTaskInfo().blocksStartup }
    val backgroundTasks = sortedTasks.filterNot { it.getAppTaskInfo().blocksStartup }

    suspend fun runTask(task: AppInitTask): Throwable? {
        return try {
            task.init()
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            runCatching { onTaskFailure(task, throwable) }
            throwable
        }
    }

    val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val startupDeferred = startupTasks.map { task ->
        taskScope.async { runTask(task) }
    }
    val startupFailures = withTimeoutOrNull(STARTUP_INITIALIZATION_TIMEOUT_MS) {
        startupDeferred.mapNotNull { it.await() }
    }

    if (startupFailures == null) {
        taskScope.cancel()
        initManager.completeFailure(attemptId, "启动初始化超时，请重试")
        return
    }
    if (startupFailures.isNotEmpty()) {
        taskScope.cancel()
        val message = startupFailures.first().message.orEmpty().ifBlank { "本地数据初始化失败" }
        initManager.completeFailure(attemptId, "启动失败：$message")
        return
    }

    initManager.completeReady(attemptId)
    if (initManager.currentAttempt() != attemptId) {
        taskScope.cancel()
        return
    }
    runCatching { onStartupReady() }

    try {
        withContext(Dispatchers.IO) {
            backgroundTasks.map { task ->
                async { runTask(task) }
            }.forEach { it.await() }
        }
    } finally {
        taskScope.cancel()
    }
}
