package com.halalify.kotlin.media

import android.content.Context
import android.graphics.Bitmap
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
        val male = output[0][0].coerceIn(0f, 1f)
        val female = output[0][1].coerceIn(0f, 1f)
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
    val shouldBlurAsWoman: Boolean
        get() = femaleProbability >= FEMALE_BLUR_THRESHOLD ||
            (femaleProbability >= AMBIGUOUS_FEMALE_THRESHOLD && femaleProbability >= maleProbability)

    companion object {
        private const val FEMALE_BLUR_THRESHOLD = 0.45f
        private const val AMBIGUOUS_FEMALE_THRESHOLD = 0.35f
    }
}
