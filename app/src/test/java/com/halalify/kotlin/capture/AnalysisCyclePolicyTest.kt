package com.halalify.kotlin.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisCyclePolicyTest {
    @Test
    fun `first moving frame starts at full screen`() {
        assertTrue(
            shouldRestartAnalysisCycle(
                reason = FrameAnalysisReason.CONTENT_CHANGED,
                previousReason = FrameAnalysisReason.STABILIZATION,
                externallyInvalidated = false,
            ),
        )
    }

    @Test
    fun `continuous video frames advance through detail tiles`() {
        assertFalse(
            shouldRestartAnalysisCycle(
                reason = FrameAnalysisReason.CONTENT_CHANGED,
                previousReason = FrameAnalysisReason.CONTENT_CHANGED,
                externallyInvalidated = false,
            ),
        )
    }

    @Test
    fun `external navigation always restarts at full screen`() {
        assertTrue(
            shouldRestartAnalysisCycle(
                reason = FrameAnalysisReason.CONTENT_CHANGED,
                previousReason = FrameAnalysisReason.CONTENT_CHANGED,
                externallyInvalidated = true,
            ),
        )
    }

    @Test
    fun `initial and safety passes start at full screen`() {
        assertTrue(
            shouldRestartAnalysisCycle(
                reason = FrameAnalysisReason.INITIAL,
                previousReason = null,
                externallyInvalidated = false,
            ),
        )
        assertTrue(
            shouldRestartAnalysisCycle(
                reason = FrameAnalysisReason.SAFETY_REFRESH,
                previousReason = FrameAnalysisReason.STABILIZATION,
                externallyInvalidated = false,
            ),
        )
    }
}
