package com.halalify.kotlin.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBitmapPolicyTest {
    @Test
    fun `window remains until its last visible pixel leaves the display`() {
        assertTrue(
            windowIntersectsDisplay(
                left = -99,
                top = 100,
                width = 100,
                height = 100,
                displayLeft = 0,
                displayTop = 0,
                displayRight = 1080,
                displayBottom = 2340,
            ),
        )
    }

    @Test
    fun `window is removed after it completely leaves the display`() {
        assertFalse(
            windowIntersectsDisplay(
                left = -100,
                top = 100,
                width = 100,
                height = 100,
                displayLeft = 0,
                displayTop = 0,
                displayRight = 1080,
                displayBottom = 2340,
            ),
        )
    }

    @Test
    fun `same filtered region accepts a fresh usable bitmap`() {
        assertFalse(
            shouldPreserveOverlayBitmap(
                currentProtectionId = 7L,
                hasFilteredBitmap = true,
                newProtectionId = 7L,
                newIsFiltered = true,
                hasSpatialContinuity = true,
            ),
        )
    }

    @Test
    fun `same filtered region keeps its bitmap when replacement is fully redacted`() {
        assertTrue(
            shouldPreserveOverlayBitmap(
                currentProtectionId = 7L,
                hasFilteredBitmap = true,
                newProtectionId = 7L,
                newIsFiltered = true,
                newBitmapLooksRedacted = true,
                hasSpatialContinuity = true,
            ),
        )
    }

    @Test
    fun `same identity cannot stretch its old bitmap to a distant image card`() {
        assertFalse(
            shouldPreserveOverlayBitmap(
                currentProtectionId = 7L,
                hasFilteredBitmap = true,
                newProtectionId = 7L,
                newIsFiltered = true,
                hasSpatialContinuity = false,
            ),
        )
    }

    @Test
    fun `new region cannot reuse another regions bitmap`() {
        assertFalse(
            shouldPreserveOverlayBitmap(
                currentProtectionId = 7L,
                hasFilteredBitmap = true,
                newProtectionId = 8L,
                newIsFiltered = true,
            ),
        )
    }

    @Test
    fun `spatially continuous region keeps blur when Android redacts its new bitmap`() {
        assertTrue(
            shouldPreserveOverlayBitmap(
                currentProtectionId = 7L,
                hasFilteredBitmap = true,
                newProtectionId = 8L,
                newIsFiltered = true,
                newBitmapLooksRedacted = true,
                hasSpatialContinuity = true,
            ),
        )
    }

    @Test
    fun `redacted bitmap without spatial continuity cannot reuse old blur`() {
        assertFalse(
            shouldPreserveOverlayBitmap(
                currentProtectionId = 7L,
                hasFilteredBitmap = true,
                newProtectionId = 8L,
                newIsFiltered = true,
                newBitmapLooksRedacted = true,
                hasSpatialContinuity = false,
            ),
        )
    }

    @Test
    fun `solid style intentionally replaces a filtered bitmap`() {
        assertFalse(
            shouldPreserveOverlayBitmap(
                currentProtectionId = 7L,
                hasFilteredBitmap = true,
                newProtectionId = 7L,
                newIsFiltered = false,
            ),
        )
    }
}
