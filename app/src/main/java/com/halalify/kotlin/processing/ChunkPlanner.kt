package com.halalify.kotlin.processing

internal data class ChunkPlan(
    val index: Int,
    val startSeconds: Int,
    val durationSeconds: Int,
)

internal object ChunkPlanner {
    fun buildPlans(durationSeconds: Int): List<ChunkPlan> {
        val totalDuration = durationSeconds.coerceAtLeast(1)
        val plans = mutableListOf<ChunkPlan>()
        var start = 0
        var index = 0

        while (start < totalDuration) {
            val targetDuration = when (index) {
                0 -> 10
                1, 2 -> 15
                3 -> 30
                else -> 60
            }
            val duration = (totalDuration - start).coerceAtMost(targetDuration).coerceAtLeast(1)
            plans += ChunkPlan(
                index = index,
                startSeconds = start,
                durationSeconds = duration,
            )
            start += duration
            index += 1
        }

        return plans
    }
}
