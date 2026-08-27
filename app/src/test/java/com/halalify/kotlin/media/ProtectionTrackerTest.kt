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
    fun `unselected classification cannot refresh or move a protected track`() {
        val tracker = ProtectionTracker()
        val original = detection(shouldBlur = true)
        tracker.update(listOf(original))

        val changedClass = detection(classId = 1, shouldBlur = false).copy(
            x1 = 0.20F,
            x2 = 0.70F,
        )
        val protected = tracker.update(listOf(changedClass), contentChanged = false)
        val stillProtected = tracker.update(emptyList(), contentChanged = false)

        assertEquals(1, protected.size)
        assertTrue(protected.single().shouldBlur)
        assertEquals(0, protected.single().classId)
        assertEquals(original.x1, protected.single().x1, 0.0001F)
        assertEquals(original.x2, protected.single().x2, 0.0001F)
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

    @Test
    fun `separate stacked image cards cannot steal the same protection identity`() {
        val tracker = ProtectionTracker()
        // Coordinates are normalized from the two regions that alternated as
        // id=1 on the emulator: they are similar in size, but belong to image
        // cards roughly 800 px apart vertically.
        val upperCard = detection(shouldBlur = true).copy(
            x1 = 0.19F,
            y1 = 0.36F,
            x2 = 0.48F,
            y2 = 0.56F,
        )
        val lowerCard = upperCard.copy(
            x1 = 0.06F,
            y1 = 0.64F,
            x2 = 0.47F,
            y2 = 0.96F,
        )
        val firstId = tracker.update(listOf(upperCard)).single().protectionId

        val protected = tracker.update(listOf(lowerCard))

        assertEquals(2, protected.size)
        assertEquals(firstId, protected.first().protectionId)
        assertTrue(protected[0].protectionId != protected[1].protectionId)
        assertEquals(upperCard.y1, protected.first().y1, 0.0001F)
    }

    @Test
    fun `protected region follows content movement while still on screen`() {
        val tracker = ProtectionTracker()
        tracker.update(listOf(detection(shouldBlur = true)))

        val protected = tracker.offset(deltaX = 0F, deltaY = -0.20F)

        assertEquals(1, protected.size)
        assertEquals(-0.10F, protected.single().y1, 0.0001F)
        assertEquals(0.60F, protected.single().y2, 0.0001F)
    }

    @Test
    fun `protected region is removed only after content leaves the display`() {
        val tracker = ProtectionTracker()
        tracker.update(listOf(detection(shouldBlur = true)))

        val protected = tracker.offset(deltaX = 0F, deltaY = -0.90F)

        assertTrue(protected.isEmpty())
    }

    @Test
    fun `protected region keeps the same identity across detector updates`() {
        val tracker = ProtectionTracker()
        val first = tracker.update(listOf(detection(shouldBlur = true))).single()

        val refreshed = tracker.update(
            listOf(detection(shouldBlur = true).copy(x1 = 0.12F, x2 = 0.62F)),
        ).single()

        assertTrue(first.protectionId != null)
        assertEquals(first.protectionId, refreshed.protectionId)
    }

    @Test
    fun `nested tile detection keeps one stable enclosing subject`() {
        val tracker = ProtectionTracker()
        val fullFrame = detection(shouldBlur = true)
        val first = tracker.update(listOf(fullFrame)).single()

        val detailTile = fullFrame.copy(
            x1 = 0.20F,
            y1 = 0.20F,
            x2 = 0.50F,
            y2 = 0.70F,
        )
        val refreshed = tracker.update(listOf(detailTile))

        assertEquals(1, refreshed.size)
        assertEquals(first.protectionId, refreshed.single().protectionId)
        assertEquals(fullFrame.x1, refreshed.single().x1, 0.0001F)
        assertEquals(fullFrame.y1, refreshed.single().y1, 0.0001F)
        assertEquals(fullFrame.x2, refreshed.single().x2, 0.0001F)
        assertEquals(fullFrame.y2, refreshed.single().y2, 0.0001F)
    }

    @Test
    fun `duplicate nested boxes in one frame create one track`() {
        val tracker = ProtectionTracker()
        val outer = detection(shouldBlur = true).copy(confidence = 0.80F)
        val inner = outer.copy(
            x1 = 0.20F,
            y1 = 0.20F,
            x2 = 0.50F,
            y2 = 0.70F,
            confidence = 0.90F,
        )

        val protected = tracker.update(listOf(outer, inner))

        assertEquals(1, protected.size)
        assertEquals(inner.x1, protected.single().x1, 0.0001F)
        assertEquals(inner.y1, protected.single().y1, 0.0001F)
    }

    @Test
    fun `partially overlapping portrait tiles follow the latest box without expansion`() {
        val tracker = ProtectionTracker()
        val upperTile = detection(shouldBlur = true).copy(
            x1 = 0.10F,
            y1 = 0.10F,
            x2 = 0.60F,
            y2 = 0.55F,
        )
        tracker.update(listOf(upperTile))

        val lowerTile = upperTile.copy(
            x1 = 0.15F,
            y1 = 0.30F,
            x2 = 0.65F,
            y2 = 0.95F,
        )
        val protected = tracker.update(listOf(lowerTile)).single()

        assertEquals(lowerTile.x1, protected.x1, 0.0001F)
        assertEquals(lowerTile.y1, protected.y1, 0.0001F)
        assertEquals(lowerTile.x2, protected.x2, 0.0001F)
        assertEquals(lowerTile.y2, protected.y2, 0.0001F)
    }

    @Test
    fun `successive overlapping tiles cannot ratchet protection across the screen`() {
        val tracker = ProtectionTracker()
        val firstTile = detection(shouldBlur = true).copy(
            x1 = 0.05F,
            y1 = 0.10F,
            x2 = 0.55F,
            y2 = 0.55F,
        )
        tracker.update(listOf(firstTile))

        val secondTile = firstTile.copy(
            x1 = 0.15F,
            y1 = 0.30F,
            x2 = 0.65F,
            y2 = 0.75F,
        )
        tracker.update(listOf(secondTile))

        val thirdTile = firstTile.copy(
            x1 = 0.25F,
            y1 = 0.45F,
            x2 = 0.75F,
            y2 = 0.90F,
        )
        val protected = tracker.update(listOf(thirdTile)).single()

        assertEquals(thirdTile.x1, protected.x1, 0.0001F)
        assertEquals(thirdTile.y1, protected.y1, 0.0001F)
        assertEquals(thirdTile.x2, protected.x2, 0.0001F)
        assertEquals(thirdTile.y2, protected.y2, 0.0001F)
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
