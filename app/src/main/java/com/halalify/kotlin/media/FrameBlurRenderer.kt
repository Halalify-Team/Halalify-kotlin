package com.halalify.kotlin.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.halalify.kotlin.model.Detection
import com.halalify.kotlin.settings.BlurStyle
import kotlin.math.ceil
import kotlin.math.floor

internal data class FrameBlurResult(
    val blurredCount: Int,
    val overlayRegions: List<OverlayRegion>,
)

internal data class OverlayRegion(
    /** Full-colour source used by Android's GPU blur on API 31+. */
    val bitmap: Bitmap,
    /** Already-obscured source used if RenderEffect is unavailable. */
    val fallbackBitmap: Bitmap,
    val bounds: Rect,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

internal object FrameBlurRenderer {
    fun renderSelectedDetections(
        bitmap: Bitmap,
        detections: List<Detection>,
        style: BlurStyle,
    ): FrameBlurResult {
        val selected = detections.filter(Detection::shouldBlur)
        if (selected.isEmpty()) return FrameBlurResult(0, emptyList())

        val previewCanvas = Canvas(bitmap)
        val protectedPaint = Paint().apply {
            // HaramBlur's censor effect is an opaque mosaic. Filtering while
            // enlarging the reduced patch would blend the cells and make the
            // protected subject visible through a soft blur.
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
        }
        var blurredCount = 0
        val overlayRegions = mutableListOf<OverlayRegion>()
        try {
            selected.forEach { detection ->
                val rect = detection.toExpandedRect(bitmap.width, bitmap.height) ?: return@forEach
                overlayRegions += createOverlayRegion(
                    source = bitmap,
                    rect = rect,
                    style = style,
                    protectedPaint = protectedPaint,
                )
                blurredCount += 1
            }
            overlayRegions.forEach { region ->
                previewCanvas.drawBitmap(
                    region.fallbackBitmap,
                    region.bounds.left.toFloat(),
                    region.bounds.top.toFloat(),
                    protectedPaint,
                )
            }
        } catch (error: Throwable) {
            overlayRegions.forEach { region ->
                region.bitmap.recycle()
                region.fallbackBitmap.recycle()
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
    ): OverlayRegion {
        val patch = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height()).apply {
            setHasAlpha(false)
        }
        var tiny: Bitmap? = null
        var protectedPatch: Bitmap? = null
        try {
            val sampleSize = if (style == BlurStyle.PIXELATED) {
                MOSAIC_BLOCK_SIZE
            } else {
                LEGACY_BLUR_SAMPLE_SIZE
            }
            val tinyWidth = ceil(rect.width().toFloat() / sampleSize).toInt().coerceAtLeast(1)
            val tinyHeight = ceil(rect.height().toFloat() / sampleSize).toInt().coerceAtLeast(1)
            tiny = Bitmap.createScaledBitmap(patch, tinyWidth, tinyHeight, true)
            protectedPatch = Bitmap.createBitmap(
                rect.width(),
                rect.height(),
                Bitmap.Config.ARGB_8888,
            )
            val patchCanvas = Canvas(protectedPatch)
            val patchBounds = Rect(0, 0, protectedPatch.width, protectedPatch.height)
            patchCanvas.drawBitmap(tiny, null, patchBounds, protectedPaint)
            protectedPatch.setHasAlpha(false)
            return OverlayRegion(
                bitmap = patch,
                fallbackBitmap = protectedPatch,
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
        }
    }

    private fun Detection.toExpandedRect(width: Int, height: Int): Rect? {
        val rawLeft = x1 * width
        val rawTop = y1 * height
        val rawRight = x2 * width
        val rawBottom = y2 * height
        val paddingX = (rawRight - rawLeft) * BOX_PADDING_RATIO
        val paddingY = (rawBottom - rawTop) * BOX_PADDING_RATIO
        val left = floor(rawLeft - paddingX).toInt().coerceIn(0, width)
        val top = floor(rawTop - paddingY).toInt().coerceIn(0, height)
        val right = ceil(rawRight + paddingX).toInt().coerceIn(0, width)
        val bottom = ceil(rawBottom + paddingY).toInt().coerceIn(0, height)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    }

    private const val MOSAIC_BLOCK_SIZE = 32
    // Use the same large cells for the default blur so facial details cannot
    // remain recognizable between neighbouring mosaic samples.
    private const val LEGACY_BLUR_SAMPLE_SIZE = 32
    // The detector often returns a tight body box. HaramBlur visually covers
    // the whole subject, so extend only the rendered censor region around it.
    private const val BOX_PADDING_RATIO = 0.35F
}
