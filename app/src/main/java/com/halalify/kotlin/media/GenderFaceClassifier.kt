package com.halalify.kotlin.media

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.halalify.kotlin.model.BlurStrictness
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp

internal class GenderFaceClassifier(context: Context) : Closeable {
    private val interpreter = Interpreter(FileUtil.loadMappedFile(context, MODEL_FILENAME))
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(INPUT_IMAGE_SIZE, INPUT_IMAGE_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(0f, 255f))
        .build()

    suspend fun classify(bitmap: Bitmap): GenderPrediction = withContext(Dispatchers.Default) {
        val input = imageProcessor.process(TensorImage.fromBitmap(bitmap)).buffer
        val output = Array(1) { FloatArray(2) }
        interpreter.run(input, output)
        val rawMale = output[0][0]
        val rawFemale = output[0][1]

        val male: Float
        val female: Float
        // Check if the outputs are already normalized probabilities (sum to ~1.0)
        if (rawMale in 0.0f..1.0f && rawFemale in 0.0f..1.0f && Math.abs((rawMale + rawFemale) - 1.0f) < 0.05f) {
            male = rawMale
            female = rawFemale
        } else {
            // Apply softmax for raw logits or unnormalized values
            val maxVal = maxOf(rawMale, rawFemale)
            val expMale = Math.exp((rawMale - maxVal).toDouble())
            val expFemale = Math.exp((rawFemale - maxVal).toDouble())
            val sum = expMale + expFemale
            male = (expMale / sum).toFloat()
            female = (expFemale / sum).toFloat()
            Log.d("HalalifyClassifier", "Raw output: male=$rawMale, female=$rawFemale -> Softmax: male=$male, female=$female")
        }

        GenderPrediction(maleProbability = male, femaleProbability = female)
    }

    override fun close() {
        interpreter.close()
    }

    private companion object {
        const val MODEL_FILENAME = "model_gender_q.tflite"
        const val INPUT_IMAGE_SIZE = 128
    }
}

internal data class GenderPrediction(
    val maleProbability: Float,
    val femaleProbability: Float,
) {
    fun shouldBlur(strictness: BlurStrictness): Boolean =
        femaleProbability >= strictness.femaleBlurThreshold ||
            (femaleProbability >= strictness.ambiguousFemaleThreshold && femaleProbability >= maleProbability)
}
