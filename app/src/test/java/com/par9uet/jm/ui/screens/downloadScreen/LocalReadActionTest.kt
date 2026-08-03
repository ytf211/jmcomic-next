package com.par9uet.jm.ui.screens.downloadScreen

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalReadActionTest {
    @Test
    fun historyChangesLocalReadActionToContinue() {
        assertEquals("继续", localReadActionLabel(isMultiChapter = true, hasHistory = true))
        assertEquals("继续", localReadActionLabel(isMultiChapter = false, hasHistory = true))
    }

    @Test
    fun unreadLocalComicKeepsExistingLabels() {
        assertEquals("阅读", localReadActionLabel(isMultiChapter = true, hasHistory = false))
        assertEquals("阅读缓存", localReadActionLabel(isMultiChapter = false, hasHistory = false))
    }
}
