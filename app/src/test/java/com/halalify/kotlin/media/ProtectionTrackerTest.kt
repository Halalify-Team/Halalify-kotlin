package com.halalify.kotlin.media

import com.halalify.kotlin.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionTrackerTest {
    @Test
    fun `protected detection survives a missed frame`() {
        val tracker = ProtectionTracker()

        tracker.update(listOf(detection(shouldBlur = true)))
        val protected = tracker.update(emptyList())

        assertEquals(1, protected.size)
        assertTrue(protected.single().shouldBlur)
    }

    @Test
    fun `changed classification at same location stays protected and refreshes track`() {
        val tracker = ProtectionTracker()
        tracker.update(listOf(detection(shouldBlur = true)))

        val changedClass = detection(classId = 1, shouldBlur = false)
        val protected = tracker.update(listOf(changedClass))
        val stillProtected = tracker.update(emptyList())

        assertEquals(1, protected.size)
        assertTrue(protected.single().shouldBlur)
        assertEquals(1, stillProtected.size)
    }

    @Test
    fun `static region never expires with time or safety refreshes`() {
        val tracker = ProtectionTracker(maxMissedContentChanges = 2)
        tracker.update(listOf(detection(shouldBlur = true)))

        repeat(100) { tracker.update(emptyList(), contentChanged = false) }

        assertEquals(1, tracker.update(emptyList()).size)
    }

    @Test
    fun `stale region expires on the first real content change`() {
        val tracker = ProtectionTracker(maxMissedContentChanges = 1)
        tracker.update(listOf(detection(shouldBlur = true)))

        val afterChange = tracker.update(emptyList(), contentChanged = true)

        assertTrue(afterChange.isEmpty())
    }

    @Test
    fun `unprotected detection does not create a track`() {
        val tracker = ProtectionTracker()

        val protected = tracker.update(
            listOf(detection(classId = 1, shouldBlur = false)),
        )

        assertFalse(protected.any())
    }

    @Test
    fun `matched region movement is smoothed like HaramBlur`() {
        val tracker = ProtectionTracker(smoothingAlpha = 0.55F)
        val first = detection(shouldBlur = true)
        tracker.update(listOf(first))

        val moved = first.copy(x1 = 0.20F, x2 = 0.70F)
        val protected = tracker.update(listOf(moved)).single()

        assertEquals(0.155F, protected.x1, 0.0001F)
        assertEquals(0.655F, protected.x2, 0.0001F)
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
