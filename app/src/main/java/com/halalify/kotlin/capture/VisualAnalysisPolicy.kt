package com.halalify.kotlin.capture

import com.halalify.kotlin.settings.BlurSettings

/** Maps capture activity to the visual coverage selected by the user. */
internal class VisualAnalysisPolicy(
    private val settings: BlurSettings,
) {
    fun shouldAnalyze(reason: FrameAnalysisReason): Boolean = when (reason) {
        FrameAnalysisReason.INITIAL,
        FrameAnalysisReason.CONTENT_CHANGED,
        -> settings.hasVisualProtection
        FrameAnalysisReason.STABILIZATION -> settings.blurVideos
        FrameAnalysisReason.SAFETY_REFRESH -> settings.blurImages
    }
}
