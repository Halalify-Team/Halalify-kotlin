package com.halalify.kotlin.capture

import com.halalify.kotlin.model.Detection
import kotlin.math.max
import kotlin.math.min

internal fun requiresNewProtectionConfirmation(
    hasExistingProtection: Boolean,
    reason: FrameAnalysisReason,
): Boolean = !hasExistingProtection &&
    reason == FrameAnalysisReason.INITIAL

/**
 * Prevents a single unstable model result from creating a new overlay on an
 * otherwise clean screen. Existing protected tracks bypass this gate.
 */
internal class NewProtectionConfirmation(
    private val matchingIou: Float = DEFAULT_MATCHING_IOU,
    private val immediateConfidence: Float = DEFAULT_IMMEDIATE_CONFIDENCE,
) {
    private var previousCandidates: List<Detection> = emptyList()

    init {
        require(matchingIou in 0F..1F)
        require(immediateConfidence in 0F..1F)
    }

    fun apply(
        detections: List<Detection>,
        confirmationRequired: Boolean,
    ): List<Detection> {
        if (!confirmationRequired) {
            previousCandidates = emptyList()
            return detections
        }

        val candidates = detections.filter(Detection::shouldBlur)
        if (candidates.isEmpty()) {
            previousCandidates = emptyList()
            return detections
        }

        val confirmed = candidates.filter { current ->
            current.confidence >= immediateConfidence ||
                previousCandidates.any { previous ->
                    previous.classId == current.classId &&
                        intersectionOverUnion(previous, current) >= matchingIou
                }
        }.toSet()
        previousCandidates = candidates

        return detections.map { detection ->
            if (detection.shouldBlur && detection !in confirmed) {
                detection.copy(shouldBlur = false)
            } else {
                detection
            }
        }
    }

    fun reset() {
        previousCandidates = emptyList()
    }

    private fun intersectionOverUnion(first: Detection, second: Detection): Float {
        val intersectionWidth =
            (min(first.x2, second.x2) - max(first.x1, second.x1)).coerceAtLeast(0F)
        val intersectionHeight =
            (min(first.y2, second.y2) - max(first.y1, second.y1)).coerceAtLeast(0F)
        val intersection = intersectionWidth * intersectionHeight
        if (intersection <= 0F) return 0F

        val firstArea = (first.x2 - first.x1) * (first.y2 - first.y1)
        val secondArea = (second.x2 - second.x1) * (second.y2 - second.y1)
        val union = firstArea + secondArea - intersection
        return if (union > 0F) intersection / union else 0F
    }

    private companion object {
        const val DEFAULT_MATCHING_IOU = 0.30F
        // Strong, unambiguous detections should not wait for a second model
        // pass. Weaker results still require temporal confirmation to avoid
        // flashing a block over text, icons, or male/ignored subjects.
        const val DEFAULT_IMMEDIATE_CONFIDENCE = 0.50F
    }
}
