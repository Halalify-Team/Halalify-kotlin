// DO NOT modify the download/audio/mux/music-removal pipeline; this file is the blur stage only.

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
import com.halalify.kotlin.model.BlurStrictness
import com.halalify.kotlin.model.FileResult
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Localized face-blur pipeline: detect → track → consolidate → overlay.
 *
 * Instead of blurring the whole frame (HaramBlur approach), this pipeline
 * clusters face detections into person tracks, confirms each track as woman
 * via ≥2 positive gender classifications, computes ONE expanded bounding box
 * per confirmed track, and applies ≤6 localized blur overlays via FFmpeg.
 *
 * Reference: https://github.com/alganzory/HaramBlur (thresholds + hysteresis)
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

        // Probe original video dimensions (needed to scale overlay boxes from
        // the 480px inference frames back to full-resolution coordinates).
        val (videoWidth, videoHeight) = probeVideoDimensions(source)
        if (videoWidth <= 0 || videoHeight <= 0) {
            error("Could not probe video dimensions for ${source.absolutePath}")
        }

        val decision = detectAndClusterWomen(activity, source, strictness, videoWidth, videoHeight)

        if (decision.regions.isEmpty()) {
            return@withContext FileResult(
                message = "SUCCESS: no women detected, blur skipped.\n" +
                    "frames: ${decision.framesAnalyzed}\n" +
                    "faces: ${decision.facesDetected}\n" +
                    "tracks: ${decision.totalTracks}\n" +
                    "confirmed: ${decision.confirmedTracks}\n" +
                    "path: ${source.absolutePath}\n" +
                    "elapsed: ${System.currentTimeMillis() - startedAt}ms",
                path = source.absolutePath,
            )
        }

        val outputDir = File(activity.filesDir, "halalify-video-blur").apply { mkdirs() }
        val outputFile = File(outputDir, "blur_${chunkIndex}_${UUID.randomUUID().toString().take(8)}.mp4")

        val blurRadius = when (strictness) {
            BlurStrictness.CONSERVATIVE -> 20
            BlurStrictness.BALANCED -> 30
            BlurStrictness.STRICT -> 40
        }

        val filterGraph = buildLocalizedBlurFilter(decision.regions, videoWidth, videoHeight, blurRadius)

        val arguments = arrayOf(
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
        )

        Log.i("HalalifyBlur", "Filter graph: $filterGraph")

        val session = FFmpegKit.executeWithArguments(arguments)
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
            message = "SUCCESS: women blur applied (localized).\n" +
                "frames: ${decision.framesAnalyzed}\n" +
                "faces: ${decision.facesDetected}\n" +
                "tracks: ${decision.totalTracks}\n" +
                "confirmed: ${decision.confirmedTracks}\n" +
                "regions: ${decision.regions.size}\n" +
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

// ─── Data structures ───

private data class FaceObservation(
    val frameIndex: Int,
    val timeSeconds: Double,
    val box: Rect,         // in full-res video coordinates
    val trackingId: Int?,
    val isFemale: Boolean,
)

private data class PersonTrack(
    val id: Int,
    val observations: MutableList<FaceObservation> = mutableListOf(),
    var positiveCount: Int = 0,
) {
    val lastBox: Rect?
        get() = observations.lastOrNull()?.box

    val lastFrameIndex: Int?
        get() = observations.lastOrNull()?.frameIndex

    val isConfirmed: Boolean
        get() = positiveCount >= 2
}

private data class BlurRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val startSeconds: Double,
    val endSeconds: Double,
)

private data class BlurDecision(
    val regions: List<BlurRegion>,
    val framesAnalyzed: Int,
    val facesDetected: Int,
    val totalTracks: Int,
    val confirmedTracks: Int,
)

// ─── Detection + clustering ───

