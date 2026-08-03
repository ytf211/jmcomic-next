package com.par9uet.jm.ui.viewModel

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicReadWindowTest {
    @Test
    fun indexPrefetchKeepsEntireVisibleWindow() {
        assertEquals(
            10..11,
            readerDecodeKeepRange(
                index = 10,
                pageCount = 20,
                prefetchCount = 0,
                visibleRange = 10..11,
            )
        )
    }

    @Test
    fun distantJumpDoesNotRetainStaleVisibleWindow() {
        assertEquals(
            98..102,
            readerDecodeKeepRange(
                index = 100,
                pageCount = 200,
                prefetchCount = 2,
                visibleRange = 10..11,
            )
        )
    }

    @Test
    fun indexPrefetchExtendsBeyondVisibleWindow() {
        assertEquals(
            8..13,
            readerDecodeKeepRange(
                index = 10,
                pageCount = 20,
                prefetchCount = 2,
                visibleRange = 10..11,
            )
        )
    }
}
