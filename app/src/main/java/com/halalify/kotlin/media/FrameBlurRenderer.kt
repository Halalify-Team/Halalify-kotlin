package com.halalify.kotlin.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import com.halalify.kotlin.model.Detection
import com.halalify.kotlin.settings.BlurStyle
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class FrameBlurResult(
    val blurredCount: Int,
    val overlayRegions: List<OverlayRegion>,
)

internal data class OverlayRegion(
    /** Small, fully obscured bitmap owned by the overlay after [ProtectionOverlay.update]. */
    val bitmap: Bitmap,
    val bounds: Rect,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

internal object FrameBlurRenderer {
    private val bitmapPool = ArrayDeque<Bitmap>()

    fun renderSelectedDetections(
        bitmap: Bitmap,
        detections: List<Detection>,
        style: BlurStyle,
        intensity: Float,
    ): FrameBlurResult {
        val selected = detections.filter(Detection::shouldBlur)
        if (selected.isEmpty()) return FrameBlurResult(0, emptyList())

        // Keep the old Halalify look: a hard-edged mosaic made from a small
        // per-region crop, then scaled by the transparent device overlay.
        val protectedPaint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
        }
        val overlayRegions = mutableListOf<OverlayRegion>()
        try {
            selected.forEach { detection ->
                val rect = detection.toTightRect(bitmap.width, bitmap.height) ?: return@forEach
                overlayRegions += createOverlayRegion(
                    source = bitmap,
                    rect = rect,
                    protectedPaint = protectedPaint,
                    intensity = intensity,
                )
            }

            // Keep the in-app preview consistent with the device overlay.
            Canvas(bitmap).drawRegions(overlayRegions, protectedPaint)
        } catch (error: Throwable) {
            overlayRegions.forEach { releaseOverlayBitmap(it.bitmap) }
            throw error
        }
        return FrameBlurResult(overlayRegions.size, overlayRegions)
    }

    /** Returns a pooled bitmap to the renderer without recycling it prematurely. */
    internal fun releaseOverlayBitmap(bitmap: Bitmap) {
        synchronized(bitmapPool) {
            if (bitmap.isRecycled) return
            if (!bitmap.isMutable) {
                bitmap.recycleSafely()
                return
            }
            if (bitmapPool.size < MAX_POOLED_BITMAPS) {
                runCatching { bitmap.eraseColor(Color.BLACK) }
                bitmapPool.addLast(bitmap)
            } else {
                bitmap.recycleSafely()
            }
        }
    }

    internal fun clearBitmapPool() {
        synchronized(bitmapPool) {
            while (bitmapPool.isNotEmpty()) bitmapPool.removeFirst().recycleSafely()
        }
    }

    private fun Canvas.drawRegions(regions: List<OverlayRegion>, paint: Paint) {
        regions.forEach { region ->
            drawBitmap(region.bitmap, null, region.bounds, paint)
        }
    }

    private fun createOverlayRegion(
        source: Bitmap,
        rect: Rect,
        protectedPaint: Paint,
        intensity: Float,
    ): OverlayRegion {
        val safeIntensity = if (intensity.isFinite()) intensity.coerceIn(0f, 1f) else 1f
        val maxGridDim = (MAX_GRID_MAJOR -
                safeIntensity * (MAX_GRID_MAJOR - MIN_GRID_MAJOR))
            .roundToInt()
            .coerceIn(MIN_GRID_MAJOR.roundToInt(), MAX_GRID_MAJOR.roundToInt())

        val (tinyWidth, tinyHeight) = if (rect.width() >= rect.height()) {
            val width = maxGridDim
            val height = (maxGridDim * rect.height().toFloat() / rect.width().coerceAtLeast(1))
                .roundToInt().coerceIn(2, maxGridDim)
            width to height
        } else {
            val height = maxGridDim
            val width = (maxGridDim * rect.width().toFloat() / rect.height().coerceAtLeast(1))
                .roundToInt().coerceIn(2, maxGridDim)
            width to height
        }

        val patch = acquireBitmap(tinyWidth.coerceAtLeast(1), tinyHeight.coerceAtLeast(1))
        try {
            patch.eraseColor(Color.BLACK)
            Canvas(patch).drawBitmap(
                source,
                rect,
                Rect(0, 0, patch.width, patch.height),
                protectedPaint,
            )
            return OverlayRegion(
                bitmap = patch,
                bounds = Rect(rect),
                sourceWidth = source.width,
                sourceHeight = source.height,
            )
        } catch (error: Throwable) {
            releaseOverlayBitmap(patch)
            throw error
        }
    }

    private fun acquireBitmap(width: Int, height: Int): Bitmap {
        synchronized(bitmapPool) {
            val iterator = bitmapPool.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (!candidate.isRecycled && candidate.width == width && candidate.height == height) {
                    iterator.remove()
                    return candidate
                }
            }
        }
        return createBitmap(width, height).also { it.setHasAlpha(false) }
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) runCatching { recycle() }
    }

    private fun Detection.toTightRect(width: Int, height: Int): Rect? {
        if (width <= 0 || height <= 0) return null
        val boxWidth = x2 - x1
        val boxHeight = y2 - y1
        if (!boxWidth.isFinite() || !boxHeight.isFinite() || boxWidth <= 0f || boxHeight <= 0f) {
            return null
        }
        val padX = boxWidth * 0.02f
        val padY = boxHeight * 0.02f
        val left = ceil((x1 - padX) * width).toInt().coerceIn(0, width - 1)
        val top = ceil((y1 - padY) * height).toInt().coerceIn(0, height - 1)
        val right = floor((x2 + padX) * width).toInt().coerceIn(left + 1, width)
        val bottom = floor((y2 + padY) * height).toInt().coerceIn(top + 1, height)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    }

    private const val MAX_POOLED_BITMAPS = 10
    private const val MIN_GRID_MAJOR = 5f
    private const val MAX_GRID_MAJOR = 18f
}
