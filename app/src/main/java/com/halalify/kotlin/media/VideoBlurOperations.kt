package com.halalify.kotlin.media

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import androidx.activity.ComponentActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.halalify.kotlin.model.FileResult
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal suspend fun blurWomenInVideoChunk(
    activity: ComponentActivity,
    videoPath: String,
    chunkIndex: Int,
    durationSeconds: Int,
): FileResult = withContext(Dispatchers.IO) {
    try {
        val source = File(videoPath)
        if (!source.isFile || source.length() <= 0L) {
            error("Video chunk is missing or empty: ${source.absolutePath}")
        }

        val startedAt = System.currentTimeMillis()
        val detectionResult = detectWomenBlurRegions(
            activity = activity,
            videoFile = source,
            durationSeconds = durationSeconds,
        )
        val detections = detectionResult.regions
        if (detections.isEmpty()) {
            return@withContext FileResult(
                message = "SUCCESS: no women detected, blur skipped.\n" +
                    "faces: ${detectionResult.facesDetected}\n" +
                    "path: ${source.absolutePath}",
                path = source.absolutePath,
            )
        }

        val outputDir = File(activity.filesDir, "halalify-video-blur").apply { mkdirs() }
        val outputFile = File(outputDir, "blur_${chunkIndex}_${UUID.randomUUID().toString().take(8)}.mp4")
        val filterGraph = buildBlurFilterGraph(detections)
        val command = listOf(
            "-y",
            "-i", source.absolutePath,
            "-filter_complex", filterGraph,
            "-map", "[blurred]",
            "-map", "0:a?",
            "-c:v", "mpeg4",
            "-b:v", "2200k",
            "-maxrate", "3200k",
            "-bufsize", "4200k",
            "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            "-movflags", "+faststart",
            outputFile.absolutePath,
        ).joinToString(" ") { it.ffmpegQuote() }

        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            error(
                "ffmpeg women blur failed. code=${session.returnCode?.value}\n" +
                    session.allLogsAsString.takeLast(2500)
            )
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("ffmpeg women blur finished but produced no output file.")
        }

        FileResult(
            message = "SUCCESS: women blur applied.\n" +
                "faces: ${detectionResult.facesDetected}\n" +
                "blurred: ${detectionResult.facesBlurred}\n" +
                "skipped: ${detectionResult.facesSkipped}\n" +
                "regions: ${detections.size}\n" +
                "path: ${outputFile.absolutePath}\n" +
                "elapsed: ${System.currentTimeMillis() - startedAt}ms",
            path = outputFile.absolutePath,
        )
    } catch (error: Throwable) {
        FileResult(
            message = "FAILED: ${error.javaClass.simpleName}: ${error.message}",
            path = null,
        )
    }
}

private suspend fun detectWomenBlurRegions(
    activity: ComponentActivity,
    videoFile: File,
    durationSeconds: Int,
): WomenBlurDetectionResult {
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.04f)
            .enableTracking()
            .build()
    )
    val retriever = MediaMetadataRetriever()
    val regions = mutableListOf<TimedBlurRegion>()
    var facesDetected = 0
    var facesBlurred = 0
    var facesSkipped = 0
    val classifier = GenderFaceClassifier(activity.applicationContext)
    try {
        retriever.setDataSource(videoFile.absolutePath)
        val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 0
        val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 0
        val safeDuration = durationSeconds.coerceAtLeast(1)
        val sampleStepMs = 500L
        var sampleMs = 0L
        while (sampleMs < safeDuration * 1000L) {
            val bitmap = retriever.getFrameAtTime(
                sampleMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
            )
            if (bitmap != null) {
                val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
                facesDetected += faces.size
                for (face in faces) {
                    val faceBitmap = bitmap.cropFace(face.boundingBox)
                    if (faceBitmap == null) {
                        facesSkipped += 1
                        continue
                    }
                    val prediction = classifier.classify(faceBitmap)
                    faceBitmap.recycle()
                    if (!prediction.shouldBlurAsWoman) {
                        facesSkipped += 1
                        continue
                    }
                    val region = face.toTimedBlurRegion(
                        sampleMs = sampleMs,
                        sampleStepMs = sampleStepMs,
                        frameWidth = bitmap.width,
                        frameHeight = bitmap.height,
                        videoWidth = videoWidth.takeIf { it > 0 } ?: bitmap.width,
                        videoHeight = videoHeight.takeIf { it > 0 } ?: bitmap.height,
                    )
                    if (region != null) {
                        facesBlurred += 1
                        regions += region
                    } else {
                        facesSkipped += 1
                    }
                }
                bitmap.recycle()
            }
            sampleMs += sampleStepMs
        }
    } finally {
        classifier.close()
        detector.close()
        retriever.release()
    }
    return WomenBlurDetectionResult(
        regions = regions.take(MAX_BLUR_REGIONS_PER_CHUNK),
        facesDetected = facesDetected,
        facesBlurred = facesBlurred,
        facesSkipped = facesSkipped,
    )
}