private const val DETECTION_FPS = 3
private const val INFERENCE_WIDTH = 480
private const val MAX_FACES_PER_FRAME = 4
private const val MAX_CONFIRMED_TRACKS = 6
private const val TRACK_CONFIRMATION_POSITIVES = 2
private const val TRACK_IOU_THRESHOLD = 0.30
private const val TRACK_MAX_FRAME_GAP = 3
private const val TIME_MARGIN_SECONDS = 0.5

private suspend fun detectAndClusterWomen(
    activity: ComponentActivity,
    videoFile: File,
    strictness: BlurStrictness,
    videoWidth: Int,
    videoHeight: Int,
): BlurDecision {
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.10f)
            .enableTracking()
            .build()
    )
    val classifier = GenderFaceClassifier(activity.applicationContext)
    val framesDir = File(activity.cacheDir, "halalify_frames_${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }

    val tracks = mutableListOf<PersonTrack>()
    var nextTrackId = 0
    var framesAnalyzed = 0
    var facesDetected = 0

    val minFaceFraction = when (strictness) {
        BlurStrictness.CONSERVATIVE -> 0.08f
        BlurStrictness.BALANCED -> 0.05f
        BlurStrictness.STRICT -> 0.02f
    }

    try {
        val startedAt = System.currentTimeMillis()

        val extractArgs = arrayOf(
            "-y",
            "-i", videoFile.absolutePath,
            "-vf", "scale=$INFERENCE_WIDTH:-2,fps=$DETECTION_FPS",
            "-q:v", "5",
            "${framesDir.absolutePath}/frame_%04d.jpg",
        )
        val session = FFmpegKit.executeWithArguments(extractArgs)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            error("Failed to extract frames. code=${session.returnCode}\n${session.allLogsAsString.takeLast(1500)}")
        }
        Log.i("HalalifyBlur", "Extracted frames in ${System.currentTimeMillis() - startedAt}ms")

        val files = framesDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        val sampleStepMs = 1000.0 / DETECTION_FPS

        files.forEachIndexed { frameIndex, file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@forEachIndexed
            val bitmapW = bitmap.width.coerceAtLeast(1)
            val bitmapH = bitmap.height.coerceAtLeast(1)
            val timeSeconds = frameIndex * sampleStepMs / 1000.0

            val scaleX = videoWidth.toDouble() / bitmapW
            val scaleY = videoHeight.toDouble() / bitmapH

            val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
            framesAnalyzed++
            facesDetected += faces.size

            // Destroy tracks that haven't been seen in a while (gap > TRACK_MAX_FRAME_GAP)
            // — they're stale and won't match anymore.

            for (face in faces.take(MAX_FACES_PER_FRAME)) {
                val faceFraction = face.boundingBox.height().toFloat() / bitmapH
                if (faceFraction < minFaceFraction) continue

                // Scale face box to full-res video coordinates
                val scaledBox = scaleBox(face.boundingBox, scaleX, scaleY, videoWidth, videoHeight)

                // Try to match to an existing track
                val matchedTrack = matchToTrack(scaledBox, face.trackingId, frameIndex, tracks)

                if (matchedTrack != null) {
                    // Track exists — if already confirmed, skip classification (big speedup)
                    if (matchedTrack.isConfirmed) {
                        matchedTrack.observations.add(
                            FaceObservation(frameIndex, timeSeconds, scaledBox, face.trackingId, true)
                        )
                    } else {
                        val faceBitmap = bitmap.cropFace(face.boundingBox) ?: continue
                        val prediction = classifier.classify(faceBitmap)
                        faceBitmap.recycle()
                        val isFemale = prediction.shouldBlur(strictness)
                        matchedTrack.observations.add(
                            FaceObservation(frameIndex, timeSeconds, scaledBox, face.trackingId, isFemale)
                        )
                        if (isFemale) matchedTrack.positiveCount++
                    }
                } else {
                    // New track — classify immediately
                    val faceBitmap = bitmap.cropFace(face.boundingBox) ?: continue
                    val prediction = classifier.classify(faceBitmap)
                    faceBitmap.recycle()
                    val isFemale = prediction.shouldBlur(strictness)

                    val track = PersonTrack(id = nextTrackId++)
                    track.observations.add(
                        FaceObservation(frameIndex, timeSeconds, scaledBox, face.trackingId, isFemale)
                    )
                    if (isFemale) track.positiveCount = 1
                    tracks.add(track)
                }
            }

            bitmap.recycle()
            file.delete()
        }
    } finally {
        classifier.close()
        detector.close()
        framesDir.deleteRecursively()
    }

    // Confirm tracks: keep only those with ≥2 positive classifications.
    val confirmed = tracks.filter { it.isConfirmed }
    val totalTracks = tracks.size
    val confirmedCount = confirmed.size

    // Cap at MAX_CONFIRMED_TRACKS: keep the ones with the most observations.
    val capped = confirmed
        .sortedByDescending { it.observations.size }
        .take(MAX_CONFIRMED_TRACKS)

    val regions = capped.map { track -> trackToBlurRegion(track, videoWidth, videoHeight) }

    Log.i(
        "HalalifyBlur",
        "decision: tracks=$totalTracks confirmed=$confirmedCount regions=${regions.size} frames=$framesAnalyzed faces=$facesDetected"
    )

    return BlurDecision(
        regions = regions,
        framesAnalyzed = framesAnalyzed,
        facesDetected = facesDetected,
        totalTracks = totalTracks,
        confirmedTracks = confirmedCount,
    )
}

