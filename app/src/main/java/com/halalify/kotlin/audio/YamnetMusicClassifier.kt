package com.halalify.kotlin.audio

import android.content.Context
import org.tensorflow.lite.InterpreterApi
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class MusicClassification(
    val musicProbability: Float,
    val label: String,
)

/**
 * Runs the small, frozen YAMNet classifier and the locally trained four-class head.
 *
 * YAMNet is deliberately kept separate from DTLN: it answers "is this music?", while
 * DTLN produces the speech-only waveform. Keeping the two contracts separate makes it
 * possible to retrain the head without changing the separator.
 */
internal class YamnetMusicClassifier(
    context: Context,
) : AutoCloseable {
    private val yamnet = InterpreterApi.create(
        context.assetBuffer(YAMNET_ASSET),
        InterpreterApi.Options()
            .setNumThreads(2)
            .setRuntime(InterpreterApi.Options.TfLiteRuntime.PREFER_SYSTEM_OVER_APPLICATION),
    )
    private val head = InterpreterApi.create(
        context.assetBuffer(HEAD_ASSET),
        InterpreterApi.Options()
            .setNumThreads(2)
            .setRuntime(InterpreterApi.Options.TfLiteRuntime.PREFER_SYSTEM_OVER_APPLICATION),
    )

    private val waveform = FloatArray(YAMNET_SAMPLES)
    private val yamnetScores = Array(1) { FloatArray(YAMNET_CLASSES) }
    private val headLogits = Array(1) { FloatArray(LABELS.size) }

    init {
        check(yamnet.getInputTensor(0).numElements() == YAMNET_SAMPLES) {
            "YAMNet input must contain $YAMNET_SAMPLES samples."
        }
        check(yamnet.getOutputTensor(0).numElements() == YAMNET_CLASSES) {
            "YAMNet output must contain $YAMNET_CLASSES scores."
        }
        check(head.getInputTensor(0).numElements() == YAMNET_CLASSES) {
            "Music head input must contain $YAMNET_CLASSES scores."
        }
        check(head.getOutputTensor(0).numElements() == LABELS.size) {
            "Music head output must contain ${LABELS.size} class scores."
        }
    }

    @Synchronized
    fun classify(pcm: ShortArray): MusicClassification {
        waveform.fill(0F)
        val count = minOf(pcm.size, waveform.size)
        for (index in 0 until count) waveform[index] = pcm[index] / 32768F
        yamnet.run(waveform, yamnetScores)
        head.run(yamnetScores, headLogits)

        val probabilities = softmax(headLogits[0])
        // The trained label order is music, other, speech, speech_music. Treating the
        // mixed class as music avoids letting a soundtrack with dialogue pass through.
        val musicProbability = (probabilities[0] + probabilities[3]).coerceIn(0F, 1F)
        var best = 0
        for (index in 1 until probabilities.size) {
            if (probabilities[index] > probabilities[best]) best = index
        }
        return MusicClassification(musicProbability, LABELS[best])
    }

    override fun close() {
        head.close()
        yamnet.close()
    }

    private fun softmax(logits: FloatArray): FloatArray {
        var maximum = Float.NEGATIVE_INFINITY
        for (value in logits) maximum = maxOf(maximum, value)
        val result = FloatArray(logits.size)
        var sum = 0F
        for (index in logits.indices) {
            result[index] = kotlin.math.exp((logits[index] - maximum).toDouble()).toFloat()
            sum += result[index]
        }
        if (sum > 0F) for (index in result.indices) result[index] /= sum
        return result
    }

    private fun Context.assetBuffer(name: String): ByteBuffer {
        assets.openFd(name).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                return channel.map(
                    FileInputStream.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                ).order(ByteOrder.nativeOrder())
            }
        }
    }

    internal companion object {
        const val YAMNET_ASSET = "audio/yamnet/yamnet.tflite"
        const val HEAD_ASSET = "audio/yamnet/yamnet_music_head.tflite"
        const val YAMNET_SAMPLES = 15_600
        const val YAMNET_CLASSES = 521
        val LABELS = arrayOf("music", "other", "speech", "speech_music")

        fun isModelInstalled(context: Context): Boolean = runCatching {
            context.assets.open(YAMNET_ASSET).use { }
            context.assets.open(HEAD_ASSET).use { }
            true
        }.getOrDefault(false)
    }
}
