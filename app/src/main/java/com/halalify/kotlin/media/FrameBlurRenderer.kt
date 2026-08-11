package com.halalify.kotlin.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.halalify.kotlin.model.Detection
import kotlin.math.ceil
import kotlin.math.floor

internal data class FrameBlurResult(
    val blurredCount: Int,
    val overlayRegions: List<OverlayRegion>,
)

internal data class OverlayRegion(
    val bitmap: Bitmap,
    val bounds: Rect,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

internal object FrameBlurRenderer {
    fun renderSelectedDetections(bitmap: Bitmap, detections: List<Detection>): FrameBlurResult {
        val selected = detections.filter(Detection::shouldBlur)
        if (selected.isEmpty()) return FrameBlurResult(0, emptyList())

        val source = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val previewCanvas = Canvas(bitmap)
        // Nearest-neighbour scaling keeps every reduced source pixel as a hard,
        // visible censor block. Bitmap filtering would blend neighbouring blocks
        // and turn the effect back into a soft blur.
        val pixelPaint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
        }
        val privacyShadePaint = Paint().apply {
            color = Color.BLACK
            alpha = PRIVACY_SHADE_ALPHA
        }
        var blurredCount = 0
        val overlayRegions = mutableListOf<OverlayRegion>()
        try {
            selected.forEach { detection ->
                val rect = detection.toExpandedRect(bitmap.width, bitmap.height) ?: return@forEach
                val patch = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
                val tinyWidth = (rect.width() / PIXEL_BLOCK_SIZE).coerceAtLeast(1)
                val tinyHeight = (rect.height() / PIXEL_BLOCK_SIZE).coerceAtLeast(1)
                val tiny = Bitmap.createScaledBitmap(patch, tinyWidth, tinyHeight, false)
                val protectedPatch = Bitmap.createBitmap(
                    rect.width(),
                    rect.height(),
                    Bitmap.Config.ARGB_8888,
                )
                val patchCanvas = Canvas(protectedPatch)
                val patchBounds = Rect(0, 0, protectedPatch.width, protectedPatch.height)
                patchCanvas.drawBitmap(tiny, null, patchBounds, pixelPaint)
                patchCanvas.drawRect(patchBounds, privacyShadePaint)
                previewCanvas.drawBitmap(protectedPatch, rect.left.toFloat(), rect.top.toFloat(), pixelPaint)
                overlayRegions += OverlayRegion(
                    bitmap = protectedPatch,
                    bounds = Rect(rect),
                    sourceWidth = bitmap.width,
                    sourceHeight = bitmap.height,
                )
                blurredCount += 1
                if (tiny !== patch) tiny.recycle()
                patch.recycle()
            }
        } catch (error: Throwable) {
            overlayRegions.forEach { region -> region.bitmap.recycle() }
            throw error
        } finally {
            source.recycle()
        }
        return FrameBlurResult(blurredCount, overlayRegions)
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

    private const val PIXEL_BLOCK_SIZE = 40
    private const val BOX_PADDING_RATIO = 0.15F
    private const val PRIVACY_SHADE_ALPHA = 112
}
