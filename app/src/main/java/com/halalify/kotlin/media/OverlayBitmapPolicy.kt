package com.halalify.kotlin.media

/**
 * A protected region keeps its previous bitmap only as a last-resort fallback
 * when Android redacts the entire replacement. A fresh, usable bitmap should
 * replace the old one even for the same identity so it is never stretched
 * across changed bounds.
 */
internal fun shouldPreserveOverlayBitmap(
    currentProtectionId: Long?,
    hasFilteredBitmap: Boolean,
    newProtectionId: Long?,
    newIsFiltered: Boolean,
    newBitmapLooksRedacted: Boolean = false,
    hasSpatialContinuity: Boolean = false,
): Boolean =
    currentProtectionId != null &&
        newProtectionId != null &&
        hasFilteredBitmap &&
        newIsFiltered &&
        newBitmapLooksRedacted &&
        hasSpatialContinuity
