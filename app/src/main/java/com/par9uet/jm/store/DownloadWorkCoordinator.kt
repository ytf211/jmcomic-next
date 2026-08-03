package com.par9uet.jm.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal object DownloadWorkCoordinator {
    private val chapterLocks = ConcurrentHashMap<Int, Mutex>()

    suspend fun <T> withChapterLock(comicId: Int, block: suspend () -> T): T {
        return chapterLocks.getOrPut(comicId) { Mutex() }.withLock {
            block()
        }
    }
}
