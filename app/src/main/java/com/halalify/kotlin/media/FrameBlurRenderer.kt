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
    val bitmap: Bitmap,
    val bounds: Rect,
    val sourceWidth: Int,
    val sourceHeight: Int,
    /**
     * true  = draw the generated bitmap (Soft blur / Pixelated)
     * false = draw a fully solid black region (Solid)
     */
    val isFiltered: Boolean = true,
)

internal object FrameBlurRenderer {
    private val bitmapPool = ArrayDeque<Bitmap>()

    private val solidBlackPaint = Paint().apply {
        color = Color.BLACK
        alpha = 255
        style = Paint.Style.FILL
        isAntiAlias = false
        isDither = false
    }

    fun renderSelectedDetections(
        bitmap: Bitmap,
        detections: List<Detection>,
        style: BlurStyle,
        intensity: Float,
        includePreview: Boolean = false,
    ): FrameBlurResult {
        val selected = detections.filter(Detection::shouldBlur)
        if (selected.isEmpty()) {
            return FrameBlurResult(0, emptyList())
        }

        val regions = ArrayList<OverlayRegion>(selected.size)

        try {
            selected.forEach { detection ->
                val rect = detection.toProtectedRect(bitmap.width, bitmap.height)
                    ?: return@forEach

                regions += createOverlayRegion(
                    source = bitmap,
                    rect = rect,
                    style = style,
                    intensity = intensity,
                )
            }

            if (includePreview) {
                // Keep the optional in-app preview consistent with the real overlay.
                Canvas(bitmap).drawRegions(regions)
            }
        } catch (error: Throwable) {
            regions.forEach { releaseOverlayBitmap(it.bitmap) }
            throw error
        }

        return FrameBlurResult(
            blurredCount = regions.size,
            overlayRegions = regions,
        )
    }

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
            while (bitmapPool.isNotEmpty()) {
                bitmapPool.removeFirst().recycleSafely()
            }
        }
    }

    private fun Canvas.drawRegions(regions: List<OverlayRegion>) {
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = 255
            isDither = false
        }

        regions.forEach { region ->
            if (region.isFiltered) {
                drawBitmap(region.bitmap, null, region.bounds, bitmapPaint)
            } else {
                drawRect(region.bounds, solidBlackPaint)
            }
        }
    }

    private fun createOverlayRegion(
        source: Bitmap,
        rect: Rect,
        style: BlurStyle,
        intensity: Float,
    ): OverlayRegion {
        val width = rect.width().coerceAtLeast(1)
        val height = rect.height().coerceAtLeast(1)
        val patch = acquireBitmap(width, height)

        try {
            patch.eraseColor(Color.BLACK)

            Canvas(patch).drawBitmap(
                source,
                Rect(rect),
                Rect(0, 0, width, height),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 255 },
            )

            // The source screen capture is opaque. Mark the patch opaque as well so
            // the OPAQUE region window never contains translucent pixels.
            patch.setHasAlpha(false)

            val amount = intensity.coerceIn(0F, 1F)

            return when (style) {
                BlurStyle.SOLID -> {
                    patch.eraseColor(Color.BLACK)

                    OverlayRegion(
                        bitmap = patch,
                        bounds = Rect(rect),
                        sourceWidth = source.width,
                        sourceHeight = source.height,
                        isFiltered = false,
                    )
                }

                BlurStyle.PIXELATED -> {
                    applyPixelatedProtection(patch, amount)
                    forceOpaqueAlpha(patch)

                    OverlayRegion(
                        bitmap = patch,
                        bounds = Rect(rect),
                        sourceWidth = source.width,
                        sourceHeight = source.height,
                        isFiltered = true,
                    )
                }

                BlurStyle.SOFT_BLUR -> {
                    applySoftProtection(patch, amount)
                    forceOpaqueAlpha(patch)

                    OverlayRegion(
                        bitmap = patch,
                        bounds = Rect(rect),
                        sourceWidth = source.width,
                        sourceHeight = source.height,
                        isFiltered = true,
                    )
                }
            }
        } catch (error: Throwable) {
            releaseOverlayBitmap(patch)
            throw error
        }
    }

    /**
     * Real soft privacy blur:
     * 1) aggressively downsamples the protected patch,
     * 2) scales it back with bilinear filtering,
     * 3) adds a dark veil so Level 5 remains difficult to recognize.
     *
     * The final bitmap is still fully opaque.
     */
    private fun applySoftProtection(
        bitmap: Bitmap,
        intensity: Float,
    ) {
        if (bitmap.width <= 1 || bitmap.height <= 1) return

        val amount = intensity.coerceIn(0F, 1F)

        // Level 1: ~16% of original size.
        // Level 5: ~3% of original size.
        val scale = lerp(0.16F, 0.03F, amount)

        val smallWidth = (bitmap.width * scale)
            .roundToInt()
            .coerceAtLeast(2)

        val smallHeight = (bitmap.height * scale)
            .roundToInt()
            .coerceAtLeast(2)

        val tiny = Bitmap.createScaledBitmap(
            bitmap,
            smallWidth,
            smallHeight,
            true,
        )

        val blurred = Bitmap.createScaledBitmap(
            tiny,
            bitmap.width,
            bitmap.height,
            true,
        )

        try {
            Canvas(bitmap).drawBitmap(
                blurred,
                0F,
                0F,
                Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 255 },
            )

            // Darken without turning Soft blur into Solid.
            val veilAlpha = lerp(35F, 115F, amount)
                .roundToInt()
                .coerceIn(0, 180)

            drawBlackVeil(bitmap, veilAlpha)
        } finally {
            if (!tiny.isRecycled) tiny.recycle()
            if (!blurred.isRecycled) blurred.recycle()
        }
    }

    /**
     * Pixelated privacy mode. It uses nearest-neighbour scaling so the result
     * remains clearly different from Soft blur.
     */
    private fun applyPixelatedProtection(
        bitmap: Bitmap,
        intensity: Float,
    ) {
        if (bitmap.width <= 1 || bitmap.height <= 1) return

        val amount = intensity.coerceIn(0F, 1F)
        val blockSize = lerp(10F, 38F, amount)
            .roundToInt()
            .coerceAtLeast(6)

        val smallWidth = (bitmap.width / blockSize).coerceAtLeast(2)
        val smallHeight = (bitmap.height / blockSize).coerceAtLeast(2)

        val tiny = Bitmap.createScaledBitmap(
            bitmap,
            smallWidth,
            smallHeight,
            false,
        )

        val pixelated = Bitmap.createScaledBitmap(
            tiny,
            bitmap.width,
            bitmap.height,
            false,
        )

        try {
            Canvas(bitmap).drawBitmap(
                pixelated,
                0F,
                0F,
                Paint().apply {
                    alpha = 255
                    isAntiAlias = false
                    isFilterBitmap = false
                    isDither = false
                },
            )

            val veilAlpha = lerp(25F, 90F, amount)
                .roundToInt()
                .coerceIn(0, 150)

            drawBlackVeil(bitmap, veilAlpha)
        } finally {
            if (!tiny.isRecycled) tiny.recycle()
            if (!pixelated.isRecycled) pixelated.recycle()
        }
    }

    private fun drawBlackVeil(
        bitmap: Bitmap,
        alpha: Int,
    ) {
        val paint = Paint().apply {
            color = Color.argb(alpha.coerceIn(0, 255), 0, 0, 0)
            style = Paint.Style.FILL
        }

        Canvas(bitmap).drawRect(
            0F,
            0F,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            paint,
        )
    }

    /**
     * Ensures every pixel has alpha=255. This matters because the region window
     * is PixelFormat.OPAQUE and must never contain partially transparent pixels.
     */
    private fun forceOpaqueAlpha(bitmap: Bitmap) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(
            pixels,
            0,
            bitmap.width,
            0,
            0,
            bitmap.width,
            bitmap.height,
        )

        for (index in pixels.indices) {
            pixels[index] = pixels[index] or -0x1000000
        }

        bitmap.setPixels(
            pixels,
            0,
            bitmap.width,
            0,
            0,
            bitmap.width,
            bitmap.height,
        )
        bitmap.setHasAlpha(false)
    }

    private fun acquireBitmap(
        width: Int,
        height: Int,
    ): Bitmap {
        synchronized(bitmapPool) {
            val iterator = bitmapPool.iterator()

            while (iterator.hasNext()) {
                val candidate = iterator.next()

                if (
                    !candidate.isRecycled &&
                    candidate.width == width &&
                    candidate.height == height
                ) {
                    iterator.remove()
                    candidate.setHasAlpha(false)
                    return candidate
                }
            }
        }

        return createBitmap(width, height).also {
            it.setHasAlpha(false)
        }
    }

    private fun Detection.toProtectedRect(
        width: Int,
        height: Int,
    ): Rect? {
        if (width <= 0 || height <= 0) return null

        val boxWidth = x2 - x1
        val boxHeight = y2 - y1

        if (
            !boxWidth.isFinite() ||
            !boxHeight.isFinite() ||
            boxWidth <= 0F ||
            boxHeight <= 0F
        ) {
            return null
        }

        // Small safety margin. This is intentionally smaller than the old 25%.
        val padX = boxWidth * 0.08F
        val padY = boxHeight * 0.08F

        val left = ceil((x1 - padX) * width)
            .toInt()
            .coerceIn(0, width - 1)

        val top = ceil((y1 - padY) * height)
            .toInt()
            .coerceIn(0, height - 1)

        val right = floor((x2 + padX) * width)
            .toInt()
            .coerceIn(left + 1, width)

        val bottom = floor((y2 + padY) * height)
            .toInt()
            .coerceIn(top + 1, height)

        return if (right > left && bottom > top) {
            Rect(left, top, right, bottom)
        } else {
            null
        }
    }

    private fun lerp(
        start: Float,
        end: Float,
        fraction: Float,
    ): Float {
        val t = fraction.coerceIn(0F, 1F)
        return start + (end - start) * t
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) {
            runCatching { recycle() }
        }
    }

    private const val MAX_POOLED_BITMAPS = 10
}