package com.halalify.kotlin.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.halalify.kotlin.model.Detection
import kotlin.math.ceil
import kotlin.math.floor

internal data class FrameBlurResult(
    val blurredCount: Int,
    val overlayBitmap: Bitmap?,
)

internal object FrameBlurRenderer {
    fun renderSelectedDetections(bitmap: Bitmap, detections: List<Detection>): FrameBlurResult {
        val selected = detections.filter(Detection::shouldBlur)
        if (selected.isEmpty()) return FrameBlurResult(0, null)

        val source = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val overlay = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val previewCanvas = Canvas(bitmap)
        val overlayCanvas = Canvas(overlay)
        // Nearest-neighbour scaling keeps every reduced source pixel as a hard,
        // visible censor block. Bitmap filtering would blend neighbouring blocks
        // and turn the effect back into a soft blur.
        val pixelPaint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
        }
        var blurredCount = 0
        try {
            selected.forEach { detection ->
                val rect = detection.toExpandedRect(bitmap.width, bitmap.height) ?: return@forEach
                val patch = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
                val tinyWidth = (rect.width() / PIXEL_BLOCK_SIZE).coerceAtLeast(1)
                val tinyHeight = (rect.height() / PIXEL_BLOCK_SIZE).coerceAtLeast(1)
                val tiny = Bitmap.createScaledBitmap(patch, tinyWidth, tinyHeight, false)
                previewCanvas.drawBitmap(tiny, null, rect, pixelPaint)
                overlayCanvas.drawBitmap(tiny, null, rect, pixelPaint)
                blurredCount += 1
                if (tiny !== patch) tiny.recycle()
                patch.recycle()
            }
        } finally {
            source.recycle()
        }
        if (blurredCount == 0) {
            overlay.recycle()
            return FrameBlurResult(0, null)
        }
        return FrameBlurResult(blurredCount, overlay)
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

    private const val PIXEL_BLOCK_SIZE = 24
    private const val BOX_PADDING_RATIO = 0.10F
}
