package com.halalify.kotlin.capture

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import com.halalify.kotlin.media.FrameBlurRenderer
import com.halalify.kotlin.media.ProtectionOverlay
import com.halalify.kotlin.media.ProtectionTracker
import com.halalify.kotlin.model.Detection
import com.halalify.kotlin.model.VisionProcessor
import com.halalify.kotlin.settings.BlurSettings
import java.io.ByteArrayOutputStream
import java.io.Closeable

/** Owns the visual capture pipeline and all resources created for one projection session. */
internal class ScreenProtectionSession(
    private val resources: Resources,
    private val mediaProjection: MediaProjection,
    private val settings: BlurSettings,
    private val visionProcessor: VisionProcessor,
    private val overlay: ProtectionOverlay,
    private val statePublisher: CaptureStatePublisher,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : Closeable {
    private val protectionTracker = ProtectionTracker()
    private val frameActivityDetector = FrameActivityDetector()
    private val analysisPolicy = VisualAnalysisPolicy(settings)
    private var imageReader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var visionThread: HandlerThread? = null
    private var lastProtectedDetections: List<Detection> = emptyList()
    private var lastChangeCheckAt = 0L

    @Volatile
    private var running = false

    @Volatile
    private var closed = false

    @Synchronized
    fun start() {
        check(!closed) { "Screen protection session is closed." }
        check(!running) { "Screen protection session is already running." }

        val metrics = resources.displayMetrics
        val width = CAPTURE_WIDTH.coerceAtMost(metrics.widthPixels)
        val height = (metrics.heightPixels.toFloat() * width / metrics.widthPixels)
            .toInt()
            .coerceAtLeast(1)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        val handlerThread = HandlerThread(VISION_THREAD_NAME).apply { start() }
        imageReader = reader
        visionThread = handlerThread
        lastChangeCheckAt = 0L
        running = true

        try {
            reader.setOnImageAvailableListener(
                { source -> onImageAvailable(source) },
                Handler(handlerThread.looper),
            )
            display = checkNotNull(
                mediaProjection.createVirtualDisplay(
                    DISPLAY_NAME,
                    width,
                    height,
                    resources.configuration.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    null,
                ),
            ) { "Android could not create the screen capture display." }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun onImageAvailable(source: ImageReader) {
        val image = source.acquireLatestImage() ?: return
        try {
            if (!running) return
            val now = clock()
            if (now - lastChangeCheckAt < CHANGE_CHECK_INTERVAL_MS) return
            lastChangeCheckAt = now
            val plane = image.planes.firstOrNull() ?: return
            if (plane.pixelStride != RGBA_PIXEL_STRIDE) return

            val sample = plane.sampleGrid(
                width = image.width,
                height = image.height,
                ignoredRegions = lastProtectedDetections,
            )
            val reason = frameActivityDetector.analysisReason(sample, now) ?: return
            if (!analysisPolicy.shouldAnalyze(reason)) return

            plane.buffer.rewind()
            val detections = visionProcessor.process(
                rgbaBuffer = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                rotationDegrees = 0,
                timestampNs = image.timestamp,
            )
            if (!running) return

            val frameBitmap = plane.toCroppedBitmap(image.width, image.height)
            try {
                val protectedDetections = protectionTracker.update(
                    detections,
                    contentChanged = reason == FrameAnalysisReason.CONTENT_CHANGED,
                )
                lastProtectedDetections = protectedDetections
                renderFrame(frameBitmap, detections, protectedDetections)
            } finally {
                frameBitmap.recycle()
            }
        } catch (error: Exception) {
            if (running) {
                statePublisher.updateState { current ->
                    current.copy(
                        message = "Vision frame failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        } finally {
            image.close()
        }
    }

    private fun renderFrame(
        croppedBitmap: Bitmap,
        detections: List<Detection>,
        protectedDetections: List<Detection>,
    ) {
        val rendered = FrameBlurRenderer.renderSelectedDetections(
            croppedBitmap,
            protectedDetections,
            settings.style,
            settings.intensity,
        )
        overlay.update(rendered.overlayRegions)
        publishDetectionStatus(detections, rendered.blurredCount)
        if (statePublisher.isPreviewRequested) {
            val preview = ByteArrayOutputStream().also { stream ->
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }.toByteArray()
            statePublisher.updateState { current -> current.copy(previewJpeg = preview) }
        }
    }

    private fun publishDetectionStatus(detections: List<Detection>, blurredCount: Int) {
        val femaleCount = detections.count { it.classId == FEMALE_CLASS_ID }
        val maleCount = detections.count { it.classId == MALE_CLASS_ID }
        val nsfwCount = detections.count(Detection::isNsfw)
        Log.d(TAG, "detections female=$femaleCount male=$maleCount nsfw=$nsfwCount blurred=$blurredCount")
        statePublisher.updateState { current ->
            current.copy(
                message = "Detected: $femaleCount female, $maleCount male, $nsfwCount NSFW - blurred: $blurredCount",
            )
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        running = false
        closeResource("image listener") {
            imageReader?.setOnImageAvailableListener(null, null)
        }
        closeResource("virtual display") { display?.release() }
        display = null
        closeResource("image reader") { imageReader?.close() }
        imageReader = null
        closeResource("vision thread") { visionThread?.quitSafely() }
        visionThread = null
        closeResource("vision processor") { visionProcessor.close() }
        closeResource("protection overlay") { overlay.close() }
        lastProtectedDetections = emptyList()
    }

    private inline fun closeResource(name: String, close: () -> Unit) {
        try {
            close()
        } catch (error: Exception) {
            Log.w(TAG, "Could not close $name.", error)
        }
    }

    private fun Image.Plane.toBitmap(width: Int, height: Int): Bitmap {
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        return createBitmap(paddedWidth, height).also { bitmap ->
            bitmap.copyPixelsFromBuffer(buffer)
        }
    }

    private fun Image.Plane.toCroppedBitmap(width: Int, height: Int): Bitmap {
        buffer.rewind()
        val paddedBitmap = toBitmap(width, height)
        return try {
            Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
        } catch (error: Exception) {
            paddedBitmap.recycle()
            throw error
        }.also {
            if (it !== paddedBitmap) paddedBitmap.recycle()
        }
    }

    private fun Image.Plane.sampleGrid(
        width: Int,
        height: Int,
        ignoredRegions: List<Detection>,
    ): IntArray {
        val columns = SAMPLE_COLUMNS.coerceAtMost(width)
        val rows = SAMPLE_ROWS.coerceAtMost(height)
        val sample = IntArray(columns * rows)
        var outputIndex = 0
        for (row in 0 until rows) {
            val y = (((row + 0.5F) * height) / rows).toInt().coerceIn(0, height - 1)
            for (column in 0 until columns) {
                val x = (((column + 0.5F) * width) / columns).toInt().coerceIn(0, width - 1)
                if (ignoredRegions.any { detection ->
                        !detection.coversMostOfFrame() && detection.contains(x, y, width, height)
                    }) {
                    sample[outputIndex++] = IGNORED_SAMPLE
                    continue
                }
                val sourceIndex = y * rowStride + x * pixelStride
                val red = buffer.get(sourceIndex).toInt() and 0xFF
                val green = buffer.get(sourceIndex + 1).toInt() and 0xFF
                val blue = buffer.get(sourceIndex + 2).toInt() and 0xFF
                sample[outputIndex++] = red shl 16 or (green shl 8) or blue
            }
        }
        return sample
    }

    private fun Detection.contains(x: Int, y: Int, width: Int, height: Int): Boolean {
        val normalizedX = x.toFloat() / width
        val normalizedY = y.toFloat() / height
        val boxWidth = x2 - x1
        val boxHeight = y2 - y1
        val padX = boxWidth * 0.04f
        val padY = boxHeight * 0.04f
        val left = x1 - padX
        val right = x2 + padX
        val top = y1 - padY
        val bottom = y2 + padY
        return normalizedX in left..right && normalizedY in top..bottom
    }

    private fun Detection.coversMostOfFrame(): Boolean =
        (x2 - x1) * (y2 - y1) >= FULL_FRAME_AREA_THRESHOLD

    private companion object {
        const val DISPLAY_NAME = "HalalifyPreview"
        const val VISION_THREAD_NAME = "halalify-vision"
        const val CAPTURE_WIDTH = 416
        const val MAX_IMAGES = 2

        // Check frames at ~30 FPS so scrolling and page flips update immediately.
        const val CHANGE_CHECK_INTERVAL_MS = 33L
        const val JPEG_QUALITY = 70
        const val RGBA_PIXEL_STRIDE = 4
        const val SAMPLE_COLUMNS = 20
        const val SAMPLE_ROWS = 32
        const val IGNORED_SAMPLE = -1

        // Match FrameBlurRenderer and ignore the complete protected subject
        // box while checking whether the underlying page changed.
        const val NSFW_CLASS_ID = 3
        const val FULL_FRAME_AREA_THRESHOLD = 0.85F
        const val FEMALE_CLASS_ID = 0
        const val MALE_CLASS_ID = 1
        const val TAG = "HalalifyVision"
    }
}
