package com.par9uet.jm.ui.screens.readScreen

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderToolbarProgressTest {
    @Test
    fun progressUsesOneBasedPageAndRoundedPercentage() {
        assertEquals(
            ReaderToolbarProgress(currentPage = 3, pageCount = 33, progressText = "9%"),
            readerToolbarProgress(currentIndex = 2, pageCount = 33)
        )
    }

    @Test
    fun progressHandlesEmptyAndOutOfBoundsValues() {
        assertEquals(
            ReaderToolbarProgress(currentPage = 0, pageCount = 0, progressText = "0%"),
            readerToolbarProgress(currentIndex = 10, pageCount = 0)
        )
        assertEquals(
            ReaderToolbarProgress(currentPage = 10, pageCount = 10, progressText = "100%"),
            readerToolbarProgress(currentIndex = 99, pageCount = 10)
        )
    }
}
