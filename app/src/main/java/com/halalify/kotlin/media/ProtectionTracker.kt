package com.halalify.kotlin.media

import com.halalify.kotlin.model.Detection
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps a protected region stable across intermittent or changing classifications.
 * Once a region is protected, any detection at the same location refreshes it.
 */
internal class ProtectionTracker(
    private val maxMissedContentChanges: Int = DEFAULT_MAX_MISSED_CONTENT_CHANGES,
    private val matchingIou: Float = DEFAULT_MATCHING_IOU,
    private val smoothingAlpha: Float = DEFAULT_SMOOTHING_ALPHA,
) {
    private class Track(
        var detection: Detection,
        var missedContentChanges: Int = 0,
    )

    private val tracks = mutableListOf<Track>()

    init {
        require(maxMissedContentChanges > 0) { "Missed content-change limit must be positive." }
        require(matchingIou in 0F..1F) { "Matching IoU must be between 0 and 1." }
        require(smoothingAlpha in 0F..1F) { "Smoothing alpha must be between 0 and 1." }
    }

    fun update(
        detections: List<Detection>,
        contentChanged: Boolean = false,
    ): List<Detection> {
        val availableTracks = tracks.toMutableList()
        val matchedDetections = mutableSetOf<Detection>()
        detections.sortedByDescending(Detection::confidence).forEach { detection ->
            // On a real content change, an unprotected detection represents
            // the new subject. Do not attach it to the previous protected
            // track, otherwise the old blur can remain over the new page.
            if (contentChanged && !detection.shouldBlur) return@forEach
            val track = availableTracks.maxByOrNull { candidate ->
                intersectionOverUnion(candidate.detection, detection)
            } ?: return@forEach
            if (intersectionOverUnion(track.detection, detection) < matchingIou) return@forEach

            // Preserve the protection decision even when a later frame briefly
            // changes the class assigned to the same person.
            track.detection = smooth(track.detection, detection).copy(shouldBlur = true)
            track.missedContentChanges = 0
            availableTracks.remove(track)
            matchedDetections += detection
        }

        detections.asSequence()
            .filter(Detection::shouldBlur)
            .filterNot(matchedDetections::contains)
            .forEach { detection -> tracks += Track(detection) }

        // A static or periodically refreshed screen never ages protected regions.
        // Only real screen changes age unmatched regions, preventing timed flicker.
        if (contentChanged) {
            availableTracks.forEach { track -> track.missedContentChanges += 1 }
            tracks.removeAll { track ->
                track.missedContentChanges >= maxMissedContentChanges
            }
        }

        return tracks.map(Track::detection)
    }

    fun reset() {
        tracks.clear()
    }

    private fun intersectionOverUnion(first: Detection, second: Detection): Float {
        val intersectionWidth = (min(first.x2, second.x2) - max(first.x1, second.x1))
            .coerceAtLeast(0F)
        val intersectionHeight = (min(first.y2, second.y2) - max(first.y1, second.y1))
            .coerceAtLeast(0F)
        val intersection = intersectionWidth * intersectionHeight
        if (intersection <= 0F) return 0F

        val firstArea = (first.x2 - first.x1).coerceAtLeast(0F) *
                (first.y2 - first.y1).coerceAtLeast(0F)
        val secondArea = (second.x2 - second.x1).coerceAtLeast(0F) *
                (second.y2 - second.y1).coerceAtLeast(0F)
        val union = firstArea + secondArea - intersection
        return if (union > 0F) intersection / union else 0F
    }

    private fun smooth(previous: Detection, current: Detection): Detection {
        val alpha = smoothingAlpha.coerceIn(0F, 1F)
        fun blend(old: Float, new: Float): Float = old + (new - old) * alpha
        return current.copy(
            x1 = blend(previous.x1, current.x1),
            y1 = blend(previous.y1, current.y1),
            x2 = blend(previous.x2, current.x2),
            y2 = blend(previous.y2, current.y2),
        )
    }

    private companion object {
        // Allow one grace frame so the overlay itself does not trigger
        // immediate track expiry (feedback loop). At 33ms check intervals
        // stale regions still clear in ~66ms instead of the old ~300ms.
        const val DEFAULT_MAX_MISSED_CONTENT_CHANGES = 2
        const val DEFAULT_MATCHING_IOU = 0.20F

        // Follow the latest detector box immediately so a page flip cannot
        // leave part of the previous location covered.
        const val DEFAULT_SMOOTHING_ALPHA = 1F
    }
}
