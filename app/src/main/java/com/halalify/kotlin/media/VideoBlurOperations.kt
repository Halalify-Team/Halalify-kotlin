package com.halalify.kotlin.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.activity.ComponentActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.halalify.kotlin.model.BlurStrictness
import com.halalify.kotlin.model.FileResult
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * HaramBlur-style whole-frame blur pipeline.
 *
 * Instead of detecting face *boxes* and building a complex multi-overlay
 * filter graph, we ask a single binary question per sample: "is a woman
 * present in this frame, yes/no?" Then we emit a list of `(start, end)`
 * time windows and apply ONE full-frame `boxblur` filter with an `enable`
 * expression. FFmpeg copies frames untouched outside the windows and blurs
 * the whole frame inside them — one filter, constant cost regardless of
 * how many women are on screen.
 *
 * Key HaramBlur tricks ported:
 *  - Binary detection (no boxes, no tracking, no interpolation)
 *  - POSITIVE_THRESHOLD=1 / NEGATIVE_THRESHOLD=3 debounce (blur on fast,
 *    off slow → no flicker on, slight stickiness off)
 *  - Stale-sample drop (don't emit a sample whose inference lagged)
 *  - Age gate via face-size heuristic (ignore very small faces = kids)
 *  - Gender cache per trackingId (run TFLite once per tracked face)
 */
internal suspend fun blurWomenInVideoChunk(
    activity: ComponentActivity,
    videoPath: String,
    chunkIndex: Int,
    durationSeconds: Int,
    strictness: BlurStrictness = BlurStrictness.BALANCED,
): FileResult = withContext(Dispatchers.IO) {
    try {
        val source = File(videoPath)
        if (!source.isFile || source.length() <= 0L) {
            error("Video chunk is missing or empty: ${source.absolutePath}")
        }

        val startedAt = System.currentTimeMillis()
        val windows = detectWomenWindows(activity, source, durationSeconds, strictness)
        Log.i("HalalifyBlur", "Detection: windows=${windows.size} elapsed=${System.currentTimeMillis() - startedAt}ms")

        if (windows.isEmpty()) {
            return@withContext FileResult(
                message = "SUCCESS: no women detected, blur skipped. path: ${source.absolutePath}",
                path = source.absolutePath,
            )
        }

        val outputDir = File(activity.filesDir, "halalify-video-blur").apply { mkdirs() }
        val outputFile = File(outputDir, "blur_${chunkIndex}_${UUID.randomUUID().toString().take(8)}.mp4")
        val filter = buildFullFrameBlurFilter(windows)
        val encodeStartedAt = System.currentTimeMillis()
        // Single boxblur filter on the whole frame, enabled only during windows.
        // -threads 0 for multi-core encode. -c:a copy keeps audio untouched.
        val command = listOf(
            "-y",
            "-threads", "0",
            "-i", source.absolutePath,
            "-vf", filter,
            "-c:v", "mpeg4",
            "-b:v", "2200k",
            "-maxrate", "3200k",
            "-bufsize", "2200k",
            "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            "-movflags", "+faststart",
            outputFile.absolutePath,
        ).joinToString(" ") { it.ffmpegQuote() }

        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            error("ffmpeg women blur failed. code=${session.returnCode?.value}\n${session.allLogsAsString.takeLast(2500)}")
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("ffmpeg women blur finished but produced no output file.")
        }
        Log.i("HalalifyBlur", "Encode done in ${System.currentTimeMillis() - encodeStartedAt}ms total=${System.currentTimeMillis() - startedAt}ms")

        FileResult(
            message = "SUCCESS: women blur applied. windows=${windows.size} path: ${outputFile.absolutePath} elapsed: ${System.currentTimeMillis() - startedAt}ms",
            path = outputFile.absolutePath,
        )
    } catch (error: Throwable) {
        FileResult(message = "FAILED: ${error.javaClass.simpleName}: ${error.message}", path = null)
    }
}

// =====================================================================
// Binary detection: "is a woman present at sample t?" → time windows
// =====================================================================

/** A time range (in seconds) during which a woman is present and the frame should be blurred. */
private data class BlurWindow(val startSec: Double, val endSec: Double)

private data class SampleResult(val sampleMs: Long, val womanPresent: Boolean, val inferStartMs: Long)

