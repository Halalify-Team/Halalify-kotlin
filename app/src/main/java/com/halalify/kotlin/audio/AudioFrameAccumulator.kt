package com.halalify.kotlin.audio

/** Collects arbitrary AudioRecord reads into fixed-size inference frames. */
internal class AudioFrameAccumulator(private val frameSamples: Int) {
    private val pending = ShortArray(frameSamples)
    private var pendingSamples = 0

    init {
        require(frameSamples > 0) { "Audio frame size must be positive." }
    }

    fun append(samples: ShortArray, count: Int, onFrame: (ShortArray) -> Unit) {
        require(count in 0..samples.size) { "Audio sample count is outside the source buffer." }
        var sourceOffset = 0
        while (sourceOffset < count) {
            val copied = minOf(frameSamples - pendingSamples, count - sourceOffset)
            samples.copyInto(
                destination = pending,
                destinationOffset = pendingSamples,
                startIndex = sourceOffset,
                endIndex = sourceOffset + copied,
            )
            sourceOffset += copied
            pendingSamples += copied
            if (pendingSamples == frameSamples) {
                onFrame(pending.copyOf())
                pendingSamples = 0
            }
        }
    }

    fun reset() {
        pendingSamples = 0
    }
}
