package com.halalify.kotlin.media

import com.halalify.kotlin.model.Detection
import kotlin.math.hypot
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
        val id: Long,
        var detection: Detection,
        var missedContentChanges: Int = 0,
    )

    private val tracks = mutableListOf<Track>()
    private var nextProtectionId = 1L

    init {
        require(maxMissedContentChanges > 0) { "Missed content-change limit must be positive." }
        require(matchingIou in 0F..1F) { "Matching IoU must be between 0 and 1." }
        require(smoothingAlpha in 0F..1F) { "Smoothing alpha must be between 0 and 1." }
    }

    fun update(
        detections: List<Detection>,
        contentChanged: Boolean = false,
        safetyRefresh: Boolean = false,
    ): List<Detection> {
        val availableTracks = tracks.toMutableList()
        val matchedDetections = mutableSetOf<Int>()
        detections.withIndex()
            .sortedByDescending { it.value.confidence }
            .forEach { indexedDetection ->
            val detection = indexedDetection.value
            // On a real content change, an unprotected detection represents
            // the new subject. Do not attach it to the previous protected
            // track, otherwise the old blur can remain over the new page.
            if ((contentChanged || safetyRefresh) && !detection.shouldBlur) return@forEach
            val track = availableTracks
                .map { candidate -> candidate to matchScore(candidate.detection, detection) }
                .filter { (_, score) -> score != null }
                .maxByOrNull { (_, score) -> score ?: Float.NEGATIVE_INFINITY }
                ?.first
                ?: return@forEach

            // Preserve the protection decision even when a later frame briefly
            // changes the class assigned to the same person.
            track.detection = smooth(track.detection, detection).copy(
                shouldBlur = true,
                protectionId = track.id,
            )
            track.missedContentChanges = 0
            availableTracks.remove(track)
            matchedDetections += indexedDetection.index
        }

        val newProtectedDetections = detections.withIndex().asSequence()
            .filter { it.value.shouldBlur }
            .filterNot { matchedDetections.contains(it.index) }
            .map { it.value }
            .toList()
        newProtectedDetections.forEach { detection ->
            val id = nextProtectionId++
            tracks += Track(
                id = id,
                detection = detection.copy(protectionId = id),
            )
        }

        // A static or periodically refreshed screen never ages protected regions.
        // When a new protected box appears after a real change, remove unmatched
        // old boxes immediately. The current frame already contains a confirmed
        // replacement, so retaining the previous page's boxes only creates the
        // stacked mosaics users see during a fast swipe.
        if (contentChanged) {
            availableTracks.forEach { track -> track.missedContentChanges += 1 }
            // A matched detection only confirms its own track. It must not
            // immediately delete a second person that the model missed in this
            // frame. Only a genuinely new protected box confirms replacement.
            val replacementConfirmed = newProtectedDetections.isNotEmpty()
            val expiryLimit = if (replacementConfirmed) {
                REPLACEMENT_CONFIRMATION_CHANGES.coerceAtMost(maxMissedContentChanges)
            } else {
                maxMissedContentChanges
            }
            tracks.removeAll { track -> track.missedContentChanges >= expiryLimit }
        }

        // If a page moved and the detector missed the subject during that
        // movement, do not keep the old region forever after the page settles.
        // A safety refresh is deliberately conservative: tracks that were
        // never missed remain protected through a transient detector miss.
        if (safetyRefresh) {
            // A clean probe can still be missed by the model. Count it like a
            // changed frame instead of removing protection on one noisy result.
            availableTracks.forEach { track -> track.missedContentChanges += 1 }
            tracks.removeAll { track ->
                track.missedContentChanges >= maxMissedContentChanges
            }
        }

        return tracks.map(Track::detection)
    }

    fun reset() {
        tracks.clear()
        nextProtectionId = 1L
    }

    /**
     * Follows content moved by an accessibility scroll event. Tracks are kept
     * while any part of their box is on screen and removed only after the box
     * has moved completely beyond the display edge.
     */
    fun offset(deltaX: Float, deltaY: Float): List<Detection> {
        if (deltaX == 0F && deltaY == 0F) return currentDetections()

        tracks.forEach { track ->
            track.detection = track.detection.copy(
                x1 = track.detection.x1 + deltaX,
                y1 = track.detection.y1 + deltaY,
                x2 = track.detection.x2 + deltaX,
                y2 = track.detection.y2 + deltaY,
            )
        }
        tracks.removeAll { track -> !track.detection.intersectsDisplay() }
        return currentDetections()
    }

    private fun currentDetections(): List<Detection> = tracks.map(Track::detection)

    private fun Detection.intersectsDisplay(): Boolean =
        x2 > 0F && x1 < 1F && y2 > 0F && y1 < 1F

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

    /** Match a fast swipe by centre when two correct boxes barely overlap. */
    private fun matchScore(first: Detection, second: Detection): Float? {
        val iou = intersectionOverUnion(first, second)
        if (iou >= matchingIou) return iou

        val firstWidth = (first.x2 - first.x1).coerceAtLeast(0F)
        val firstHeight = (first.y2 - first.y1).coerceAtLeast(0F)
        val secondWidth = (second.x2 - second.x1).coerceAtLeast(0F)
        val secondHeight = (second.y2 - second.y1).coerceAtLeast(0F)
        if (firstWidth <= 0F || firstHeight <= 0F || secondWidth <= 0F || secondHeight <= 0F) {
            return null
        }

        val widthRatio = secondWidth / firstWidth
        val heightRatio = secondHeight / firstHeight
        if (widthRatio !in MIN_SIZE_RATIO..MAX_SIZE_RATIO ||
            heightRatio !in MIN_SIZE_RATIO..MAX_SIZE_RATIO
        ) {
            return null
        }

        val firstCenterX = (first.x1 + first.x2) * 0.5F
        val firstCenterY = (first.y1 + first.y2) * 0.5F
        val secondCenterX = (second.x1 + second.x2) * 0.5F
        val secondCenterY = (second.y1 + second.y2) * 0.5F
        val centerDistance = hypot(
            firstCenterX - secondCenterX,
            firstCenterY - secondCenterY,
        )
        if (centerDistance > MAX_CENTER_DISTANCE) return null

        return 0.01F - centerDistance
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
        // Three changed frames tolerate the two-frame oscillation observed on
        // the emulator. Navigation and scroll events reset tracks immediately.
        const val DEFAULT_MAX_MISSED_CONTENT_CHANGES = 3
        const val DEFAULT_MATCHING_IOU = 0.15F
        const val REPLACEMENT_CONFIRMATION_CHANGES = 1
        const val MAX_CENTER_DISTANCE = 0.35F
        const val MIN_SIZE_RATIO = 0.45F
        const val MAX_SIZE_RATIO = 2.20F

        // Follow the latest detector box immediately so a page flip cannot
        // leave part of the previous location covered.
        const val DEFAULT_SMOOTHING_ALPHA = 1F
    }
}
