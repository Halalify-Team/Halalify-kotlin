package com.halalify.kotlin.capture

import android.content.Context
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
import com.halalify.kotlin.media.getRealDisplayBounds
import com.halalify.kotlin.model.Detection
import com.halalify.kotlin.model.VisionProcessor
import com.halalify.kotlin.settings.BlurSettings
import com.halalify.kotlin.settings.BlurStyle
import com.halalify.kotlin.settings.normalizeBlurIntensity
import java.io.ByteArrayOutputStream
import java.io.Closeable
import kotlin.math.roundToInt

/** Owns the visual capture pipeline and all resources created for one projection session. */
internal class ScreenProtectionSession(
    private val context: Context,
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

    @Volatile
    private var visualSettings = VisualSettings(
        style = settings.style,
        intensity = normalizeBlurIntensity(settings.intensity),
    )

    @Volatile
    private var visualSettingsVersion = 0L
    private var renderedVisualSettingsVersion = Long.MIN_VALUE
    private var imageReader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var visionThread: HandlerThread? = null
    private var lastRenderedDetections: List<Detection> = emptyList()
    private var lastRenderedStyle: BlurStyle? = null
    private var lastRenderedIntensity = Float.NaN
    private var lastChangeCheckAt = 0L

    @Volatile
    private var running = false

    @Volatile
    private var closed = false

    fun updateVisualSettings(style: BlurStyle, intensity: Float) {
        if (closed) return
        visualSettings = VisualSettings(
            style = style,
            intensity = normalizeBlurIntensity(intensity),
        )
        visualSettingsVersion += 1L
    }

    @Synchronized
    fun start() {
        check(!closed) { "Screen protection session is closed." }
        check(!running) { "Screen protection session is already running." }

        val displayBounds = context.getRealDisplayBounds()
        val realWidth = displayBounds.width().coerceAtLeast(1)
        val realHeight = displayBounds.height().coerceAtLeast(1)
        val width = CAPTURE_WIDTH.coerceAtMost(realWidth)
        val height = ((realHeight.toFloat() * width) / realWidth)
            .roundToInt()
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
                    context.resources.configuration.densityDpi,
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
            )
            val currentVisualSettings = visualSettings
            val currentVisualSettingsVersion = visualSettingsVersion
            val visualSettingsChanged = currentVisualSettingsVersion != renderedVisualSettingsVersion
            val activityReason = frameActivityDetector.analysisReason(sample, now)
            val reason = if (visualSettingsChanged) {
                frameActivityDetector.reset()
                FrameAnalysisReason.CONTENT_CHANGED
            } else {
                activityReason
            } ?: return
            if (!analysisPolicy.shouldAnalyze(reason)) return

            plane.buffer.rewind()
            val detections = visionProcessor.process(
                rgbaBuffer = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                rotationDegrees = 0,
                timestampNs = image.timestamp,
            ).filter { detection ->
                !detection.shouldBlur || detection.isReasonableProtectionRegion()
            }
            if (!running) return

            val protectedDetections = protectionTracker.update(
                detections,
                contentChanged = reason == FrameAnalysisReason.CONTENT_CHANGED,
                safetyRefresh = reason == FrameAnalysisReason.SAFETY_REFRESH,
            )
            val frameBitmap = if (
                protectedDetections.isNotEmpty() || statePublisher.isPreviewRequested
            ) {
                plane.toCroppedBitmap(image.width, image.height)
            } else {
                null
            }
            try {
                renderFrame(
                    croppedBitmap = frameBitmap,
                    detections = detections,
                    protectedDetections = protectedDetections,
                    visualSettings = currentVisualSettings,
                    visualSettingsVersion = currentVisualSettingsVersion,
                    contentChanged = reason == FrameAnalysisReason.CONTENT_CHANGED,
                )
            } finally {
                frameBitmap?.recycle()
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
        croppedBitmap: Bitmap?,
        detections: List<Detection>,
        protectedDetections: List<Detection>,
        visualSettings: VisualSettings,
        visualSettingsVersion: Long,
        contentChanged: Boolean,
    ) {
        val settingsChanged = visualSettingsVersion != renderedVisualSettingsVersion ||
                lastRenderedStyle != visualSettings.style ||
                lastRenderedIntensity != visualSettings.intensity
        val protectionChanged = !sameDetections(lastRenderedDetections, protectedDetections)
        val needsOverlayRender = contentChanged || settingsChanged || protectionChanged ||
                statePublisher.isPreviewRequested
        if (!needsOverlayRender) {
            publishDetectionStatus(detections, protectedDetections.size)
            return
        }
        if (croppedBitmap == null) {
            overlay.update(emptyList())
            if (this.visualSettingsVersion == visualSettingsVersion) {
                renderedVisualSettingsVersion = visualSettingsVersion
            }
            lastRenderedDetections = protectedDetections
            lastRenderedStyle = visualSettings.style
            lastRenderedIntensity = visualSettings.intensity
            publishDetectionStatus(detections, 0)
            return
        }
        val rendered = FrameBlurRenderer.renderSelectedDetections(
            croppedBitmap,
            protectedDetections,
            visualSettings.style,
            visualSettings.intensity,
            includePreview = statePublisher.isPreviewRequested,
        )
        // The renderer creates independent region bitmaps before the source
        // frame is recycled. The overlay owns those bitmaps from this point.
        overlay.update(rendered.overlayRegions)
        if (this.visualSettingsVersion == visualSettingsVersion) {
            renderedVisualSettingsVersion = visualSettingsVersion
        }
        lastRenderedDetections = protectedDetections
        lastRenderedStyle = visualSettings.style
        lastRenderedIntensity = visualSettings.intensity
        publishDetectionStatus(detections, rendered.blurredCount)
        if (statePublisher.isPreviewRequested) {
            val preview = ByteArrayOutputStream().also { stream ->
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }.toByteArray()
            statePublisher.updateState { current -> current.copy(previewJpeg = preview) }
        }
    }

    private fun sameDetections(first: List<Detection>, second: List<Detection>): Boolean {
        if (first.size != second.size) return false
        return first.indices.all { index ->
            val left = first[index]
            val right = second[index]
            left.classId == right.classId &&
                    left.shouldBlur == right.shouldBlur &&
                    left.isNsfw == right.isNsfw &&
                    left.x1 == right.x1 && left.y1 == right.y1 &&
                    left.x2 == right.x2 && left.y2 == right.y2
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
        closeResource("image listener") { imageReader?.setOnImageAvailableListener(null, null) }
        closeResource("virtual display") { display?.release() }
        display = null
        closeResource("image reader") { imageReader?.close() }
        imageReader = null
        closeResource("vision thread") { visionThread?.quitSafely() }
        visionThread = null
        closeResource("vision processor") { visionProcessor.close() }
        closeResource("protection overlay") { overlay.close() }
        lastRenderedDetections = emptyList()
    }

    private inline fun closeResource(name: String, close: () -> Unit) {
        try {
            close()
        } catch (error: Exception) {
            Log.w(TAG, "Could not close $name.", error)
        }
    }

    private data class VisualSettings(
        val style: BlurStyle,
        val intensity: Float,
    )

    private fun Image.Plane.toBitmap(width: Int, height: Int): Bitmap {
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        return createBitmap(paddedWidth, height).also { bitmap -> bitmap.copyPixelsFromBuffer(buffer) }
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
    ): IntArray {
        val columns = SAMPLE_COLUMNS.coerceAtMost(width)
        val rows = SAMPLE_ROWS.coerceAtMost(height)
        val sample = IntArray(columns * rows)
        var outputIndex = 0
        for (row in 0 until rows) {
            val y = (((row + 0.5F) * height) / rows).toInt().coerceIn(0, height - 1)
            for (column in 0 until columns) {
                val x = (((column + 0.5F) * width) / columns).toInt().coerceIn(0, width - 1)
                val sourceIndex = y * rowStride + x * pixelStride
                val red = buffer.get(sourceIndex).toInt() and 0xFF
                val green = buffer.get(sourceIndex + 1).toInt() and 0xFF
                val blue = buffer.get(sourceIndex + 2).toInt() and 0xFF
                sample[outputIndex++] = red shl 16 or (green shl 8) or blue
            }
        }
        return sample
    }

    private fun Detection.isReasonableProtectionRegion(): Boolean {
        val regionWidth = x2 - x1
        val regionHeight = y2 - y1
        val area = regionWidth * regionHeight
        return x1.isFinite() && y1.isFinite() &&
                x2.isFinite() && y2.isFinite() &&
                regionWidth > 0F && regionHeight > 0F &&
                x1 >= 0F && y1 >= 0F && x2 <= 1F && y2 <= 1F &&
                area <= MAX_PROTECTION_AREA
    }

    private companion object {
        const val DISPLAY_NAME = "HalalifyPreview"
        const val VISION_THREAD_NAME = "halalify-vision"
        const val CAPTURE_WIDTH = 416
        const val MAX_IMAGES = 2
        // Check every display frame so a fast swipe is noticed without waiting
        // for the next 30 FPS boundary. Inference still runs on the vision
        // thread and ImageReader drops stale frames when it is busy.
        const val CHANGE_CHECK_INTERVAL_MS = 16L
        const val JPEG_QUALITY = 70
        const val RGBA_PIXEL_STRIDE = 4
        const val SAMPLE_COLUMNS = 20
        const val SAMPLE_ROWS = 32
        const val MAX_PROTECTION_AREA = 0.60F
        const val FEMALE_CLASS_ID = 0
        const val MALE_CLASS_ID = 1
        const val TAG = "HalalifyVision"
    }
}
