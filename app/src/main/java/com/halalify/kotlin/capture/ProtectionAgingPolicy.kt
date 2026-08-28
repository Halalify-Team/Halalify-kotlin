package com.halalify.kotlin.capture

import com.halalify.kotlin.model.Detection
import kotlin.math.max
import kotlin.math.min

/** Evidence that is safe to use when ageing unmatched protected regions. */
internal data class ProtectionAgingDecision(
    val contentChanged: Boolean,
    val safetyRefresh: Boolean,
)

/**
 * MediaProjection replaces Halalify's trusted overlay pixels with black, so
 * an empty periodic inference cannot prove that the protected subject left.
 * A changed frame may still belong to the same loading page, so an unmatched
 * track ages only when the same clean pass observes protected content
 * elsewhere. Accessibility navigation events own removal when the whole
 * protected page or image disappears.
 */
internal fun decideProtectionAging(
    contentChanged: Boolean,
    reason: FrameAnalysisReason,
    hasProtectedObservation: Boolean,
): ProtectionAgingDecision = ProtectionAgingDecision(
    contentChanged = contentChanged && hasProtectedObservation,
    safetyRefresh =
        reason == FrameAnalysisReason.SAFETY_REFRESH && hasProtectedObservation,
)

/**
 * A browser compositor can expose the previous page to MediaProjection for a
 * few frames after accessibility has already reported the new page. Ignore
 * only boxes that spatially match the protection just discarded; genuinely
 * new people elsewhere remain eligible for immediate protection.
 */
internal fun filterRecentlyDiscardedDetections(
    detections: List<Detection>,
    discardedDetections: List<Detection>,
    suppressionActive: Boolean,
): List<Detection> {
    if (!suppressionActive || discardedDetections.isEmpty()) return detections
    return detections.filterNot { detection ->
        detection.shouldBlur && discardedDetections.any { discarded ->
            overlapOverSmallerArea(discarded, detection) >=
                RECENTLY_DISCARDED_OVERLAP
        }
    }
}

private fun overlapOverSmallerArea(first: Detection, second: Detection): Float {
    val intersectionWidth =
        (min(first.x2, second.x2) - max(first.x1, second.x1)).coerceAtLeast(0F)
    val intersectionHeight =
        (min(first.y2, second.y2) - max(first.y1, second.y1)).coerceAtLeast(0F)
    val firstArea =
        (first.x2 - first.x1).coerceAtLeast(0F) *
            (first.y2 - first.y1).coerceAtLeast(0F)
    val secondArea =
        (second.x2 - second.x1).coerceAtLeast(0F) *
            (second.y2 - second.y1).coerceAtLeast(0F)
    val smallerArea = min(firstArea, secondArea)
    return if (smallerArea > 0F) {
        intersectionWidth * intersectionHeight / smallerArea
    } else {
        0F
    }
}

private const val RECENTLY_DISCARDED_OVERLAP = 0.65F
