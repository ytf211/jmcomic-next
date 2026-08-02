package com.par9uet.jm.ui.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousReaderStateTest {
    private val firstChapter = ChapterPageRange(
        chapterId = 101,
        chapterName = "第一章",
        startIndex = 0,
        pageCount = 30,
    )
    private val secondChapter = ChapterPageRange(
        chapterId = 202,
        chapterName = "第二章",
        startIndex = 30,
        pageCount = 12,
    )

    @Test
    fun mapsGlobalPageToChapterLocalPage() {
        val state = ContinuousReaderState(listOf(firstChapter, secondChapter))

        assertEquals(
            ReaderPosition(101, "第一章", 27, 30, 27),
            state.positionAt(27)
        )
        assertEquals(
            ReaderPosition(202, "第二章", 0, 12, 30),
            state.positionAt(30)
        )
        assertNull(state.positionAt(42))
    }

    @Test
    fun mapsChapterLocalPageBackToGlobalPage() {
        val state = ContinuousReaderState(listOf(firstChapter, secondChapter))

        assertEquals(27, state.globalIndexOf(101, 27))
        assertEquals(30, state.globalIndexOf(202, 0))
        assertEquals(41, state.globalIndexOf(202, 99))
        assertNull(state.globalIndexOf(999, 0))
    }

    @Test
    fun prefetchesWhenThreePagesRemain() {
        val state = ContinuousReaderState(listOf(firstChapter))

        assertFalse(state.shouldPrefetch(26, trailingPages = 3))
        assertTrue(state.shouldPrefetch(27, trailingPages = 3))
        assertTrue(state.shouldPrefetch(29, trailingPages = 3))
    }

    @Test
    fun shortChapterPrefetchesFromFirstPage() {
        val state = ContinuousReaderState(
            listOf(firstChapter.copy(pageCount = 2))
        )

        assertTrue(state.shouldPrefetch(0, trailingPages = 3))
    }

    @Test
    fun duplicateChapterIsNotAppended() {
        val state = ContinuousReaderState(listOf(firstChapter))

        val appended = state.append(secondChapter)

        assertEquals(appended, appended.append(secondChapter))
    }
}
