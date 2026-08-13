package com.halalify.kotlin.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFrameAccumulatorTest {
    @Test
    fun `arbitrary reads become fixed inference frames`() {
        val frames = mutableListOf<ShortArray>()
        val accumulator = AudioFrameAccumulator(frameSamples = 4)

        accumulator.append(shortArrayOf(1, 2, 3), 3, frames::add)
        accumulator.append(shortArrayOf(4, 5, 6, 7, 8), 5, frames::add)

        assertEquals(2, frames.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), frames[0])
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), frames[1])
    }

    @Test
    fun `rms is normalized for pcm16`() {
        val rms = shortArrayOf(Short.MAX_VALUE, Short.MAX_VALUE).normalizedRms()

        assertTrue(kotlin.math.abs(rms - 1F) < 0.0001F)
    }
}
