package com.halalify.kotlin.audio

import android.content.Context
import org.tensorflow.lite.InterpreterApi
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Streaming adapter for the two-state DTLN LiteRT graphs.
 *
 * DTLN works on 512-sample windows with a 256-sample hop. AudioRecord delivers larger
 * blocks, so this class owns the overlap-add and recurrent states and exposes the same
 * one-second frame contract used by PlaybackAudioMonitor.
 */
internal class DtlnAudioIsolationEngine(
    context: Context,
) : AutoCloseable {
    private val firstCore = InterpreterApi.create(
        context.assetBuffer(FIRST_MODEL_ASSET),
        options(),
    )
    private val secondCore = InterpreterApi.create(
        context.assetBuffer(SECOND_MODEL_ASSET),
        options(),
    )
    private val fft = RealFft(BLOCK_LENGTH)
    private val inputWindow = FloatArray(BLOCK_LENGTH)
    private val outputWindow = FloatArray(BLOCK_LENGTH)
    private val output = ShortArray(FRAME_SAMPLES)
    private val pendingInput = FloatArray(HOP_LENGTH)
    private var pendingInputCount = 0
    private val outputQueue = FloatArray(FRAME_SAMPLES + HOP_LENGTH)
    private var outputQueueCount = HOP_LENGTH
    private val magnitudeInput = Array(1) { Array(1) { FloatArray(FREQUENCY_BINS) } }
    private val firstMask = Array(1) { Array(1) { FloatArray(FREQUENCY_BINS) } }
    private var firstState = newState()
    private val firstStateOutput = newState()
    private val secondInput = Array(1) { Array(1) { FloatArray(BLOCK_LENGTH) } }
    private val secondOutput = Array(1) { Array(1) { FloatArray(BLOCK_LENGTH) } }
    private var secondState = newState()
    private val secondStateOutput = newState()

    init {
        check(firstCore.getInputTensor(0).numElements() == FREQUENCY_BINS) {
            "DTLN frequency input has an unexpected shape."
        }
        check(secondCore.getInputTensor(0).numElements() == BLOCK_LENGTH) {
            "DTLN time input has an unexpected shape."
        }
    }

    @Synchronized
    fun process(pcm: ShortArray): ShortArray {
        require(pcm.size == FRAME_SAMPLES) {
            "DTLN requires exactly $FRAME_SAMPLES mono samples."
        }
        for (sample in pcm) {
            pendingInput[pendingInputCount++] = sample / 32768F
            if (pendingInputCount == HOP_LENGTH) {
                shiftAndAppend(pendingInput)
                processWindow()
                appendOutputBlock()
                pendingInputCount = 0
            }
        }
        for (index in output.indices) {
            output[index] = (outputQueue[index].coerceIn(-1F, 1F) * 32767F).toInt().toShort()
        }
        val remaining = outputQueueCount - FRAME_SAMPLES
        if (remaining > 0) outputQueue.copyInto(outputQueue, 0, FRAME_SAMPLES, outputQueueCount)
        outputQueueCount = remaining.coerceAtLeast(0)
        return output.copyOf()
    }

    private fun shiftAndAppend(block: FloatArray) {
        System.arraycopy(inputWindow, HOP_LENGTH, inputWindow, 0, HOP_LENGTH)
        block.copyInto(inputWindow, HOP_LENGTH)
    }

    private fun appendOutputBlock() {
        // Match the reference implementation's overlap-add buffer update.
        for (index in 0 until HOP_LENGTH) outputWindow[index] = outputWindow[index + HOP_LENGTH]
        for (index in HOP_LENGTH until BLOCK_LENGTH) outputWindow[index] = 0F
        outputWindow.copyInto(outputQueue, outputQueueCount, 0, HOP_LENGTH)
        outputQueueCount += HOP_LENGTH
    }

    private fun processWindow() {
        val phase = FloatArray(FREQUENCY_BINS)
        val spectrum = FloatArray(FREQUENCY_BINS)
        fft.forward(inputWindow, spectrum, phase)
        for (index in 0 until FREQUENCY_BINS) magnitudeInput[0][0][index] = spectrum[index]
        firstCore.runForMultipleInputsOutputs(
            arrayOf(magnitudeInput, firstState),
            mapOf(0 to firstMask, 1 to firstStateOutput),
        )
        copyState(firstStateOutput, firstState)
        for (index in 0 until FREQUENCY_BINS) {
            spectrum[index] *= firstMask[0][0][index]
        }
        fft.inverse(spectrum, phase, secondInput[0][0])
        secondCore.runForMultipleInputsOutputs(
            arrayOf(secondInput, secondState),
            mapOf(0 to secondOutput, 1 to secondStateOutput),
        )
        copyState(secondStateOutput, secondState)
        for (index in 0 until BLOCK_LENGTH) outputWindow[index] += secondOutput[0][0][index]
    }

    override fun close() {
        secondCore.close()
        firstCore.close()
    }

    private fun options() = InterpreterApi.Options()
        .setNumThreads(2)
        .setRuntime(InterpreterApi.Options.TfLiteRuntime.PREFER_SYSTEM_OVER_APPLICATION)

    private fun copyState(
        source: Array<Array<Array<FloatArray>>>,
        destination: Array<Array<Array<FloatArray>>>,
    ) {
        for (layer in source[0].indices) {
            for (unit in source[0][layer].indices) {
                source[0][layer][unit].copyInto(destination[0][layer][unit])
            }
        }
    }

    private fun Context.assetBuffer(name: String): ByteBuffer {
        assets.openFd(name).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                return channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                ).order(ByteOrder.nativeOrder())
            }
        }
    }

    private class RealFft(private val size: Int) {
        private val real = FloatArray(size)
        private val imaginary = FloatArray(size)

        fun forward(input: FloatArray, magnitude: FloatArray, phase: FloatArray) {
            input.copyInto(real)
            imaginary.fill(0F)
            transform(inverse = false)
            for (index in 0..size / 2) {
                magnitude[index] = hypot(real[index].toDouble(), imaginary[index].toDouble()).toFloat()
                phase[index] = atan2(imaginary[index], real[index])
            }
        }

        fun inverse(magnitude: FloatArray, phase: FloatArray, output: FloatArray) {
            real.fill(0F)
            imaginary.fill(0F)
            for (index in 0..size / 2) {
                real[index] = magnitude[index] * cos(phase[index])
                imaginary[index] = magnitude[index] * sin(phase[index])
            }
            for (index in 1 until size / 2) {
                real[size - index] = real[index]
                imaginary[size - index] = -imaginary[index]
            }
            transform(inverse = true)
            real.copyInto(output)
        }

        private fun transform(inverse: Boolean) {
            var j = 0
            for (index in 1 until size) {
                var bit = size shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (index < j) {
                    val realValue = real[index]
                    real[index] = real[j]
                    real[j] = realValue
                    val imaginaryValue = imaginary[index]
                    imaginary[index] = imaginary[j]
                    imaginary[j] = imaginaryValue
                }
            }
            var length = 2
            while (length <= size) {
                val angle = (if (inverse) 2.0 else -2.0) * Math.PI / length
                val stepReal = cos(angle).toFloat()
                val stepImaginary = sin(angle).toFloat()
                var start = 0
                while (start < size) {
                    var currentReal = 1F
                    var currentImaginary = 0F
                    for (offset in 0 until length / 2) {
                        val even = start + offset
                        val odd = even + length / 2
                        val oddReal = real[odd] * currentReal - imaginary[odd] * currentImaginary
                        val oddImaginary = real[odd] * currentImaginary + imaginary[odd] * currentReal
                        real[odd] = real[even] - oddReal
                        imaginary[odd] = imaginary[even] - oddImaginary
                        real[even] += oddReal
                        imaginary[even] += oddImaginary
                        val nextReal = currentReal * stepReal - currentImaginary * stepImaginary
                        currentImaginary = currentReal * stepImaginary + currentImaginary * stepReal
                        currentReal = nextReal
                    }
                    start += length
                }
                length = length shl 1
            }
            if (inverse) {
                for (index in real.indices) {
                    real[index] /= size
                    imaginary[index] /= size
                }
            }
        }
    }

    private companion object {
        const val FIRST_MODEL_ASSET = "audio/dtln/model_1.tflite"
        const val SECOND_MODEL_ASSET = "audio/dtln/model_2.tflite"
        const val FRAME_SAMPLES = 16_000
        const val BLOCK_LENGTH = 512
        const val HOP_LENGTH = 256
        const val FREQUENCY_BINS = BLOCK_LENGTH / 2 + 1

        fun newState(): Array<Array<Array<FloatArray>>> =
            Array(1) { Array(2) { Array(128) { FloatArray(2) } } }

        fun isModelInstalled(context: Context): Boolean = runCatching {
            context.assets.open(FIRST_MODEL_ASSET).use { }
            context.assets.open(SECOND_MODEL_ASSET).use { }
            true
        }.getOrDefault(false)
    }
}
