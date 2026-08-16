package com.halalify.kotlin.capture

import com.halalify.kotlin.settings.BlurSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualAnalysisPolicyTest {
    @Test
    fun `image-only coverage includes stabilization bursts and safety refresh`() {
        val policy = VisualAnalysisPolicy(
            BlurSettings(blurImages = true, blurVideos = false),
        )

        assertTrue(policy.shouldAnalyze(FrameAnalysisReason.INITIAL))
        assertTrue(policy.shouldAnalyze(FrameAnalysisReason.CONTENT_CHANGED))
        assertTrue(policy.shouldAnalyze(FrameAnalysisReason.STABILIZATION))
        assertTrue(policy.shouldAnalyze(FrameAnalysisReason.SAFETY_REFRESH))
    }

    @Test
    fun `video-only coverage includes stabilization bursts and skips static safety refresh`() {
        val policy = VisualAnalysisPolicy(
            BlurSettings(blurImages = false, blurVideos = true),
        )

        assertTrue(policy.shouldAnalyze(FrameAnalysisReason.INITIAL))
        assertTrue(policy.shouldAnalyze(FrameAnalysisReason.CONTENT_CHANGED))
        assertTrue(policy.shouldAnalyze(FrameAnalysisReason.STABILIZATION))
        assertFalse(policy.shouldAnalyze(FrameAnalysisReason.SAFETY_REFRESH))
    }

    @Test
    fun `disabled visual coverage performs no analysis`() {
        val policy = VisualAnalysisPolicy(
            BlurSettings(blurImages = false, blurVideos = false),
        )

        FrameAnalysisReason.entries.forEach { reason ->
            assertFalse(policy.shouldAnalyze(reason))
        }
    }
}
