package com.halalify.kotlin.capture

internal enum class ReplacementRefreshAction {
    INACTIVE,
    KEEP,
    EXPIRE,
}

/** Chooses one atomic outcome for stale protection during video/page motion. */
internal fun decideReplacementRefreshAction(
    analysesRemaining: Int,
    hasProtectedObservation: Boolean,
): ReplacementRefreshAction = when {
    analysesRemaining <= 0 -> ReplacementRefreshAction.INACTIVE
    hasProtectedObservation -> ReplacementRefreshAction.INACTIVE
    analysesRemaining == 1 -> ReplacementRefreshAction.EXPIRE
    else -> ReplacementRefreshAction.KEEP
}
