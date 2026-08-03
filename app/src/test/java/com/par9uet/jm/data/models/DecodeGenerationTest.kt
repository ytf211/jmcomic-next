package com.par9uet.jm.data.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeGenerationTest {
    @Test
    fun invalidationRejectsOlderDecodeResult() {
        val generation = DecodeGeneration()
        val first = generation.begin()

        generation.invalidate()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(generation.begin()))
    }
}
