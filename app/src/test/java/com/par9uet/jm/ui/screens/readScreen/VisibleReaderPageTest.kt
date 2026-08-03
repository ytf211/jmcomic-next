package com.par9uet.jm.ui.screens.readScreen

import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleReaderPageTest {
    @Test
    fun choosesPageWithLargestViewportOverlap() {
        val items = listOf(
            VisibleReaderItem(index = 10, offset = -950, size = 1_000),
            VisibleReaderItem(index = 11, offset = 50, size = 1_000),
        )

        assertEquals(
            11,
            mostVisibleReaderPage(
                items = items,
                viewportStart = 0,
                viewportEnd = 1_000,
                pageCount = 20,
            )
        )
    }

    @Test
    fun ignoresNonPageTailItems() {
        val items = listOf(
            VisibleReaderItem(index = 19, offset = -100, size = 700),
            VisibleReaderItem(index = 20, offset = 600, size = 400),
        )

        assertEquals(
            19,
            mostVisibleReaderPage(
                items = items,
                viewportStart = 0,
                viewportEnd = 1_000,
                pageCount = 20,
            )
        )
    }
}