private fun Face.toTimedBlurRegion(
    sampleMs: Long,
    sampleStepMs: Long,
    frameWidth: Int,
    frameHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
): TimedBlurRegion? {
    val expanded = boundingBox.expandForConservativeModesty(frameWidth, frameHeight)
    if (expanded.width() <= 2 || expanded.height() <= 2) return null
    val scaleX = videoWidth.toDouble() / frameWidth.coerceAtLeast(1)
    val scaleY = videoHeight.toDouble() / frameHeight.coerceAtLeast(1)
    val x = (expanded.left * scaleX).toInt().coerceIn(0, videoWidth - 2)
    val y = (expanded.top * scaleY).toInt().coerceIn(0, videoHeight - 2)
    val width = (expanded.width() * scaleX).toInt()
        .coerceAtLeast(8)
        .coerceAtMost(videoWidth - x)
        .roundDownEven()
    val height = (expanded.height() * scaleY).toInt()
        .coerceAtLeast(8)
        .coerceAtMost(videoHeight - y)
        .roundDownEven()
    if (width <= 2 || height <= 2) return null
    return TimedBlurRegion(
        startSeconds = ((sampleMs - sampleStepMs).coerceAtLeast(0L)) / 1000.0,
        endSeconds = (sampleMs + sampleStepMs * 2) / 1000.0,
        x = x.roundDownEven(),
        y = y.roundDownEven(),
        width = width,
        height = height,
    )
}

private fun Bitmap.cropFace(faceBounds: Rect): Bitmap? {
    val padded = faceBounds.expandForGenderClassification(width, height)
    if (padded.width() < 8 || padded.height() < 8) return null
    return Bitmap.createBitmap(this, padded.left, padded.top, padded.width(), padded.height())
}

private fun Rect.expandForGenderClassification(frameWidth: Int, frameHeight: Int): Rect {
    val padX = (width() * 0.20f).roundToInt()
    val padY = (height() * 0.20f).roundToInt()
    return Rect(
        (left - padX).coerceAtLeast(0),
        (top - padY).coerceAtLeast(0),
        (right + padX).coerceAtMost(frameWidth),
        (bottom + padY).coerceAtMost(frameHeight),
    )
}

private fun Rect.expandForConservativeModesty(frameWidth: Int, frameHeight: Int): Rect {
    val faceWidth = width().coerceAtLeast(1)
    val faceHeight = height().coerceAtLeast(1)
    val leftPad = (faceWidth * 0.90f).toInt()
    val rightPad = (faceWidth * 0.90f).toInt()
    val topPad = (faceHeight * 0.70f).toInt()
    val bottomPad = (faceHeight * 2.40f).toInt()
    return Rect(
        (left - leftPad).coerceAtLeast(0),
        (top - topPad).coerceAtLeast(0),
        (right + rightPad).coerceAtMost(frameWidth),
        (bottom + bottomPad).coerceAtMost(frameHeight),
    )
}

private fun buildBlurFilterGraph(regions: List<TimedBlurRegion>): String {
    val parts = mutableListOf<String>()
    var current = "[0:v]"
    regions.forEachIndexed { index, region ->
        val base = "base$index"
        val crop = "crop$index"
        val blurred = "boxblur$index"
        val output = if (index == regions.lastIndex) "blurred" else "stage$index"
        parts += "$current split=2[$base][$crop]"
        parts += "[$crop]crop=${region.width}:${region.height}:${region.x}:${region.y}," +
            "boxblur=18:2[$blurred]"
        parts += "[$base][$blurred]overlay=${region.x}:${region.y}:" +
            "enable=between(t\\,${region.startSeconds.formatFfmpeg()}\\,${region.endSeconds.formatFfmpeg()})[$output]"
        current = "[$output]"
    }
    return parts.joinToString(";")
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}

private fun Int.roundDownEven(): Int = if (this % 2 == 0) this else this - 1

private fun Double.formatFfmpeg(): String = String.format(java.util.Locale.US, "%.2f", this)

private data class TimedBlurRegion(
    val startSeconds: Double,
    val endSeconds: Double,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

private data class WomenBlurDetectionResult(
    val regions: List<TimedBlurRegion>,
    val facesDetected: Int,
    val facesBlurred: Int,
    val facesSkipped: Int,
)

private const val MAX_BLUR_REGIONS_PER_CHUNK = 80
