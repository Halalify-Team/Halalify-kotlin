package com.halalify.kotlin.capture

import com.halalify.kotlin.model.Detection

/** True when a capture sample is covered by one of Halalify's own overlay regions. */
internal fun isProtectedSample(
    normalizedX: Float,
    normalizedY: Float,
    protectedDetections: List<Detection>,
): Boolean = protectedDetections.any { detection ->
    detection.shouldBlur &&
        normalizedX >= detection.x1 && normalizedX <= detection.x2 &&
        normalizedY >= detection.y1 && normalizedY <= detection.y2
}
