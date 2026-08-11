package com.halalify.kotlin.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        assertEquals(FrameAnalysisReason.INITIAL, detector.analysisReason(frame, 0L))
        assertNull(detector.analysisReason(frame, 50L))
        assertEquals(FrameAnalysisReason.STABILIZATION, detector.analysisReason(frame, 100L))
        assertEquals(FrameAnalysisReason.STABILIZATION, detector.analysisReason(frame, 200L))
        assertNull(detector.analysisReason(frame, 300L))
    }

    @Test
    fun `meaningful screen change starts analysis immediately`() {
        val detector = FrameActivityDetector(burstAnalyses = 1)
        val original = IntArray(100) { 0x101010 }
        val changed = original.copyOf().apply {
            for (index in 0 until 4) this[index] = 0xF0F0F0
        }

        detector.analysisReason(original, 0L)

        assertEquals(
            FrameAnalysisReason.CONTENT_CHANGED,
            detector.analysisReason(changed, 100L),
        )
    }

    @Test
    fun `static screen receives periodic safety refresh`() {
        val detector = FrameActivityDetector(
            burstAnalyses = 1,
            safetyRefreshMs = 1_000L,
        )
        val frame = IntArray(100) { 0x101010 }

        detector.analysisReason(frame, 0L)

        assertNull(detector.analysisReason(frame, 999L))
        assertEquals(
            FrameAnalysisReason.SAFETY_REFRESH,
            detector.analysisReason(frame, 1_000L),
        )
    }
}
