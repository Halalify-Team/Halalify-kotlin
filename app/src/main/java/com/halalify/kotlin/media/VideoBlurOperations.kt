package com.halalify.kotlin.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.util.Log
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
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.02f) // Detect even smaller/further faces
            .enableTracking()
            .build()
    )
    val rawRegions = mutableListOf<TimedBlurRegion>()
    var facesDetected = 0
    var facesBlurred = 0
    var facesSkipped = 0
    val classifier = GenderFaceClassifier(activity.applicationContext)

    // Create a unique temporary directory for frame extraction
    val framesDir = File(activity.cacheDir, "halalify_frames_${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }

    var videoWidth = 0
    var videoHeight = 0

    try {
        val startedAt = System.currentTimeMillis()

        // Extract frames at 10 FPS to temp directory as JPEG images sequentially
        val ffmpegCommand = listOf(
            "-y",
            "-i", videoFile.absolutePath,
            "-vf", "fps=10",
            "-q:v", "4", // Good quality JPEG (1-31, lower is better)
            "${framesDir.absolutePath}/frame_%04d.jpg"
        ).joinToString(" ") { it.ffmpegQuote() }

        val session = FFmpegKit.execute(ffmpegCommand)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            error("Failed to extract frames using FFmpeg. code=${session.returnCode?.value}")
        }
        Log.i("HalalifyBlur", "Extracted frames using FFmpeg in ${System.currentTimeMillis() - startedAt}ms")

        val files = framesDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        val sampleStepMs = 100L // Each frame is exactly 100ms apart (fps=10)

        files.forEach { file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                if (videoWidth == 0) {
                    videoWidth = bitmap.width
                    videoHeight = bitmap.height
                }

                // Parse frame index to calculate timestamp
                val frameName = file.name.substringBeforeLast(".")
                val frameNum = frameName.substringAfter("frame_").toIntOrNull() ?: 1
                val sampleMs = (frameNum - 1) * sampleStepMs

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
                        videoWidth = videoWidth,
                        videoHeight = videoHeight,
                    )
                    if (region != null) {
                        facesBlurred += 1
                        rawRegions += region
                    } else {
                        facesSkipped += 1
                    }
                }
                bitmap.recycle()
            }
            file.delete() // Clean up image file immediately
        }
    } finally {
        classifier.close()
        detector.close()
        framesDir.deleteRecursively() // Delete directory and any leftover frames
    }

    // Consolidate raw regions using our tracking/merging algorithm
    val consolidated = mutableListOf<TimedBlurRegion>()
    rawRegions.sortedBy { it.startSeconds }.forEach { raw ->
        val rawSampleMs = (raw.startSeconds * 1000.0).toLong()
        var merged = false
        for (i in consolidated.indices) {
            val existing = consolidated[i]
            if (existing.canMerge(raw.x, raw.y, raw.width, raw.height, rawSampleMs, videoWidth, videoHeight)) {
                consolidated[i] = existing.merge(raw.x, raw.y, raw.width, raw.height, rawSampleMs, videoWidth, videoHeight)
                merged = true
                break
            }
        }
        if (!merged) {
            // Adjust start and end times for the new tracker region (add 600ms hold time to prevent flickering)
            consolidated += TimedBlurRegion(
                startSeconds = raw.startSeconds,
                endSeconds = raw.endSeconds + 0.6,
                x = raw.x,
                y = raw.y,
                width = raw.width,
                height = raw.height,
            )
        }
    }

    return WomenBlurDetectionResult(
        regions = consolidated.take(MAX_BLUR_REGIONS_PER_CHUNK),
        facesDetected = facesDetected,
        facesBlurred = facesBlurred,
        facesSkipped = facesSkipped,
    )
}

private fun TimedBlurRegion.canMerge(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    sampleMs: Long,
    videoWidth: Int,
    videoHeight: Int,
): Boolean {
    val currentEndMs = (endSeconds * 1000.0).toLong()
    val timeGap = sampleMs - currentEndMs
    if (timeGap > 800) return false

    val r1CenterX = this.x + this.width / 2
    val r1CenterY = this.y + this.height / 2
    val r2CenterX = x + width / 2
    val r2CenterY = y + height / 2

    val distLimit = maxOf(this.width, this.height, width, height) * 1.5
    val dist = Math.hypot((r1CenterX - r2CenterX).toDouble(), (r1CenterY - r2CenterY).toDouble())
    if (dist > distLimit) return false

    // Prevent box from growing too large (no more than 2x the original dimensions)
    val minX = minOf(this.x, x).coerceIn(0, videoWidth)
    val minY = minOf(this.y, y).coerceIn(0, videoHeight)
    val maxX = maxOf(this.x + this.width, x + width).coerceIn(0, videoWidth)
    val maxY = maxOf(this.y + this.height, y + height).coerceIn(0, videoHeight)

    val mergedWidth = maxX - minX
    val mergedHeight = maxY - minY
    if (mergedWidth > width * 2.0 || mergedHeight > height * 2.0) {
        return false
    }

    return true
}

private fun TimedBlurRegion.merge(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    sampleMs: Long,
    videoWidth: Int,
    videoHeight: Int,
): TimedBlurRegion {
    val minX = minOf(this.x, x).coerceIn(0, videoWidth)
    val minY = minOf(this.y, y).coerceIn(0, videoHeight)
    val maxX = maxOf(this.x + this.width, x + width).coerceIn(0, videoWidth)
    val maxY = maxOf(this.y + this.height, y + height).coerceIn(0, videoHeight)

    return TimedBlurRegion(
        startSeconds = this.startSeconds,
        endSeconds = ((sampleMs + 800) / 1000.0), // Extend hold time
        x = minX.roundDownEven(),
        y = minY.roundDownEven(),
        width = (maxX - minX).coerceAtLeast(8).coerceAtMost(videoWidth - minX).roundDownEven(),
        height = (maxY - minY).coerceAtLeast(8).coerceAtMost(videoHeight - minY).roundDownEven(),
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
            "boxblur=30:3[$blurred]"
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
