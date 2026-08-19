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
     * High-quality soft privacy blur:
     * 1) Downsamples the protected patch according to blur intensity,
     * 2) Applies a fast in-place box blur for smooth Gaussian-like color diffusion,
     * 3) Scales back with bilinear filtering to fit the target patch,
     * 4) Preserves original scene colors and luminosity without darkening or turning black.
     */
    private fun applySoftProtection(
        bitmap: Bitmap,
        intensity: Float,
    ) {
        if (bitmap.width <= 1 || bitmap.height <= 1) return

        val amount = intensity.coerceIn(0F, 1F)

        // Downsample factor between 0.15 (lightest) and 0.04 (heaviest)
        val scale = lerp(0.15F, 0.04F, amount)

        val smallWidth = (bitmap.width * scale)
            .roundToInt()
            .coerceAtLeast(3)

        val smallHeight = (bitmap.height * scale)
            .roundToInt()
            .coerceAtLeast(3)

        val tiny = Bitmap.createScaledBitmap(
            bitmap,
            smallWidth,
            smallHeight,
            true,
        )

        // Fast in-place box blur on the tiny bitmap for smooth gaussian-like diffusion
        boxBlur(tiny, radius = 2)

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
        } finally {
            if (!tiny.isRecycled) tiny.recycle()
            if (!blurred.isRecycled) blurred.recycle()
        }
    }

    private fun boxBlur(bitmap: Bitmap, radius: Int) {
        if (radius < 1 || bitmap.width <= 1 || bitmap.height <= 1) return
        val width = bitmap.width
        val height = bitmap.height
        val size = width * height
        val pixels = IntArray(size)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val temp = IntArray(size)

        // Horizontal pass
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                val minK = (x - radius).coerceAtLeast(0)
                val maxK = (x + radius).coerceAtMost(width - 1)
                for (k in minK..maxK) {
                    val p = pixels[rowOffset + k]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                    count++
                }
                temp[rowOffset + x] = (-0x1000000) or
                        ((r / count) shl 16) or
                        ((g / count) shl 8) or
                        (b / count)
            }
        }

        // Vertical pass
        for (x in 0 until width) {
            for (y in 0 until height) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                val minK = (y - radius).coerceAtLeast(0)
                val maxK = (y + radius).coerceAtMost(height - 1)
                for (k in minK..maxK) {
                    val p = temp[k * width + x]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                    count++
                }
                pixels[y * width + x] = (-0x1000000) or
                        ((r / count) shl 16) or
                        ((g / count) shl 8) or
                        (b / count)
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /**
     * Clean, crisp pixelated privacy mode without darkening.
     */
    private fun applyPixelatedProtection(
        bitmap: Bitmap,
        intensity: Float,
    ) {
        if (bitmap.width <= 1 || bitmap.height <= 1) return

        val amount = intensity.coerceIn(0F, 1F)
        val blockSize = lerp(8F, 32F, amount)
            .roundToInt()
            .coerceAtLeast(4)

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
        } finally {
            if (!tiny.isRecycled) tiny.recycle()
            if (!pixelated.isRecycled) pixelated.recycle()
        }
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