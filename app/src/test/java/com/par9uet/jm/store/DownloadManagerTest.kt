package com.par9uet.jm.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadManagerTest {
    @Test
    fun workNameIsStableAndChapterSpecific() {
        assertEquals("comic-download-123", downloadWorkName(123))
        assertNotEquals(downloadWorkName(123), downloadWorkName(456))
    }
}
