package com.par9uet.jm.worker

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

private const val DOWNLOAD_LIMITER_PERMITS = 6

internal fun safeDownloadConcurrency(value: Int): Int = value.coerceIn(1, 3)

internal object DownloadConcurrencyLimiter {
    private val permits = Semaphore(DOWNLOAD_LIMITER_PERMITS)
    private val acquisitionMutex = Mutex()

    suspend fun <T> withLimit(configuredConcurrency: Int, block: suspend () -> T): T {
        val concurrency = safeDownloadConcurrency(configuredConcurrency)
        val permitsPerTask = DOWNLOAD_LIMITER_PERMITS / concurrency
        var acquiredPermits = 0
        try {
            acquisitionMutex.withLock {
                repeat(permitsPerTask) {
                    permits.acquire()
                    acquiredPermits++
                }
            }
            return block()
        } finally {
            repeat(acquiredPermits) {
                permits.release()
            }
        }
    }
}
