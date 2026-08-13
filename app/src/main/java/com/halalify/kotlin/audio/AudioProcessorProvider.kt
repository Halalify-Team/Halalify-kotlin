package com.halalify.kotlin.audio

import android.content.Context

internal data class AudioProcessorSelection(
    val processor: AudioFrameProcessor?,
    val unavailableReason: String? = null,
)

internal fun interface AudioProcessorProvider {
    fun create(): AudioProcessorSelection
}

/** Selects the best installed audio pipeline without exposing model details to the service. */
internal class BundledAudioProcessorProvider(context: Context) : AudioProcessorProvider {
    private val appContext = context.applicationContext

    override fun create(): AudioProcessorSelection {
        val failures = mutableListOf<String>()
        if (YamnetMusicClassifier.isModelInstalled(appContext)) {
            try {
                return AudioProcessorSelection(YamnetDtlnAudioProcessor(appContext))
            } catch (error: Exception) {
                failures += error.message ?: error.javaClass.simpleName
            }
        }
        if (NativeMusicIsolationEngine.isModelInstalled(appContext)) {
            try {
                return AudioProcessorSelection(NativeMusicIsolationEngine(appContext))
            } catch (error: Exception) {
                failures += error.message ?: error.javaClass.simpleName
            }
        }

        val reason = failures.joinToString(separator = "; ")
            .ifEmpty { "YAMNet/DTLN audio assets are not installed" }
        return AudioProcessorSelection(processor = null, unavailableReason = reason)
    }
}
