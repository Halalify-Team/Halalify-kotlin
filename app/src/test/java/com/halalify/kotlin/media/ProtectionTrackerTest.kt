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
    fun `stale region expires only after repeated content changes`() {
        val tracker = ProtectionTracker(maxMissedContentChanges = 2)
        tracker.update(listOf(detection(shouldBlur = true)))

        val afterFirstChange = tracker.update(emptyList(), contentChanged = true)
        val afterSecondChange = tracker.update(emptyList(), contentChanged = true)

        assertEquals(1, afterFirstChange.size)
        assertTrue(afterSecondChange.isEmpty())
    }

    @Test
    fun `unprotected detection does not create a track`() {
        val tracker = ProtectionTracker()

        val protected = tracker.update(
            listOf(detection(classId = 1, shouldBlur = false)),
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
