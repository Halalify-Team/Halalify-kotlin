package com.halalify.kotlin.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.halalify.kotlin.model.Detection
import com.halalify.kotlin.settings.BlurStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class FrameBlurResult(
    val blurredCount: Int,
    val overlayRegions: List<OverlayRegion>,
)

internal data class OverlayRegion(
    /** Fully obscured bitmap owned by the overlay after [ProtectionOverlay.update]. */
    val bitmap: Bitmap,
    val bounds: Rect,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

internal object FrameBlurRenderer {
    fun renderSelectedDetections(
        bitmap: Bitmap,
        detections: List<Detection>,
        style: BlurStyle,
        intensity: Float,
    ): FrameBlurResult {
        val selected = detections.filter(Detection::shouldBlur)
        if (selected.isEmpty()) return FrameBlurResult(0, emptyList())

        val previewCanvas = Canvas(bitmap)
        val protectedPaint = Paint().apply {
            // Pixelated style uses nearest-neighbour scaling and hard edges.
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
        }
        var blurredCount = 0
        val overlayRegions = mutableListOf<OverlayRegion>()
        try {
            selected.forEach { detection ->
                val rect = detection.toTightRect(bitmap.width, bitmap.height) ?: return@forEach
                overlayRegions += createOverlayRegion(
                    source = bitmap,
                    rect = rect,
                    style = style,
                    protectedPaint = protectedPaint,
                    intensity = intensity,
                )
                blurredCount += 1
            }
            overlayRegions.forEach { region ->
                previewCanvas.drawBitmap(
                    region.bitmap,
                    region.bounds.left.toFloat(),
                    region.bounds.top.toFloat(),
                    protectedPaint,
                )
            }
        } catch (error: Throwable) {
            overlayRegions.forEach { region ->
                region.bitmap.recycle()
            }
            throw error
        }
        return FrameBlurResult(blurredCount, overlayRegions)
    }

    private fun createOverlayRegion(
        source: Bitmap,
        rect: Rect,
        style: BlurStyle,
        protectedPaint: Paint,
        intensity: Float,
    ): OverlayRegion {
        val patch = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height()).apply {
            setHasAlpha(false)
        }
        var tiny: Bitmap? = null
        var protectedPatch: Bitmap? = null
        try {
            // Scale based on the larger dimension so tall or wide regions
            // don't accumulate dozens of small vertical/horizontal blocks.
            // At maximum intensity (1.0), the major dimension has at most 5 blocks,
            // producing a 4x4 or 3x5 macro-mosaic matching the reference.
            val safeIntensity = if (intensity.isFinite()) {
                intensity.coerceIn(0f, 1f)
            } else {
                1f
            }
            val maxGridDim = (MAX_GRID_MAJOR -
                safeIntensity * (MAX_GRID_MAJOR - MIN_GRID_MAJOR))
                .roundToInt()
                .coerceIn(MIN_GRID_MAJOR.roundToInt(), MAX_GRID_MAJOR.roundToInt())

            val (tinyWidth, tinyHeight) = if (rect.width() >= rect.height()) {
                val w = maxGridDim
                val h = (maxGridDim * (rect.height().toFloat() / rect.width().coerceAtLeast(1).toFloat()))
                    .roundToInt().coerceIn(2, maxGridDim)
                w to h
            } else {
                val h = maxGridDim
                val w = (maxGridDim * (rect.width().toFloat() / rect.height().coerceAtLeast(1).toFloat()))
                    .roundToInt().coerceIn(2, maxGridDim)
                w to h
            }

            tiny = patch.scale(tinyWidth.coerceAtLeast(1), tinyHeight.coerceAtLeast(1))
            protectedPatch = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.RGB_565)
            val patchCanvas = Canvas(protectedPatch)
            val patchBounds = Rect(0, 0, protectedPatch.width, protectedPatch.height)
            patchCanvas.drawBitmap(tiny, null, patchBounds, protectedPaint)
            return OverlayRegion(
                bitmap = protectedPatch,
                bounds = Rect(rect),
                sourceWidth = source.width,
                sourceHeight = source.height,
            )
        } catch (error: Throwable) {
            protectedPatch?.recycle()
            patch.recycle()
            throw error
        } finally {
            if (tiny != null && tiny !== patch) tiny.recycle()
            patch.recycle()
        }
    }

    private fun Detection.toTightRect(width: Int, height: Int): Rect? {
        // Use a tight, accurate bounding box around the detected subject
        // with minimal padding (2%) to cleanly cover edges without spilling
        // into surrounding UI elements, buttons, or text.
        val boxWidth = (x2 - x1)
        val boxHeight = (y2 - y1)
        val padX = boxWidth * 0.02f
        val padY = boxHeight * 0.02f

        val rawLeft = (x1 - padX) * width
        val rawTop = (y1 - padY) * height
        val rawRight = (x2 + padX) * width
        val rawBottom = (y2 + padY) * height

        val left = ceil(rawLeft).toInt().coerceIn(0, width)
        val top = ceil(rawTop).toInt().coerceIn(0, height)
        val right = floor(rawRight).toInt().coerceIn(0, width)
        val bottom = floor(rawBottom).toInt().coerceIn(0, height)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    }

    // At maximum intensity the mosaic grid has at most 5 blocks on the major dimension,
    // producing giant abstract color blocks identical to the reference image.
    // At minimum intensity, up to 18 blocks allow lighter censoring.
    private const val MIN_GRID_MAJOR = 5f
    private const val MAX_GRID_MAJOR = 18f
}
