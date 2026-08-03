package com.par9uet.jm.ui.viewModel

import com.par9uet.jm.store.InitManager
import com.par9uet.jm.store.StartupState
import com.par9uet.jm.task.AppInitTask
import com.par9uet.jm.task.AppTaskInfo
import com.par9uet.jm.task.runAppInitialization
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInitializationTest {
    @Test
    fun startupGateDoesNotWaitForNetworkBackgroundTasks() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val releaseBackground = CompletableDeferred<Unit>()
        val backgroundStarted = CompletableDeferred<Unit>()
        val initManager = InitManager()
        val attemptId = initManager.begin()
        val tasks = listOf(
            FakeInitTask("local", sort = 1, blocksStartup = true) {
                events += "local"
            },
            FakeInitTask("network", sort = 2, blocksStartup = false) {
                backgroundStarted.complete(Unit)
                releaseBackground.await()
                events += "network"
            }
        )

        val job = launch {
            runAppInitialization(
                appInitTasks = tasks,
                initManager = initManager,
                attemptId = attemptId,
                onTaskFailure = { _, _ -> },
            )
        }

        assertEquals(StartupState.Ready, initManager.awaitStartup())
        assertTrue("critical local task must finish before the gate", "local" in events)
        backgroundStarted.await()
        assertFalse("background network task must not block startup", job.isCompleted)

        releaseBackground.complete(Unit)
        job.join()
        assertTrue("network" in events)
    }

    @Test
    fun criticalFailureFailsClosedAndSkipsBackgroundTasks() = runBlocking {
        val backgroundStarted = AtomicBoolean(false)
        val initManager = InitManager()
        val attemptId = initManager.begin()
        val tasks = listOf(
            FakeInitTask("local", sort = 1, blocksStartup = true) {
                error("broken settings")
            },
            FakeInitTask("network", sort = 2, blocksStartup = false) {
                backgroundStarted.set(true)
            }
        )

        runAppInitialization(
            tasks,
            initManager,
            attemptId = attemptId,
            onTaskFailure = { _, _ -> },
        )

        val state = initManager.startupState.value
        assertTrue(state is StartupState.Failed)
        assertTrue((state as StartupState.Failed).message.contains("broken settings"))
        assertFalse(backgroundStarted.get())
    }

    @Test
    fun cancellationNeverOpensStartupGate() = runBlocking {
        val criticalStarted = CompletableDeferred<Unit>()
        val initManager = InitManager()
        val attemptId = initManager.begin()
        val task = FakeInitTask("local", sort = 1, blocksStartup = true) {
            criticalStarted.complete(Unit)
            awaitCancellation()
        }

        val job = launch {
            runAppInitialization(
                listOf(task),
                initManager,
                attemptId = attemptId,
                onTaskFailure = { _, _ -> },
            )
        }
        criticalStarted.await()
        job.cancelAndJoin()

        assertEquals(StartupState.Initializing, initManager.startupState.value)
    }

    @Test
    fun staleAttemptCannotOverwriteNewerState() {
        val initManager = InitManager()
        val first = initManager.begin()
        val second = initManager.begin()

        initManager.completeFailure(first, "stale")
        assertEquals(StartupState.Initializing, initManager.startupState.value)

        initManager.completeReady(second)
        assertEquals(StartupState.Ready, initManager.startupState.value)
    }
}

private class FakeInitTask(
    private val name: String,
    private val sort: Int,
    private val blocksStartup: Boolean,
    private val block: suspend () -> Unit,
) : AppInitTask {
    override suspend fun init() = block()

    override fun getAppTaskInfo() = AppTaskInfo(
        taskName = name,
        sort = sort,
        blocksStartup = blocksStartup,
    )
}
