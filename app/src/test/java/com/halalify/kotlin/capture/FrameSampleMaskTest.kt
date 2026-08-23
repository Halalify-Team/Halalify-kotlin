package com.halalify.kotlin.capture

import com.halalify.kotlin.model.Detection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSampleMaskTest {
    @Test
    fun samplesCoveredByCurrentBlurAreIgnored() {
        val region = detection(shouldBlur = true)

        assertTrue(isProtectedSample(0.50F, 0.50F, listOf(region)))
        assertFalse(isProtectedSample(0.90F, 0.50F, listOf(region)))
    }

    @Test
    fun unprotectedDetectionsDoNotHideFrameActivity() {
        assertFalse(isProtectedSample(0.50F, 0.50F, listOf(detection(shouldBlur = false))))
    }

    private fun detection(shouldBlur: Boolean) = Detection(
        x1 = 0.20F,
        y1 = 0.20F,
        x2 = 0.80F,
        y2 = 0.80F,
        confidence = 0.90F,
        classId = 0,
        shouldBlur = shouldBlur,
    )
}
