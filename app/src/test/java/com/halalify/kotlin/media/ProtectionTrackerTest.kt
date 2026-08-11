package com.halalify.kotlin.media

import com.halalify.kotlin.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionTrackerTest {
    @Test
    fun `protected detection survives a missed frame`() {
        val tracker = ProtectionTracker(retentionMs = 3_000L)

        tracker.update(listOf(detection(shouldBlur = true)), nowMs = 1_000L)
        val protected = tracker.update(emptyList(), nowMs = 2_000L)

        assertEquals(1, protected.size)
        assertTrue(protected.single().shouldBlur)
    }

    @Test
    fun `changed classification at same location stays protected and refreshes track`() {
        val tracker = ProtectionTracker(retentionMs = 3_000L)
        tracker.update(listOf(detection(shouldBlur = true)), nowMs = 1_000L)

        val changedClass = detection(classId = 1, shouldBlur = false)
        val protected = tracker.update(listOf(changedClass), nowMs = 3_000L)
        val stillProtected = tracker.update(emptyList(), nowMs = 5_500L)

        assertEquals(1, protected.size)
        assertTrue(protected.single().shouldBlur)
        assertEquals(1, stillProtected.size)
    }

    @Test
    fun `stale region expires after retention window`() {
        val tracker = ProtectionTracker(retentionMs = 3_000L)
        tracker.update(listOf(detection(shouldBlur = true)), nowMs = 1_000L)

        val protected = tracker.update(emptyList(), nowMs = 4_001L)

        assertTrue(protected.isEmpty())
    }

    @Test
    fun `unprotected detection does not create a track`() {
        val tracker = ProtectionTracker()

        val protected = tracker.update(
            listOf(detection(classId = 1, shouldBlur = false)),
            nowMs = 1_000L,
        )

        assertFalse(protected.any())
    }

    private fun detection(
        classId: Int = 0,
        shouldBlur: Boolean,
    ) = Detection(
        x1 = 0.10F,
        y1 = 0.10F,
        x2 = 0.60F,
        y2 = 0.80F,
        confidence = 0.90F,
        classId = classId,
        shouldBlur = shouldBlur,
    )
}
