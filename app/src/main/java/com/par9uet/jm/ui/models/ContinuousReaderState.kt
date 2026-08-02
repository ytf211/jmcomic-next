package com.par9uet.jm.ui.models

sealed interface ChapterAppendState {
    data object Idle : ChapterAppendState
    data class Loading(val chapterId: Int) : ChapterAppendState
    data class Error(val chapterId: Int, val message: String) : ChapterAppendState
    data object End : ChapterAppendState
}

data class ChapterPageRange(
    val chapterId: Int,
    val chapterName: String,
    val startIndex: Int,
    val pageCount: Int,
) {
    val endIndex: Int get() = startIndex + pageCount - 1
}

data class ReaderPosition(
    val chapterId: Int,
    val chapterName: String,
    val localPageIndex: Int,
    val pageCount: Int,
    val globalIndex: Int,
)

data class ContinuousReaderState(
    val chapters: List<ChapterPageRange> = emptyList(),
    val appendState: ChapterAppendState = ChapterAppendState.Idle,
) {
    fun positionAt(globalIndex: Int): ReaderPosition? {
        val range = chapters.firstOrNull { globalIndex in it.startIndex..it.endIndex }
            ?: return null
        return ReaderPosition(
            chapterId = range.chapterId,
            chapterName = range.chapterName,
            localPageIndex = globalIndex - range.startIndex,
            pageCount = range.pageCount,
            globalIndex = globalIndex,
        )
    }

    fun globalIndexOf(chapterId: Int, localPageIndex: Int): Int? {
        val range = chapters.firstOrNull { it.chapterId == chapterId } ?: return null
        return range.startIndex + localPageIndex.coerceIn(0, range.pageCount - 1)
    }

    fun shouldPrefetch(globalIndex: Int, trailingPages: Int): Boolean {
        val position = positionAt(globalIndex) ?: return false
        val lastChapter = chapters.lastOrNull() ?: return false
        if (position.chapterId != lastChapter.chapterId) return false
        val threshold = (position.pageCount - trailingPages.coerceAtLeast(1)).coerceAtLeast(0)
        return position.localPageIndex >= threshold
    }

    fun append(chapter: ChapterPageRange): ContinuousReaderState {
        if (chapters.any { it.chapterId == chapter.chapterId } || chapter.pageCount <= 0) {
            return this
        }
        val startIndex = chapters.sumOf { it.pageCount }
        return copy(
            chapters = chapters + chapter.copy(startIndex = startIndex),
            appendState = ChapterAppendState.Idle,
        )
    }
}
