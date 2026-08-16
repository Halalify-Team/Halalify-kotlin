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
            // Higher intensity uses larger nearest-neighbour blocks.
            val safeIntensity = if (intensity.isFinite()) {
                intensity.coerceIn(0f, 1f)
            } else {
                1f
            }
            val sampleSize = (
                safeIntensity * CENSOR_SAMPLE_SIZE.toFloat()
            ).toInt().coerceAtLeast(1)
            val tinyWidth = ceil(rect.width().toFloat() / sampleSize).toInt().coerceAtLeast(1)
            val tinyHeight = ceil(rect.height().toFloat() / sampleSize).toInt().coerceAtLeast(1)
            tiny = patch.scale(tinyWidth, tinyHeight)
            protectedPatch = createBitmap(rect.width(), rect.height())
            val patchCanvas = Canvas(protectedPatch)
            val patchBounds = Rect(0, 0, protectedPatch.width, protectedPatch.height)
            patchCanvas.drawBitmap(tiny, null, patchBounds, protectedPaint)
            protectedPatch.setHasAlpha(false)
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
        val rawLeft = x1 * width
        val rawTop = y1 * height
        val rawRight = x2 * width
        val rawBottom = y2 * height
        // Keep the complete detector box for gender detections so the blur
        // covers the subject's full body, including hair, arms, and clothing.
        // NSFW boxes already retain their complete protected area as well.
        val left = ceil(rawLeft).toInt().coerceIn(0, width)
        val top = ceil(rawTop).toInt().coerceIn(0, height)
        val right = floor(rawRight).toInt().coerceIn(0, width)
        val bottom = floor(rawBottom).toInt().coerceIn(0, height)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    }

    // Use large nearest-neighbour blocks so facial/body details are difficult
    // to recover, even at the lowest selectable intensity.
    private const val CENSOR_SAMPLE_SIZE = 32
}
