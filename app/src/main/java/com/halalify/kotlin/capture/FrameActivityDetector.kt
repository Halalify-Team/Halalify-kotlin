package com.halalify.kotlin.capture

/**
 * Avoids expensive inference while the captured screen is unchanged. A short
 * burst after each change improves detection stability, then only a periodic
 * safety refresh is performed.
 */
internal class FrameActivityDetector(
    private val changedPixelRatio: Float = DEFAULT_CHANGED_PIXEL_RATIO,
    private val channelDifference: Int = DEFAULT_CHANNEL_DIFFERENCE,
    private val safetyRefreshMs: Long = DEFAULT_SAFETY_REFRESH_MS,
    private val burstAnalyses: Int = DEFAULT_BURST_ANALYSES,
    private val burstIntervalMs: Long = DEFAULT_BURST_INTERVAL_MS,
) {
    private var baseline: IntArray? = null
    private var lastAnalysisAtMs = Long.MIN_VALUE
    private var remainingBurstAnalyses = 0

    init {
        require(changedPixelRatio in 0F..1F) { "Changed pixel ratio must be between 0 and 1." }
        require(channelDifference >= 0) { "Channel difference must not be negative." }
        require(safetyRefreshMs > 0L) { "Safety refresh interval must be positive." }
        require(burstAnalyses > 0) { "Burst analysis count must be positive." }
        require(burstIntervalMs >= 0L) { "Burst interval must not be negative." }
    }

    fun analysisReason(sample: IntArray, nowMs: Long): FrameAnalysisReason? {
        val previous = baseline
        if (previous == null || previous.size != sample.size) {
            baseline = sample.copyOf()
            remainingBurstAnalyses = (burstAnalyses - 1).coerceAtLeast(0)
            lastAnalysisAtMs = nowMs
            return FrameAnalysisReason.INITIAL
        }

        val contentChanged = changedRatio(previous, sample) >= changedPixelRatio
        val burstDue = remainingBurstAnalyses > 0 &&
                elapsedSinceLastAnalysis(nowMs) >= burstIntervalMs
        val safetyRefreshDue = elapsedSinceLastAnalysis(nowMs) >= safetyRefreshMs
        if (!contentChanged && !burstDue && !safetyRefreshDue) return null

        baseline = sample.copyOf()
        if (contentChanged) {
            remainingBurstAnalyses = (burstAnalyses - 1).coerceAtLeast(0)
        } else if (burstDue) {
            remainingBurstAnalyses -= 1
        }
        lastAnalysisAtMs = nowMs
        return when {
            contentChanged -> FrameAnalysisReason.CONTENT_CHANGED
            burstDue -> FrameAnalysisReason.STABILIZATION
            else -> FrameAnalysisReason.SAFETY_REFRESH
        }
    }

    fun reset() {
        baseline = null
        lastAnalysisAtMs = Long.MIN_VALUE
        remainingBurstAnalyses = 0
    }

    private fun changedRatio(previous: IntArray, current: IntArray): Float {
        if (current.isEmpty()) return 0F
        var changed = 0
        var compared = 0
        for (index in current.indices) {
            val before = previous[index]
            val after = current[index]
            // Negative values represent samples hidden by our own overlay.
            // Ignoring them prevents the mosaic from looking like page activity.
            if (before < 0 || after < 0) continue
            compared += 1
            val redDifference = kotlin.math.abs((before ushr 16 and 0xFF) - (after ushr 16 and 0xFF))
            val greenDifference = kotlin.math.abs((before ushr 8 and 0xFF) - (after ushr 8 and 0xFF))
            val blueDifference = kotlin.math.abs((before and 0xFF) - (after and 0xFF))
            if (redDifference + greenDifference + blueDifference >= channelDifference) changed += 1
        }
        // Keep the threshold relative to the full sampled display. Otherwise a
        // large protected region leaves only a handful of comparable samples,
        // and one animated toolbar pixel can look like a full page change.
        return if (compared > 0) changed.toFloat() / current.size else 0F
    }

    private fun elapsedSinceLastAnalysis(nowMs: Long): Long =
        if (lastAnalysisAtMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastAnalysisAtMs

    private companion object {
        // Roughly seven of the 20x32 samples must move. This ignores a blinking
        // cursor or status-bar animation while still reacting to real page
        // motion immediately.
        const val DEFAULT_CHANGED_PIXEL_RATIO = 0.01F
        const val DEFAULT_CHANNEL_DIFFERENCE = 32
        const val DEFAULT_SAFETY_REFRESH_MS = 5_000L
        const val DEFAULT_BURST_ANALYSES = 3
        const val DEFAULT_BURST_INTERVAL_MS = 150L
    }
}

internal enum class FrameAnalysisReason {
    INITIAL,
    CONTENT_CHANGED,
    STABILIZATION,
    SAFETY_REFRESH,
}