private fun matchToTrack(
    box: Rect,
    trackingId: Int?,
    frameIndex: Int,
    tracks: List<PersonTrack>,
): PersonTrack? {
    // Primary: match by trackingId
    if (trackingId != null) {
        tracks.firstOrNull { track ->
            track.observations.any { it.trackingId == trackingId }
        }?.let { return it }
    }

    // Fallback: spatial IoU with last-known box + temporal proximity
    var bestTrack: PersonTrack? = null
    var bestIoU = 0.0
    for (track in tracks) {
        val lastBox = track.lastBox ?: continue
        val lastFrame = track.lastFrameIndex ?: continue
        if (frameIndex - lastFrame > TRACK_MAX_FRAME_GAP) continue

        val iou = computeIoU(box, lastBox)
        if (iou >= TRACK_IOU_THRESHOLD && iou > bestIoU) {
            bestIoU = iou
            bestTrack = track
        }
    }
    return bestTrack
}

private fun computeIoU(a: Rect, b: Rect): Double {
    val left = maxOf(a.left, b.left)
    val top = maxOf(a.top, b.top)
    val right = minOf(a.right, b.right)
    val bottom = minOf(a.bottom, b.bottom)
    val intersectW = (right - left).coerceAtLeast(0)
    val intersectH = (bottom - top).coerceAtLeast(0)
    val intersection = intersectW.toDouble() * intersectH
    val union = a.width().toDouble() * a.height() + b.width().toDouble() * b.height() - intersection
    return if (union > 0) intersection / union else 0.0
}

private fun scaleBox(box: Rect, scaleX: Double, scaleY: Double, videoW: Int, videoH: Int): Rect {
    return Rect(
        (box.left * scaleX).toInt().coerceIn(0, videoW),
        (box.top * scaleY).toInt().coerceIn(0, videoH),
        (box.right * scaleX).toInt().coerceIn(0, videoW),
        (box.bottom * scaleY).toInt().coerceIn(0, videoH),
    )
}

