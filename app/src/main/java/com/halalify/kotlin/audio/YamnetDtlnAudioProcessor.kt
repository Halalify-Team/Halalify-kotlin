package com.halalify.kotlin.audio

import android.content.Context

/** Connects the YAMNet detector to the streaming DTLN separator. */
internal class YamnetDtlnAudioProcessor(
    context: Context,
    private val musicThreshold: Float = DEFAULT_MUSIC_THRESHOLD,
) : AudioFrameProcessor {
    private val classifier = YamnetMusicClassifier(context)
    private val separator: DtlnAudioIsolationEngine? = if (
        DtlnAudioIsolationEngine.isModelInstalled(context)
    ) {
        runCatching { DtlnAudioIsolationEngine(context) }.getOrNull()
    } else {
        null
    }

    override val frameSamples: Int = FRAME_SAMPLES
    val isolationAvailable: Boolean get() = separator != null

    @Synchronized
    override fun process(pcm: ShortArray): AudioIsolationResult {
        require(pcm.size == frameSamples)
        val classification = classifier.classify(pcm)
        val speech = separator?.process(pcm) ?: pcm.copyOf()
        return AudioIsolationResult(
            musicScore = classification.musicProbability,
            musicDetected = classification.musicProbability >= musicThreshold,
            speechPcm = speech,
            isolationActive = separator != null,
            detectorLabel = classification.label,
        )
    }

    override fun close() {
        separator?.close()
        classifier.close()
    }

    private companion object {
        const val FRAME_SAMPLES = 16_000
        const val DEFAULT_MUSIC_THRESHOLD = 0.55F
    }
}
