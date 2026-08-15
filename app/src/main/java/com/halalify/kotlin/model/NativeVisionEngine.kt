package com.halalify.kotlin.model

import android.content.Context
import com.halalify.kotlin.settings.BlurTarget
import java.io.Closeable
import java.nio.ByteBuffer

internal data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val confidence: Float,
    val classId: Int,
    val shouldBlur: Boolean,
    val isNsfw: Boolean = false,
) {
    val label: String
        get() = when (classId) {
            0 -> "female"
            1 -> "male"
            3 -> "nsfw"
            else -> "ignored"
    }
}

private const val DETECTION_FIELDS = 8

internal interface VisionProcessor : Closeable {
    fun process(
        rgbaBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        rotationDegrees: Int,
        timestampNs: Long,
    ): List<Detection>
}

internal fun FloatArray.asListOfDetections(): List<Detection> {
    check(size % DETECTION_FIELDS == 0) { "Native detection result is malformed." }
    return buildList(size / DETECTION_FIELDS) {
        for (base in this@asListOfDetections.indices step DETECTION_FIELDS) {
            add(
                Detection(
                    x1 = this@asListOfDetections[base],
                    y1 = this@asListOfDetections[base + 1],
                    x2 = this@asListOfDetections[base + 2],
                    y2 = this@asListOfDetections[base + 3],
                    confidence = this@asListOfDetections[base + 4],
                    classId = this@asListOfDetections[base + 5].toInt(),
                    shouldBlur = this@asListOfDetections[base + 6] > 0.5F,
                    isNsfw = this@asListOfDetections[base + 7] > 0.5F,
                ),
            )
        }
    }
}

internal class NativeVisionEngine(context: Context, target: BlurTarget) : VisionProcessor {
    private var nativeHandle = nativeCreate(
        context.assets,
        MODEL_ASSET,
        NSFW_MODEL_ASSET,
        target.nativeId,
    )

    @Synchronized
    override fun process(
        rgbaBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        rotationDegrees: Int,
        timestampNs: Long,
    ): List<Detection> {
        check(nativeHandle != 0L) { "Vision engine is closed." }
        val values = nativeProcess(
            nativeHandle,
            rgbaBuffer,
            width,
            height,
            rowStride,
            rotationDegrees,
            timestampNs,
        )
        return values.asListOfDetections()
    }

    @Synchronized
    fun updateTarget(target: BlurTarget) {
        check(nativeHandle != 0L) { "Vision engine is closed." }
        nativeUpdateTarget(nativeHandle, target.nativeId)
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
        nsfwAssetName: String,
        target: Int,
    ): Long

    private external fun nativeProcess(
        handle: Long,
        rgbaBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        rotationDegrees: Int,
        timestampNs: Long,
    ): FloatArray

    private external fun nativeUpdateTarget(handle: Long, target: Int)
    private external fun nativeDestroy(handle: Long)

    private companion object {
        const val MODEL_ASSET = "halalify_gender_v3_full_int8.tflite"
        const val NSFW_MODEL_ASSET = "nsfw2.tflite"

        val BlurTarget.nativeId: Int
            get() = when (this) {
                BlurTarget.FEMALE -> 0
                BlurTarget.MALE -> 1
            }

        init {
            System.loadLibrary("halalify_android_jni")
        }
    }
}
