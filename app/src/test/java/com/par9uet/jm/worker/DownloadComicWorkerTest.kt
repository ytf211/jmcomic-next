package com.par9uet.jm.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadComicWorkerTest {
    @Test
    fun streamsUnscrambledPageWhenComicIsBeforeScrambleThreshold() {
        assertTrue(
            shouldStreamOriginalPage(
                comicId = 100,
                scrambleId = 200,
                speed = "0",
                url = "https://cdn.example/1.webp"
            )
        )
    }

    @Test
    fun streamsUnscrambledPageWhenSpeedDisablesScrambling() {
        assertTrue(
            shouldStreamOriginalPage(
                comicId = 300,
                scrambleId = 200,
                speed = "1",
                url = "https://cdn.example/1.jpg?token=1"
            )
        )
    }

    @Test
    fun decodesScrambledPage() {
        assertFalse(
            shouldStreamOriginalPage(
                comicId = 300,
                scrambleId = 200,
                speed = "0",
                url = "https://cdn.example/1.webp"
            )
        )
    }

    @Test
    fun keepsGifOnDecodePath() {
        assertFalse(
            shouldStreamOriginalPage(
                comicId = 100,
                scrambleId = 200,
                speed = "1",
                url = "https://cdn.example/1.GIF?token=1"
            )
        )
    }
}
