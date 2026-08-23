package com.halalify.kotlin.capture

import com.halalify.kotlin.model.Detection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionRegionPolicyTest {
    @Test
    fun highConfidencePortraitMayCoverMostOfTheScreen() {
        assertTrue(detection(confidence = 0.56F, x2 = 0.95F, y2 = 0.98F).isUsableDetection())
    }

    @Test
    fun weakNearlyFullScreenDetectionIsRejected() {
        assertFalse(detection(confidence = 0.30F, x2 = 0.95F, y2 = 0.98F).isUsableDetection())
    }

    @Test
    fun normalWeakDetectionRemainsUsableForModelRecall() {
        assertTrue(detection(confidence = 0.30F, x2 = 0.60F, y2 = 0.80F).isUsableDetection())
    }

    @Test
    fun malformedCoordinatesAreRejected() {
        assertFalse(detection(confidence = 0.90F, x2 = 1.10F, y2 = 0.80F).isUsableDetection())
    }

    private fun detection(confidence: Float, x2: Float, y2: Float) = Detection(
        x1 = 0.05F,
        y1 = 0.05F,
        x2 = x2,
        y2 = y2,
        confidence = confidence,
        classId = 0,
        shouldBlur = true,
    )
}
