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
import com.halalify.kotlin.media.TrustedOverlayHost
import com.halalify.kotlin.media.getRealDisplayBounds
import com.halalify.kotlin.model.Detection
import com.halalify.kotlin.model.VisionProcessor
import com.halalify.kotlin.settings.BlurSettings
import com.halalify.kotlin.settings.BlurStyle
import com.halalify.kotlin.settings.normalizeBlurIntensity
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
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
    private val newProtectionConfirmation = NewProtectionConfirmation()
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
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensityDpi = 0
    private var refreshedContentGeneration = Long.MIN_VALUE
    private var visionThread: HandlerThread? = null
    private var lastRenderedDetections: List<Detection> = emptyList()
    private var lastRenderedStyle: BlurStyle? = null
    private var lastRenderedIntensity = Float.NaN
    private var lastChangeCheckAt = 0L
    private var contentInvalidationSubscription: Closeable? = null
    private val requestedContentGeneration = AtomicLong(0L)
    @Volatile
    private var handledContentGeneration = 0L

    @Volatile
    private var cleanFrameAfterMs = Long.MIN_VALUE

    @Volatile
    private var restoreProtectionAfterCleanCapture = false

    @Volatile
    private var pendingCleanFrameReason = FrameAnalysisReason.CONTENT_CHANGED

    private var newProtectionAllowedAfterMs = Long.MIN_VALUE

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
        captureWidth = width
        captureHeight = height
        captureDensityDpi = context.resources.configuration.densityDpi
        lastChangeCheckAt = 0L
        running = true
        contentInvalidationSubscription = TrustedOverlayHost.subscribeToContentInvalidation(
            ::requestCleanContentFrame,
        )

        try {
            reader.setOnImageAvailableListener(
                { source -> onImageAvailable(source) },
                Handler(handlerThread.looper),
            )
            display = createVirtualDisplay(reader)
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

            val observedContentGeneration = requestedContentGeneration.get()
            val externallyInvalidated = observedContentGeneration != handledContentGeneration
            if (externallyInvalidated && now < cleanFrameAfterMs) return
            val shouldRestoreProtection =
                externallyInvalidated && restoreProtectionAfterCleanCapture
            val cleanFrameReason = pendingCleanFrameReason
            if (
                externallyInvalidated &&
                !shouldRestoreProtection &&
                refreshedContentGeneration != observedContentGeneration
            ) {
                // Secure overlay layers can leave unchanged tiles from the
                // previous app in MediaProjection's buffer. Reattaching the
                // capture surface forces a complete composition of the new
                // screen instead of classifying those stale pixels.
                refreshVirtualDisplay()
                refreshedContentGeneration = observedContentGeneration
                cleanFrameAfterMs = clock() + CLEAN_FRAME_SETTLE_MS
                Log.d(TAG, "Reattached capture surface after navigation or scroll.")
                return
            }
            if (externallyInvalidated) {
                if (!shouldRestoreProtection) {
                    protectionTracker.reset()
                    newProtectionConfirmation.reset()
                    lastRenderedDetections = emptyList()
                    newProtectionAllowedAfterMs =
                        now + POST_NAVIGATION_CONFIRMATION_DELAY_MS
                }
                frameActivityDetector.reset()
                handledContentGeneration = observedContentGeneration
                Log.d(TAG, "Processing clean frame after navigation or scroll.")
            }

            val sample = plane.sampleGrid(
                width = image.width,
                height = image.height,
                protectedDetections =
                    if (externallyInvalidated) emptyList() else lastRenderedDetections,
            )
            val currentVisualSettings = visualSettings
            val currentVisualSettingsVersion = visualSettingsVersion
            val visualSettingsChanged = currentVisualSettingsVersion != renderedVisualSettingsVersion
            val activityReason = frameActivityDetector.analysisReason(sample, now)
            if (
                !externallyInvalidated &&
                (
                    activityReason == FrameAnalysisReason.CONTENT_CHANGED ||
                        activityReason == FrameAnalysisReason.SAFETY_REFRESH
                    ) &&
                lastRenderedDetections.isNotEmpty()
            ) {
                // A sensitive overlay is redacted from MediaProjection. Never
                // age a protected track using that incomplete frame. Briefly
                // remove our windows and classify the next clean frame.
                requestCleanContentFrame(
                    reason = activityReason,
                    restoreProtectionAfterCapture = true,
                )
                return
            }
            val reason = if (externallyInvalidated || visualSettingsChanged) {
                frameActivityDetector.reset()
                if (externallyInvalidated) cleanFrameReason else FrameAnalysisReason.CONTENT_CHANGED
            } else {
                activityReason
            } ?: return
            if (!analysisPolicy.shouldAnalyze(reason)) return

            if (shouldRestoreProtection && lastRenderedDetections.isNotEmpty()) {
                restoreOverlayFromCleanFrame(
                    plane = plane,
                    image = image,
                    visualSettings = currentVisualSettings,
                )
            }
            plane.buffer.rewind()
            val rawDetections = visionProcessor.process(
                rgbaBuffer = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                rotationDegrees = 0,
                timestampNs = image.timestamp,
            ).filter(Detection::isUsableDetection)
            val confirmationRequired = lastRenderedDetections.isEmpty()
            val detections = if (
                confirmationRequired &&
                now < newProtectionAllowedAfterMs
            ) {
                newProtectionConfirmation.reset()
                rawDetections.map { detection ->
                    if (detection.shouldBlur) {
                        detection.copy(shouldBlur = false)
                    } else {
                        detection
                    }
                }
            } else {
                newProtectionConfirmation.apply(
                    detections = rawDetections,
                    confirmationRequired = confirmationRequired,
                )
            }
            if (
                !running ||
                requestedContentGeneration.get() != observedContentGeneration
            ) return

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

    private fun requestCleanContentFrame() {
        requestCleanContentFrame(
            reason = FrameAnalysisReason.CONTENT_CHANGED,
            restoreProtectionAfterCapture = false,
        )
    }

    private fun requestCleanContentFrame(
        reason: FrameAnalysisReason,
        restoreProtectionAfterCapture: Boolean,
    ) {
        if (!running || closed) return
        val cleanFrameAlreadyPending =
            requestedContentGeneration.get() != handledContentGeneration
        if (cleanFrameAlreadyPending) {
            // A real navigation/scroll request supersedes a periodic probe.
            if (!restoreProtectionAfterCapture && restoreProtectionAfterCleanCapture) {
                this.restoreProtectionAfterCleanCapture = false
                pendingCleanFrameReason = FrameAnalysisReason.CONTENT_CHANGED
                cleanFrameAfterMs = clock() + CLEAN_FRAME_SETTLE_MS
                requestedContentGeneration.incrementAndGet()
            }
            return
        }

        // Removing the old overlay first prevents the next inference from
        // classifying Halalify's own pixels. A short compositor grace period
        // also drops the final stale frame produced during app switches.
        this.restoreProtectionAfterCleanCapture = restoreProtectionAfterCapture
        pendingCleanFrameReason = reason
        cleanFrameAfterMs = clock() + CLEAN_FRAME_SETTLE_MS
        requestedContentGeneration.incrementAndGet()
        Log.d(TAG, "Content changed outside Halalify; clearing stale overlay.")
        overlay.update(emptyList())
    }

    /**
     * A periodic clean probe needs the overlay hidden for capture, not for the
     * entire model run. Rebuild the previous protection from that clean frame
     * immediately; the confirmed result replaces it after inference.
     */
    private fun restoreOverlayFromCleanFrame(
        plane: Image.Plane,
        image: Image,
        visualSettings: VisualSettings,
    ) {
        plane.buffer.rewind()
        val cleanBitmap = plane.toCroppedBitmap(image.width, image.height)
        try {
            val restored = FrameBlurRenderer.renderSelectedDetections(
                cleanBitmap,
                lastRenderedDetections,
                visualSettings.style,
                visualSettings.intensity,
                includePreview = false,
            )
            overlay.update(restored.overlayRegions)
        } finally {
            cleanBitmap.recycle()
        }
    }

    private fun createVirtualDisplay(reader: ImageReader): VirtualDisplay =
        checkNotNull(
            mediaProjection.createVirtualDisplay(
                DISPLAY_NAME,
                captureWidth,
                captureHeight,
                captureDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            ),
        ) { "Android could not create the screen capture display." }

    private fun refreshVirtualDisplay() {
        val reader = checkNotNull(imageReader) { "Screen capture reader is unavailable." }
        val activeDisplay = checkNotNull(display) { "Virtual display is unavailable." }
        activeDisplay.setSurface(null)
        activeDisplay.setSurface(reader.surface)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        running = false
        closeResource("content invalidation subscription") {
            contentInvalidationSubscription?.close()
        }
        contentInvalidationSubscription = null
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
        protectedDetections: List<Detection>,
    ): IntArray {
        val columns = SAMPLE_COLUMNS.coerceAtMost(width)
        val rows = SAMPLE_ROWS.coerceAtMost(height)
        val sample = IntArray(columns * rows)
        var outputIndex = 0
        for (row in 0 until rows) {
            val y = (((row + 0.5F) * height) / rows).toInt().coerceIn(0, height - 1)
            for (column in 0 until columns) {
                val x = (((column + 0.5F) * width) / columns).toInt().coerceIn(0, width - 1)
                val normalizedX = (x + 0.5F) / width
                val normalizedY = (y + 0.5F) / height
                if (isProtectedSample(normalizedX, normalizedY, protectedDetections)) {
                    sample[outputIndex++] = IGNORED_FRAME_SAMPLE
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
        const val IGNORED_FRAME_SAMPLE = -1
        const val CLEAN_FRAME_SETTLE_MS = 80L
        const val POST_NAVIGATION_CONFIRMATION_DELAY_MS = 1_500L
        const val FEMALE_CLASS_ID = 0
        const val MALE_CLASS_ID = 1
        const val TAG = "HalalifyVision"
    }
}
