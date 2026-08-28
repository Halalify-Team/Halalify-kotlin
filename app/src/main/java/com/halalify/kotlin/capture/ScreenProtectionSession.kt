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
import java.util.concurrent.atomic.AtomicBoolean
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
    private var visionThread: HandlerThread? = null
    private var lastRenderedDetections: List<Detection> = emptyList()
    private var lastRenderedStyle: BlurStyle? = null
    private var lastRenderedIntensity = Float.NaN
    private var lastChangeCheckAt = 0L
    private var contentInvalidationSubscription: Closeable? = null
    private var contentMovementSubscription: Closeable? = null
    private val requestedContentGeneration = AtomicLong(0L)
    private val discardExistingProtection = AtomicBoolean(false)
    private val suppressDiscardedReappearance = AtomicBoolean(false)
    private var recentlyDiscardedDetections: List<Detection> = emptyList()
    private var protectionDiscardedAt = Long.MIN_VALUE
    @Volatile
    private var handledContentGeneration = 0L

    private val contentMovementLock = Any()
    private val contentGenerationLock = Any()
    private var pendingContentDeltaX = 0
    private var pendingContentDeltaY = 0

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
            ::requestContentAnalysis,
        )
        contentMovementSubscription = TrustedOverlayHost.subscribeToContentMovement(
            ::onContentMoved,
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
            if (!running || TrustedOverlayHost.isContentAnalysisSuspended) return
            val now = clock()
            if (now - lastChangeCheckAt < CHANGE_CHECK_INTERVAL_MS) return
            lastChangeCheckAt = now
            val plane = image.planes.firstOrNull() ?: return
            if (plane.pixelStride != RGBA_PIXEL_STRIDE) return

            applyPendingContentMovement()
            val observedContentGeneration = requestedContentGeneration.get()
            val externallyInvalidated = observedContentGeneration != handledContentGeneration
            if (externallyInvalidated) {
                if (discardExistingProtection.getAndSet(false)) {
                    if (suppressDiscardedReappearance.getAndSet(false)) {
                        recentlyDiscardedDetections = lastRenderedDetections
                        protectionDiscardedAt = now
                    } else {
                        recentlyDiscardedDetections = emptyList()
                    }
                    protectionTracker.reset()
                    newProtectionConfirmation.reset()
                    lastRenderedDetections = emptyList()
                    overlay.update(emptyList())
                }
                frameActivityDetector.reset()
                handledContentGeneration = observedContentGeneration
                Log.d(TAG, "Analyzing changed content while retaining visible protection.")
            }

            val sample = plane.sampleGrid(
                width = image.width,
                height = image.height,
                protectedDetections = lastRenderedDetections,
            )
            val currentVisualSettings = visualSettings
            val currentVisualSettingsVersion = visualSettingsVersion
            val visualSettingsChanged = currentVisualSettingsVersion != renderedVisualSettingsVersion
            val activityReason = frameActivityDetector.analysisReason(sample, now)
            val contentChanged =
                externallyInvalidated ||
                    activityReason == FrameAnalysisReason.CONTENT_CHANGED
            val reason = if (contentChanged || visualSettingsChanged) {
                FrameAnalysisReason.CONTENT_CHANGED
            } else {
                activityReason
            } ?: return
            if (!analysisPolicy.shouldAnalyze(reason)) return

            // A moving page must be inspected with the full-screen pass first.
            // Otherwise the rotating native cycle can begin at the opposite
            // detail tile and leave new visible images unprotected for another
            // two inferences.
            if (
                contentChanged ||
                reason == FrameAnalysisReason.SAFETY_REFRESH
            ) {
                visionProcessor.restartAnalysisCycle()
            }
            plane.buffer.rewind()
            val inferenceStartedAt = clock()
            val rawDetections = visionProcessor.process(
                rgbaBuffer = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                rotationDegrees = 0,
                timestampNs = image.timestamp,
            ).filter(Detection::isUsableDetection)
            Log.d(
                TAG,
                "inference_ms=${clock() - inferenceStartedAt} raw=${rawDetections.size} reason=$reason",
            )
            val inferenceFinishedAt = clock()
            val discardedSuppressionActive =
                recentlyDiscardedDetections.isNotEmpty() &&
                    inferenceFinishedAt - protectionDiscardedAt <
                    RECENTLY_DISCARDED_SUPPRESSION_MS
            val eligibleRawDetections = filterRecentlyDiscardedDetections(
                detections = rawDetections,
                discardedDetections = recentlyDiscardedDetections,
                suppressionActive = discardedSuppressionActive,
            )
            if (!discardedSuppressionActive) recentlyDiscardedDetections = emptyList()
            // INITIAL/content-change detections still require two-frame
            // confirmation. A STABILIZATION result is already a delayed clean
            // observation, and may come from a non-overlapping portrait tile;
            // requiring the same box in the next tile would make small bottom
            // avatars impossible to confirm.
            val confirmationRequired = requiresNewProtectionConfirmation(
                hasExistingProtection = lastRenderedDetections.isNotEmpty(),
                reason = reason,
            )
            val detections = newProtectionConfirmation.apply(
                detections = eligibleRawDetections,
                confirmationRequired = confirmationRequired,
            )
            if (
                !running ||
                requestedContentGeneration.get() != observedContentGeneration
            ) return

            val hasProtectedObservation = detections.any(Detection::shouldBlur)
            val protectionAging = decideProtectionAging(
                contentChanged = contentChanged,
                reason = reason,
                hasProtectedObservation = hasProtectedObservation,
            )
            val protectedDetections = protectionTracker.update(
                detections = detections,
                contentChanged = protectionAging.contentChanged,
                safetyRefresh = protectionAging.safetyRefresh,
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
                    contentGeneration = observedContentGeneration,
                    forceOverlayRender = externallyInvalidated,
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
        contentGeneration: Long,
        forceOverlayRender: Boolean,
    ) {
        val settingsChanged = visualSettingsVersion != renderedVisualSettingsVersion ||
                lastRenderedStyle != visualSettings.style ||
                lastRenderedIntensity != visualSettings.intensity
        val protectionChanged = !sameDetections(lastRenderedDetections, protectedDetections)
        // The accessibility overlay host can be destroyed and recreated while
        // the detector/tracker state stays unchanged. An external invalidation
        // must therefore rebuild the actual WindowManager surfaces as well.
        val needsOverlayRender = forceOverlayRender || settingsChanged || protectionChanged ||
                statePublisher.isPreviewRequested
        if (!needsOverlayRender) {
            publishDetectionStatus(detections, protectedDetections.size)
            return
        }
        if (croppedBitmap == null) {
            val accepted = synchronized(contentGenerationLock) {
                if (
                    running &&
                    requestedContentGeneration.get() == contentGeneration
                ) {
                    overlay.update(emptyList())
                    true
                } else {
                    false
                }
            }
            if (!accepted) return
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
        val accepted = synchronized(contentGenerationLock) {
            if (
                running &&
                requestedContentGeneration.get() == contentGeneration
            ) {
                overlay.update(rendered.overlayRegions)
                true
            } else {
                false
            }
        }
        if (!accepted) {
            rendered.overlayRegions.forEach { region ->
                FrameBlurRenderer.releaseOverlayBitmap(region.bitmap)
            }
            return
        }
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

    private fun requestContentAnalysis(
        shouldDiscardExistingProtection: Boolean,
        shouldSuppressDiscardedReappearance: Boolean,
    ) {
        if (!running || closed) return
        synchronized(contentGenerationLock) {
            if (shouldDiscardExistingProtection) {
                discardExistingProtection.set(true)
            }
            if (shouldSuppressDiscardedReappearance) {
                suppressDiscardedReappearance.set(true)
            }
            requestedContentGeneration.incrementAndGet()
            if (shouldDiscardExistingProtection) {
                // Accessibility events arrive on the main looper. Clear the
                // obsolete window now instead of waiting behind an inference.
                overlay.update(emptyList())
            }
        }
    }

    private fun onContentMoved(deltaX: Int, deltaY: Int) {
        if (
            !running ||
            closed ||
            TrustedOverlayHost.isContentAnalysisSuspended ||
            (deltaX == 0 && deltaY == 0)
        ) {
            return
        }
        overlay.offset(deltaX, deltaY)
        synchronized(contentMovementLock) {
            pendingContentDeltaX += deltaX
            pendingContentDeltaY += deltaY
        }
        // Invalidate any inference that started before this scroll event. The
        // latest captured frame will analyze the content at its new position.
        requestedContentGeneration.incrementAndGet()
    }

    private fun applyPendingContentMovement() {
        val movement = synchronized(contentMovementLock) {
            Pair(pendingContentDeltaX, pendingContentDeltaY).also {
                pendingContentDeltaX = 0
                pendingContentDeltaY = 0
            }
        }
        if (movement.first == 0 && movement.second == 0) return

        val displayBounds = context.getRealDisplayBounds()
        val normalizedDeltaX =
            movement.first.toFloat() / displayBounds.width().coerceAtLeast(1)
        val normalizedDeltaY =
            movement.second.toFloat() / displayBounds.height().coerceAtLeast(1)
        lastRenderedDetections = protectionTracker.offset(
            deltaX = normalizedDeltaX,
            deltaY = normalizedDeltaY,
        )
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

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        running = false
        closeResource("content invalidation subscription") {
            contentInvalidationSubscription?.close()
        }
        contentInvalidationSubscription = null
        closeResource("content movement subscription") {
            contentMovementSubscription?.close()
        }
        contentMovementSubscription = null
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
        const val RECENTLY_DISCARDED_SUPPRESSION_MS = 3_000L
        const val JPEG_QUALITY = 70
        const val RGBA_PIXEL_STRIDE = 4
        const val SAMPLE_COLUMNS = 20
        const val SAMPLE_ROWS = 32
        const val IGNORED_FRAME_SAMPLE = -1
        const val FEMALE_CLASS_ID = 0
        const val MALE_CLASS_ID = 1
        const val TAG = "HalalifyVision"
    }
}
