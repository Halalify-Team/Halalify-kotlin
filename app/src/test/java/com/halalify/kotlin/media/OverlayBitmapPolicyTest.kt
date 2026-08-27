package com.halalify.kotlin.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBitmapPolicyTest {
    @Test
    fun `same filtered region keeps its original bitmap`() {
        assertTrue(
            shouldPreserveOverlayBitmap(
                currentProtectionId = 7L,
                hasFilteredBitmap = true,
                newProtectionId = 7L,
                newIsFiltered = true,
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
