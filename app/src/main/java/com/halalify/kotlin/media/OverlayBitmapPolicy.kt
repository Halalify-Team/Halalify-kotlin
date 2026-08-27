package com.halalify.kotlin.media

/**
 * A protected region must keep the bitmap captured before its overlay became
 * visible. Later MediaProjection frames redact that same region to black.
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
        hasFilteredBitmap &&
        newIsFiltered &&
        (
            currentProtectionId == newProtectionId ||
                (newBitmapLooksRedacted && hasSpatialContinuity)
        )
