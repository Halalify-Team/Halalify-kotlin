package com.halalify.kotlin.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameActivityDetectorTest {
    @Test
    fun `new screen runs a short analysis burst then becomes idle`() {
        val detector = FrameActivityDetector(
            burstAnalyses = 3,
            burstIntervalMs = 100L,
            safetyRefreshMs = 5_000L,
        )
        val frame = IntArray(100) { 0x102030 }

        assertTrue(detector.shouldAnalyze(frame, 0L))
        assertFalse(detector.shouldAnalyze(frame, 50L))
        assertTrue(detector.shouldAnalyze(frame, 100L))
        assertTrue(detector.shouldAnalyze(frame, 200L))
        assertFalse(detector.shouldAnalyze(frame, 300L))
    }

    @Test
    fun `meaningful screen change starts analysis immediately`() {
        val detector = FrameActivityDetector(burstAnalyses = 1)
        val original = IntArray(100) { 0x101010 }
        val changed = original.copyOf().apply {
            for (index in 0 until 4) this[index] = 0xF0F0F0
        }

        detector.shouldAnalyze(original, 0L)

        assertTrue(detector.shouldAnalyze(changed, 100L))
    }

    @Test
    fun `static screen receives periodic safety refresh`() {
        val detector = FrameActivityDetector(
            burstAnalyses = 1,
            safetyRefreshMs = 1_000L,
        )
        val frame = IntArray(100) { 0x101010 }

        detector.shouldAnalyze(frame, 0L)

        assertFalse(detector.shouldAnalyze(frame, 999L))
        assertTrue(detector.shouldAnalyze(frame, 1_000L))
    }
}
