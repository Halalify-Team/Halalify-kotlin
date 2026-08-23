package com.halalify.kotlin.media

import com.halalify.kotlin.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionTrackerTest {
    @Test
    fun `protected detection survives a missed frame when content has not changed`() {
        val tracker = ProtectionTracker()

        tracker.update(listOf(detection(shouldBlur = true)))
        val protected = tracker.update(emptyList(), contentChanged = false)

        assertEquals(1, protected.size)
        assertTrue(protected.single().shouldBlur)
    }

    @Test
    fun `protected detection survives one noisy content change`() {
        val tracker = ProtectionTracker()
        tracker.update(listOf(detection(shouldBlur = true)))

        val protected = tracker.update(emptyList(), contentChanged = true)

        assertEquals(1, protected.size)
        assertTrue(protected.single().shouldBlur)
    }

    @Test
    fun `stale detection expires after two content changes`() {
        val tracker = ProtectionTracker(maxMissedContentChanges = 2)
        tracker.update(listOf(detection(shouldBlur = true)))

        tracker.update(emptyList(), contentChanged = true)
        val protected = tracker.update(emptyList(), contentChanged = true)

        assertTrue(protected.isEmpty())
    }

    @Test
    fun `changed classification at same location stays protected and refreshes track`() {
        val tracker = ProtectionTracker()
        tracker.update(listOf(detection(shouldBlur = true)))

        val changedClass = detection(classId = 1, shouldBlur = false)
        val protected = tracker.update(listOf(changedClass), contentChanged = false)
        val stillProtected = tracker.update(emptyList(), contentChanged = false)

        assertEquals(1, protected.size)
        assertTrue(protected.single().shouldBlur)
        assertEquals(1, stillProtected.size)
    }

    @Test
    fun `new unprotected subject replaces old protected subject after content change`() {
        val tracker = ProtectionTracker(maxMissedContentChanges = 1)
        tracker.update(listOf(detection(shouldBlur = true)))

        val protected = tracker.update(
            listOf(detection(classId = 1, shouldBlur = false)),
            contentChanged = true,
        )

        assertTrue(protected.isEmpty())
    }

    @Test
    fun oldRegionIsReplacedImmediatelyWhenMovedSubjectIsConfirmed() {
        val tracker = ProtectionTracker()
        tracker.update(listOf(detection(shouldBlur = true)))

        val moved = detection(shouldBlur = true).copy(
            x1 = 0.60F,
            x2 = 0.95F,
        )
        val protected = tracker.update(listOf(moved), contentChanged = true)

        assertEquals(1, protected.size)
        assertEquals(moved.x1, protected.single().x1, 0.0001F)
    }

    @Test
    fun `one matched person does not immediately remove a second missed person`() {
        val tracker = ProtectionTracker()
        val first = detection(shouldBlur = true)
        val second = detection(shouldBlur = true).copy(
            x1 = 0.65F,
            x2 = 0.95F,
        )
        tracker.update(listOf(first, second))

        val protected = tracker.update(listOf(first), contentChanged = true)

        assertEquals(2, protected.size)
    }

    @Test
    fun defaultTrackerRemovesStaleRegionAfterThreeChangedFrames() {
        val tracker = ProtectionTracker()
        tracker.update(listOf(detection(shouldBlur = true)))

        assertEquals(1, tracker.update(emptyList(), contentChanged = true).size)
        assertEquals(1, tracker.update(emptyList(), contentChanged = true).size)
        assertTrue(tracker.update(emptyList(), contentChanged = true).isEmpty())
    }

    @Test
    fun `safety refresh clears a static region that is no longer detected`() {
        val tracker = ProtectionTracker(maxMissedContentChanges = 2)
        tracker.update(listOf(detection(shouldBlur = true)))

        repeat(3) { tracker.update(emptyList(), safetyRefresh = true) }

        assertTrue(tracker.update(emptyList(), contentChanged = false).isEmpty())
    }

    @Test
    fun `missed region is cleared after enough settled safety refreshes`() {
        val tracker = ProtectionTracker()
        tracker.update(listOf(detection(shouldBlur = true)))
        tracker.update(emptyList(), contentChanged = true)

        tracker.update(emptyList(), safetyRefresh = true)
        val protected = tracker.update(emptyList(), safetyRefresh = true)

        assertTrue(protected.isEmpty())
    }

    @Test
    fun `stale region expires on the first real content change with tolerance of 1`() {
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

    @Test
    fun `default tracker follows the latest box without stale location padding`() {
        val tracker = ProtectionTracker()
        val first = detection(shouldBlur = true)
        tracker.update(listOf(first))

        val moved = first.copy(x1 = 0.20F, x2 = 0.70F)
        val protected = tracker.update(listOf(moved)).single()

        assertEquals(moved.x1, protected.x1, 0.0001F)
        assertEquals(moved.x2, protected.x2, 0.0001F)
    }

    @Test
    fun `fast movement is matched by center when boxes barely overlap`() {
        val tracker = ProtectionTracker()
        val first = detection(shouldBlur = true)
        tracker.update(listOf(first))

        val moved = first.copy(x1 = 0.40F, x2 = 0.90F)
        val protected = tracker.update(listOf(moved), contentChanged = true)

        assertEquals(1, protected.size)
        assertEquals(moved.x1, protected.single().x1, 0.0001F)
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
