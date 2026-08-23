package com.halalify.kotlin.capture

import com.halalify.kotlin.model.Detection

/** Rejects malformed and implausible protection regions before they reach the overlay. */
internal fun Detection.isUsableDetection(): Boolean {
    val regionWidth = x2 - x1
    val regionHeight = y2 - y1
    val area = regionWidth * regionHeight

    if (
        !x1.isFinite() || !y1.isFinite() ||
        !x2.isFinite() || !y2.isFinite() ||
        !confidence.isFinite() ||
        confidence !in 0F..1F ||
        regionWidth <= 0F || regionHeight <= 0F ||
        x1 < 0F || y1 < 0F || x2 > 1F || y2 > 1F
    ) {
        return false
    }

    if (!shouldBlur) return true

    // Full-height portraits are legitimate model output and used to be thrown
    // away by the old fixed 60% limit. Keep them when confidence is strong, but
    // retain the conservative limit for weak detections that could otherwise
    // turn an abstract wallpaper into a nearly full-screen mosaic.
    val maximumArea = if (confidence >= LARGE_REGION_CONFIDENCE) {
        MAX_HIGH_CONFIDENCE_PROTECTION_AREA
    } else {
        MAX_LOW_CONFIDENCE_PROTECTION_AREA
    }
    return area <= maximumArea
}

private const val LARGE_REGION_CONFIDENCE = 0.50F
private const val MAX_LOW_CONFIDENCE_PROTECTION_AREA = 0.60F
private const val MAX_HIGH_CONFIDENCE_PROTECTION_AREA = 0.98F
