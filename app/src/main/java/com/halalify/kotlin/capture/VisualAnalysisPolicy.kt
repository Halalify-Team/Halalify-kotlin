package com.halalify.kotlin.capture

import com.halalify.kotlin.settings.BlurSettings

/** Maps capture activity to the visual coverage selected by the user. */
internal class VisualAnalysisPolicy(
    private val settings: BlurSettings,
) {
    fun shouldAnalyze(reason: FrameAnalysisReason): Boolean = when (reason) {
        FrameAnalysisReason.INITIAL,
        FrameAnalysisReason.CONTENT_CHANGED,
        FrameAnalysisReason.STABILIZATION,
        -> settings.hasVisualProtection
        // Safety refreshes are also required for video-only coverage: when a
        // video stops or a page settles, there may be no contentChanged event
        // to age out a previously protected region.
        FrameAnalysisReason.SAFETY_REFRESH -> settings.hasVisualProtection
    }
}