private fun trackToBlurRegion(track: PersonTrack, videoW: Int, videoH: Int): BlurRegion {
    val obs = track.observations
    // Union of all face boxes in this track
    val unionLeft = obs.minOf { it.box.left }
    val unionTop = obs.minOf { it.box.top }
    val unionRight = obs.maxOf { it.box.right }
    val unionBottom = obs.maxOf { it.box.bottom }
    val faceW = (unionRight - unionLeft).coerceAtLeast(1)
    val faceH = (unionBottom - unionTop).coerceAtLeast(1)

    // Expand: ±35% width, +50% top, +100% bottom (modest — covers hair/neck/upper chest)
    val expandW = (faceW * 0.35).roundToInt()
    val expandTop = (faceH * 0.50).roundToInt()
    val expandBottom = (faceH * 1.00).roundToInt()

    val x = (unionLeft - expandW).coerceAtLeast(0).roundDownEven()
    val y = (unionTop - expandTop).coerceAtLeast(0).roundDownEven()
    val w = (faceW + 2 * expandW).roundDownEven().coerceIn(8, videoW - x)
    val h = (faceH + expandTop + expandBottom).roundDownEven().coerceIn(8, videoH - y)

    // Time span: first to last observation + margin
    val startTime = (obs.first().timeSeconds - TIME_MARGIN_SECONDS).coerceAtLeast(0.0)
    val endTime = obs.last().timeSeconds + TIME_MARGIN_SECONDS

    return BlurRegion(
        x = x,
        y = y,
        width = w,
        height = h,
        startSeconds = startTime,
        endSeconds = endTime,
    )
}

// ─── FFmpeg filter graph ───

private fun buildLocalizedBlurFilter(
    regions: List<BlurRegion>,
    videoWidth: Int,
    videoHeight: Int,
    blurRadius: Int,
): String {
    if (regions.isEmpty()) return "null"

    val n = regions.size
    val parts = mutableListOf<String>()

    // Split input into N+1 streams: 1 base + N crops
    parts += "[0:v]split=${n + 1}" + (0 until n).joinToString("") { "[s$it]" } + "[base]"

    // Each branch: crop → boxblur (with chroma radius clamping).
    // boxblur=luma_r:luma_p:chroma_r — chroma plane is half the luma size for
    // yuv420p, so the chroma radius must be <= minDim/4 - 1 or FFmpeg aborts
    // with "Invalid chroma_param radius". We clamp both independently.
    regions.forEachIndexed { i, region ->
        val minDim = minOf(region.width, region.height)
        val lumaRadius = minOf(blurRadius, minDim / 2 - 1).coerceAtLeast(1)
        val chromaRadius = minOf(blurRadius, minDim / 4 - 1).coerceAtLeast(1)
        parts += "[s$i]crop=${region.width}:${region.height}:${region.x}:${region.y},boxblur=$lumaRadius:3:$chromaRadius[b$i]"
    }

    // Sequential overlay chain: base → overlay each blurred region with time enable
    var current = "[base]"
    regions.forEachIndexed { i, region ->
        val output = if (i == regions.lastIndex) "[blurred]" else "[v$i]"
        parts += "$current[b$i]overlay=${region.x}:${region.y}:enable='between(t,${formatFfmpeg(region.startSeconds)},${formatFfmpeg(region.endSeconds)})'$output"
        current = output
    }

    return parts.joinToString(";")
}

// ─── Utilities ───

private fun probeVideoDimensions(file: File): Pair<Int, Int> {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        Pair(w, h)
    } catch (e: Exception) {
        Pair(0, 0)
    } finally {
        runCatching { retriever.release() }
    }
}

private fun Bitmap.cropFace(faceBounds: Rect): Bitmap? {
    val padX = (faceBounds.width() * 0.20f).roundToInt()
    val padY = (faceBounds.height() * 0.20f).roundToInt()
    val left = (faceBounds.left - padX).coerceAtLeast(0)
    val top = (faceBounds.top - padY).coerceAtLeast(0)
    val right = (faceBounds.right + padX).coerceAtMost(width)
    val bottom = (faceBounds.bottom + padY).coerceAtLeast(0).coerceAtMost(height)
    val w = right - left
    val h = bottom - top
    if (w < 8 || h < 8) return null
    return Bitmap.createBitmap(this, left, top, w, h)
}

private fun Int.roundDownEven(): Int = if (this % 2 == 0) this else this - 1

private fun formatFfmpeg(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}