private suspend fun detectWomenWindows(
    activity: ComponentActivity,
    videoFile: File,
    durationSeconds: Int,
    strictness: BlurStrictness,
): List<BlurWindow> {
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
    val classifier = GenderFaceClassifier(activity.applicationContext)
    val genderCache = mutableMapOf<Int, GenderPrediction>()

    val sampleStepMs = 200L  // 5 FPS
    val staleThresholdMs = sampleStepMs * 3  // drop samples whose inference took >600ms
    val positiveThreshold = 1  // 1 positive → blur ON
    val negativeThreshold = 3  // 3 consecutive negatives → blur OFF

    // Get video dimensions for the small-face (age) heuristic
    val (videoW, videoH) = getVideoDimensions(videoFile)
    // Faces smaller than this fraction of frame height are likely children → ignore
    val minAdultFaceFraction = 0.08f

    val framesDir = File(activity.cacheDir, "halalify_frames_${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
    val samples = mutableListOf<SampleResult>()

    try {
        val extractStart = System.currentTimeMillis()
        // Extract at 5 FPS, 360p, fast_bilinear, skip audio/subs/data
        val scaleFilter = if (videoW > 0 && videoH > 0) "fps=5,scale=-2:360:flags=fast_bilinear" else "fps=5"
        val ffmpegCommand = listOf(
            "-y", "-an", "-sn", "-dn",
            "-i", videoFile.absolutePath,
            "-vf", scaleFilter,
            "-q:v", "8",
            "${framesDir.absolutePath}/frame_%04d.jpg",
        ).joinToString(" ") { it.ffmpegQuote() }
        val session = FFmpegKit.execute(ffmpegCommand)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            error("Failed to extract frames. code=${session.returnCode?.value}")
        }
        Log.i("HalalifyBlur", "Extracted 5fps 360p frames in ${System.currentTimeMillis() - extractStart}ms")

        val files = framesDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        var bitmapH = 0

        files.forEach { file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: run { file.delete(); return@forEach }
            if (bitmapH == 0) bitmapH = bitmap.height

            val frameNum = file.name.substringBeforeLast(".").substringAfter("frame_").toIntOrNull() ?: 1
            val sampleMs = (frameNum - 1) * sampleStepMs
            val inferStart = System.currentTimeMillis()

            val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
            var womanPresent = false

            for (face in faces) {
                // Age gate via face-size heuristic: ignore very small faces (kids)
                val faceFraction = face.boundingBox.height().toFloat() / bitmapH.coerceAtLeast(1)
                if (faceFraction < minAdultFaceFraction) continue

                val tid = face.trackingId
                val pred = if (tid != null && genderCache.containsKey(tid)) {
                    genderCache[tid]!!
                } else {
                    val faceBitmap = bitmap.cropFace(face.boundingBox) ?: continue
                    val p = classifier.classify(faceBitmap)
                    faceBitmap.recycle()
                    if (tid != null) genderCache[tid] = p
                    p
                }
                if (pred.shouldBlur(strictness)) {
                    womanPresent = true
                    break  // one woman is enough — binary decision
                }
            }

            val inferMs = System.currentTimeMillis() - inferStart
            // Stale-sample drop: if inference lagged beyond threshold, don't emit
            if (inferMs <= staleThresholdMs) {
                samples += SampleResult(sampleMs, womanPresent, inferStart)
            } else {
                Log.w("HalalifyBlur", "Dropped stale sample at ${sampleMs}ms (infer=${inferMs}ms)")
            }
            bitmap.recycle()
            file.delete()
        }
    } finally {
        classifier.close()
        detector.close()
        framesDir.deleteRecursively()
    }

    // Build contiguous blur windows from binary samples with debounce.
    // - 1 positive flips ON instantly
    // - 3 consecutive negatives flips OFF
    val windows = mutableListOf<BlurWindow>()
    var windowStartMs: Long? = null
    var consecutiveNegatives = 0
    val chunkEndMs = durationSeconds * 1000L

    for (s in samples) {
        if (s.womanPresent) {
            if (windowStartMs == null) windowStartMs = (s.sampleMs - sampleStepMs).coerceAtLeast(0L)
            consecutiveNegatives = 0
        } else {
            if (windowStartMs != null) {
                consecutiveNegatives++
                if (consecutiveNegatives >= negativeThreshold) {
                    // Close the window just before the first negative
                    val endMs = s.sampleMs - (consecutiveNegatives * sampleStepMs)
                    windows += BlurWindow(
                        windowStartMs!! / 1000.0,
                        endMs.coerceAtLeast(windowStartMs!! + sampleStepMs) / 1000.0,
                    )
                    windowStartMs = null
                    consecutiveNegatives = 0
                }
            }
        }
    }
    // Close any open window at chunk end (sticky: woman never un-blurs mid-chunk)
    if (windowStartMs != null) {
        windows += BlurWindow(windowStartMs!! / 1000.0, chunkEndMs / 1000.0)
    }

    Log.i("HalalifyBlur", "Built ${windows.size} blur windows from ${samples.size} samples")
    return windows
}

// =====================================================================
// Single full-frame boxblur filter with enable expression
// =====================================================================

/**
 * Build a single FFmpeg filter that applies full-frame boxblur only during
 * the given time windows. Outside the windows, frames are copied untouched.
 *
 * Example output:
 *   boxblur=20:1:enable='between(t,2.00,8.00)+between(t,12.00,18.00)'
 *
 * FFmpeg's `enable` accepts an expression that's truthy (non-zero) when blur
 * should apply. We OR all windows together with `+` (addition), which is
 * truthy when any window is active.
 */
private fun buildFullFrameBlurFilter(windows: List<BlurWindow>): String {
    if (windows.isEmpty()) return "null"

    val enableExpr = windows.joinToString("+") { w ->
        "between(t\\,${w.startSec.formatFfmpeg()}\\,${w.endSec.formatFfmpeg()})"
    }
    return "boxblur=20:1:enable='${enableExpr}'"
}

// =====================================================================
// Helpers
// =====================================================================

private fun Bitmap.cropFace(faceBounds: android.graphics.Rect): Bitmap? {
    val padX = (faceBounds.width() * 0.20f).toInt()
    val padY = (faceBounds.height() * 0.20f).toInt()
    val padded = android.graphics.Rect(
        (faceBounds.left - padX).coerceAtLeast(0),
        (faceBounds.top - padY).coerceAtLeast(0),
        (faceBounds.right + padX).coerceAtMost(width),
        (faceBounds.bottom + padY).coerceAtMost(height),
    )
    if (padded.width() < 8 || padded.height() < 8) return null
    return Bitmap.createBitmap(this, padded.left, padded.top, padded.width(), padded.height())
}

private fun getVideoDimensions(videoFile: File): Pair<Int, Int> {
    return try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(videoFile.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            Pair(w, h)
        }
    } catch (e: Exception) {
        Log.w("HalalifyBlur", "Could not get video dimensions: ${e.message}")
        Pair(0, 0)
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

private fun Double.formatFfmpeg(): String = String.format(java.util.Locale.US, "%.2f", this)