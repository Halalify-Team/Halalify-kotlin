package com.halalify.kotlin.media

import com.halalify.kotlin.model.Detection
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps a selected protected region stable across intermittent detections.
 * Only another selected detection at the same subject location can refresh it.
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
        val protectedCandidates = mutableListOf<IndexedValue<Detection>>()
        detections.withIndex()
            .filter { it.value.shouldBlur }
            .sortedByDescending { it.value.confidence }
            .forEach { candidate ->
                val duplicatesStrongerCandidate = protectedCandidates.any { stronger ->
                    overlapOverSmallerArea(stronger.value, candidate.value) >=
                        DUPLICATE_CONTAINMENT_OVERLAP
                }
                if (!duplicatesStrongerCandidate) protectedCandidates += candidate
            }

        protectedCandidates.forEach { indexedDetection ->
            val detection = indexedDetection.value
            val track = availableTracks
                .map { candidate -> candidate to matchScore(candidate.detection, detection) }
                .filter { (_, score) -> score != null }
                .maxByOrNull { (_, score) -> score ?: Float.NEGATIVE_INFINITY }
                ?.first
                ?: return@forEach

            // Preserve the protection decision even when a later frame briefly
            // changes the class assigned to the same person.
            track.detection = stabilize(track.detection, detection).copy(
                shouldBlur = true,
                protectionId = track.id,
            )
            track.missedContentChanges = 0
            availableTracks.remove(track)
            matchedDetections += indexedDetection.index
        }

        val newProtectedDetections = protectedCandidates.asSequence()
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
        // Detector passes can report different people from the same unchanged
        // screen. A newly observed box therefore cannot prove that every
        // unmatched person disappeared; age each missing track independently.
        if (contentChanged) {
            availableTracks.forEach { track -> track.missedContentChanges += 1 }
            tracks.removeAll { track ->
                track.missedContentChanges >= maxMissedContentChanges
            }
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
        val intersection = intersectionArea(first, second)
        if (intersection <= 0F) return 0F

        val firstArea = area(first)
        val secondArea = area(second)
        val union = firstArea + secondArea - intersection
        return if (union > 0F) intersection / union else 0F
    }

    private fun overlapOverSmallerArea(first: Detection, second: Detection): Float {
        val smallerArea = min(area(first), area(second))
        return if (smallerArea > 0F) {
            intersectionArea(first, second) / smallerArea
        } else {
            0F
        }
    }

    private fun intersectionArea(first: Detection, second: Detection): Float {
        val width = (min(first.x2, second.x2) - max(first.x1, second.x1))
            .coerceAtLeast(0F)
        val height = (min(first.y2, second.y2) - max(first.y1, second.y1))
            .coerceAtLeast(0F)
        return width * height
    }

    private fun area(detection: Detection): Float =
        (detection.x2 - detection.x1).coerceAtLeast(0F) *
            (detection.y2 - detection.y1).coerceAtLeast(0F)

    /** Match a fast swipe by centre when two correct boxes barely overlap. */
    private fun matchScore(first: Detection, second: Detection): Float? {
        val iou = intersectionOverUnion(first, second)
        if (iou >= matchingIou) return iou

        // Portrait detail tiles often detect a tighter part of the same body.
        // IoU is low when one correct box is nested inside another, but a high
        // overlap relative to the smaller box still proves spatial identity.
        val containment = overlapOverSmallerArea(first, second)
        if (containment >= DUPLICATE_CONTAINMENT_OVERLAP) {
            return 0.50F + containment * 0.50F
        }

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
        val averageBoxDiagonal = (
            hypot(firstWidth, firstHeight) +
                hypot(secondWidth, secondHeight)
            ) * 0.5F
        val allowedCenterDistance = min(
            MAX_CENTER_DISTANCE,
            averageBoxDiagonal * MAX_CENTER_DISTANCE_IN_BOX_DIAGONALS,
        )
        if (centerDistance > allowedCenterDistance) return null

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

    /**
     * Detail tiles can return a tighter portion of the same body. Prefer the
     * larger single observation only when one box is genuinely nested inside
     * the other. Never union partially overlapping observations: repeating
     * that operation makes the region grow permanently across the screen.
     */
    private fun stabilize(previous: Detection, current: Detection): Detection {
        val previousArea = area(previous)
        val currentArea = area(current)
        val largerArea = max(previousArea, currentArea)
        val sizeRatio = if (largerArea > 0F) {
            min(previousArea, currentArea) / largerArea
        } else {
            1F
        }
        val isNestedTileExtent =
            sizeRatio <= MAX_TILE_EXTENT_SIZE_RATIO &&
                overlapOverSmallerArea(previous, current) >= STABLE_TILE_OVERLAP
        if (isNestedTileExtent) {
            val largerObservation = if (previousArea >= currentArea) previous else current
            return current.copy(
                x1 = largerObservation.x1,
                y1 = largerObservation.y1,
                x2 = largerObservation.x2,
                y2 = largerObservation.y2,
            )
        }
        return smooth(previous, current)
    }

    private companion object {
        // Four changed observations let independently detected people coexist
        // through a full four-region cycle. Navigation and scroll events still
        // clear or move obsolete tracks immediately.
        const val DEFAULT_MAX_MISSED_CONTENT_CHANGES = 4
        const val DEFAULT_MATCHING_IOU = 0.15F
        const val DUPLICATE_CONTAINMENT_OVERLAP = 0.70F
        const val STABLE_TILE_OVERLAP = 0.85F
        const val MAX_TILE_EXTENT_SIZE_RATIO = 0.75F
        const val MAX_CENTER_DISTANCE = 0.35F
        // An absolute screen-distance limit alone can associate two separate
        // image cards in the same column. Scale the fallback by the boxes too:
        // a large subject can move farther, while small neighbouring cards
        // must remain separate tracks.
        const val MAX_CENTER_DISTANCE_IN_BOX_DIAGONALS = 0.55F
        const val MIN_SIZE_RATIO = 0.45F
        const val MAX_SIZE_RATIO = 2.20F

        // Follow the latest detector box immediately so a page flip cannot
        // leave part of the previous location covered.
        const val DEFAULT_SMOOTHING_ALPHA = 1F
    }
}
