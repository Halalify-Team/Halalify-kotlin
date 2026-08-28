package com.halalify.kotlin.capture

import com.halalify.kotlin.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionAgingPolicyTest {
    @Test
    fun `changed clean screen keeps protection hidden by the overlay`() {
        val decision = decideProtectionAging(
            contentChanged = true,
            reason = FrameAnalysisReason.CONTENT_CHANGED,
            hasProtectedObservation = false,
        )

        assertFalse(decision.contentChanged)
        assertFalse(decision.safetyRefresh)
    }

    @Test
    fun `changed screen with another protected observation ages stale tracks`() {
        val decision = decideProtectionAging(
            contentChanged = true,
            reason = FrameAnalysisReason.CONTENT_CHANGED,
            hasProtectedObservation = true,
        )

        assertTrue(decision.contentChanged)
        assertFalse(decision.safetyRefresh)
    }

    @Test
    fun `empty periodic refresh keeps protection on a static image`() {
        val decision = decideProtectionAging(
            contentChanged = false,
            reason = FrameAnalysisReason.SAFETY_REFRESH,
            hasProtectedObservation = false,
        )

        assertFalse(decision.contentChanged)
        assertFalse(decision.safetyRefresh)
    }

    @Test
    fun `periodic refresh with another protected observation ages missed tracks`() {
        val decision = decideProtectionAging(
            contentChanged = false,
            reason = FrameAnalysisReason.SAFETY_REFRESH,
            hasProtectedObservation = true,
        )

        assertFalse(decision.contentChanged)
        assertTrue(decision.safetyRefresh)
    }

    @Test
    fun `late detection matching a discarded page is ignored temporarily`() {
        val discarded = detection()
        val shiftedLateDetection = discarded.copy(
            x1 = 0.12F,
            y1 = 0.12F,
            x2 = 0.62F,
            y2 = 0.82F,
        )

        val filtered = filterRecentlyDiscardedDetections(
            detections = listOf(shiftedLateDetection),
            discardedDetections = listOf(discarded),
            suppressionActive = true,
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `new protected person elsewhere is not blocked after navigation`() {
        val discarded = detection()
        val newPerson = discarded.copy(
            x1 = 0.70F,
            y1 = 0.70F,
            x2 = 0.90F,
            y2 = 0.95F,
        )

        val filtered = filterRecentlyDiscardedDetections(
            detections = listOf(newPerson),
            discardedDetections = listOf(discarded),
            suppressionActive = true,
        )

        assertEquals(listOf(newPerson), filtered)
    }

    @Test
    fun `discarded location becomes eligible after suppression window`() {
        val detection = detection()

        val filtered = filterRecentlyDiscardedDetections(
            detections = listOf(detection),
            discardedDetections = listOf(detection),
            suppressionActive = false,
        )

        assertEquals(listOf(detection), filtered)
    }

    private fun detection() = Detection(
        x1 = 0.10F,
        y1 = 0.10F,
        x2 = 0.60F,
        y2 = 0.80F,
        confidence = 0.90F,
        classId = 0,
        shouldBlur = true,
    )
}
