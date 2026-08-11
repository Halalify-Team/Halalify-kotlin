package com.halalify.kotlin.media

import com.halalify.kotlin.model.Detection
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps a protected region stable across intermittent or changing classifications.
 * Once a region is protected, any detection at the same location refreshes it.
 */
internal class ProtectionTracker(
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    private val matchingIou: Float = DEFAULT_MATCHING_IOU,
) {
    private class Track(
        var detection: Detection,
        var lastSeenAtMs: Long,
    )

    private val tracks = mutableListOf<Track>()

    fun update(detections: List<Detection>, nowMs: Long): List<Detection> {
        tracks.removeAll { nowMs - it.lastSeenAtMs > retentionMs }

        val availableTracks = tracks.toMutableList()
        val matchedDetections = mutableSetOf<Detection>()
        detections.sortedByDescending(Detection::confidence).forEach { detection ->
            val track = availableTracks.maxByOrNull { candidate ->
                intersectionOverUnion(candidate.detection, detection)
            } ?: return@forEach
            if (intersectionOverUnion(track.detection, detection) < matchingIou) return@forEach

            // Preserve the protection decision even when a later frame briefly
            // changes the class assigned to the same person.
            track.detection = detection.copy(shouldBlur = true)
            track.lastSeenAtMs = nowMs
            availableTracks.remove(track)
            matchedDetections += detection
        }

        detections.asSequence()
            .filter(Detection::shouldBlur)
            .filterNot(matchedDetections::contains)
            .forEach { detection -> tracks += Track(detection, nowMs) }

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

    private companion object {
        const val DEFAULT_RETENTION_MS = 3_000L
        const val DEFAULT_MATCHING_IOU = 0.20F
    }
}
