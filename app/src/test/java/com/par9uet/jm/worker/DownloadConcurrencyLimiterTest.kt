package com.par9uet.jm.worker

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadConcurrencyLimiterTest {
    @Test
    fun configuredLimitCapsConcurrentDownloadTasks() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)

        coroutineScope {
            repeat(6) {
                launch(Dispatchers.Default) {
                    DownloadConcurrencyLimiter.withLimit(2) {
                        val current = active.incrementAndGet()
                        maximum.updateAndGet { previous -> maxOf(previous, current) }
                        try {
                            delay(40)
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }
        }

        assertEquals(2, maximum.get())
    }

    @Test
    fun userValueIsClampedToSafeRange() {
        assertEquals(1, safeDownloadConcurrency(0))
        assertEquals(2, safeDownloadConcurrency(2))
        assertEquals(3, safeDownloadConcurrency(9))
    }
}
