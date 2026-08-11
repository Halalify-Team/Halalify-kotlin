package com.halalify.kotlin.audio

import android.content.Context
import java.io.Closeable
import kotlin.math.sqrt

internal data class AudioIsolationResult(
    /** Residual energy ratio: input minus the model's speech-only output. */
    val musicScore: Float,
    val musicDetected: Boolean,
    /** PCM16 mono speech stem returned by the model. */
    val speechPcm: ShortArray,
)

internal interface AudioFrameProcessor : Closeable {
    val frameSamples: Int
    fun process(pcm: ShortArray): AudioIsolationResult
}

/**
 * LiteRT bridge for a mono waveform-to-waveform model.
 *
 * The bundled model is intentionally optional. The expected asset accepts one second of
 * normalized float32 mono audio and returns a float32 speech-only waveform of the same size.
 */
internal class NativeMusicIsolationEngine(
    context: Context,
    private val musicThreshold: Float = DEFAULT_MUSIC_THRESHOLD,
) : AudioFrameProcessor {
    override val frameSamples: Int = MODEL_FRAME_SAMPLES
    private var nativeHandle = nativeCreate(
        context.assets,
        MODEL_ASSET,
        MODEL_SAMPLE_RATE,
        frameSamples,
        musicThreshold,
    )

    @Synchronized
    override fun process(pcm: ShortArray): AudioIsolationResult {
        check(nativeHandle != 0L) { "Audio isolation engine is closed." }
        require(pcm.size == frameSamples) {
            "Audio model requires exactly $frameSamples mono samples."
        }
        val speech = ShortArray(frameSamples)
        val score = nativeProcess(nativeHandle, pcm, speech)
        return AudioIsolationResult(
            musicScore = score,
            musicDetected = score >= musicThreshold,
            speechPcm = speech,
        )
    }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(
        assetManager: android.content.res.AssetManager,
        assetName: String,
        sampleRate: Int,
        frameSamples: Int,
        musicThreshold: Float,
    ): Long

    private external fun nativeProcess(
        handle: Long,
        inputPcm: ShortArray,
        speechPcm: ShortArray,
    ): Float

    private external fun nativeDestroy(handle: Long)

    internal companion object {
        const val MODEL_ASSET = "halalify_music_voice_16k.tflite"
        const val MODEL_SAMPLE_RATE = 16_000
        const val MODEL_FRAME_SAMPLES = 16_000
        const val DEFAULT_MUSIC_THRESHOLD = 0.12F

        fun isModelInstalled(context: Context): Boolean = runCatching {
            context.assets.open(MODEL_ASSET).use { }
            true
        }.getOrDefault(false)

        init {
            System.loadLibrary("halalify_android_jni")
        }
    }
}

internal fun ShortArray.normalizedRms(): Float {
    if (isEmpty()) return 0F
    var sumSquares = 0.0
    for (sample in this) {
        val normalized = sample.toDouble() / Short.MAX_VALUE
        sumSquares += normalized * normalized
    }
    return sqrt(sumSquares / size).toFloat()
}
