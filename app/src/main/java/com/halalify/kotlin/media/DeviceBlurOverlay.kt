package com.halalify.kotlin.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.roundToInt

internal interface ProtectionOverlay : Closeable {
    /** Takes ownership of every bitmap in [regions]. */
    fun update(regions: List<OverlayRegion>)
}

@RequiresApi(Build.VERSION_CODES.O)
internal class DeviceBlurOverlay(
    context: Context,
    private val onError: (String) -> Unit,
) : ProtectionOverlay {
    private class ActiveRegion(bitmap: Bitmap, bounds: Rect) {
        var bitmap: Bitmap = bitmap
        val current = Rect(bounds)
        val target = Rect(bounds)
    }

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateLock = Any()
    private var pending: List<OverlayRegion>? = null
    private var updatePosted = false
    private var overlayView: FullDeviceBlurOverlayView? = null
    private var attached = false

    @Volatile
    private var closed = false

    override fun update(regions: List<OverlayRegion>) {
        if (closed) {
            regions.releaseBitmaps()
            return
        }
        val oldPending: List<OverlayRegion>?
        val shouldPost: Boolean
        synchronized(updateLock) {
            if (closed) {
                regions.releaseBitmaps()
                return
            }
            oldPending = pending
            pending = regions
            shouldPost = !updatePosted
            updatePosted = true
        }
        oldPending?.releaseBitmaps()
        if (shouldPost) mainHandler.post { applyPending() }
    }

    fun clear() = update(emptyList())

    override fun close() {
        closed = true
        val oldPending = synchronized(updateLock) {
            pending.also {
                pending = null
                updatePosted = false
            }
        }
        oldPending?.releaseBitmaps()
        mainHandler.post { clearOnMainThread() }
    }

    private fun applyPending() {
        val regions = synchronized(updateLock) {
            pending.also {
                pending = null
                updatePosted = false
            }
        } ?: return

        if (closed) {
            regions.releaseBitmaps()
            return
        }
        try {
            if (!Settings.canDrawOverlays(appContext)) {
                regions.releaseBitmaps()
                clearOnMainThread()
                onError("Display-over-other-apps permission was revoked.")
            } else {
                renderRegions(regions)
            }
        } catch (error: Throwable) {
            regions.releaseBitmaps()
            clearOnMainThread()
            onError(error.message ?: error.javaClass.simpleName)
        }

        val shouldPost = synchronized(updateLock) {
            if (pending != null && !updatePosted) {
                updatePosted = true
                true
            } else false
        }
        if (shouldPost) mainHandler.post { applyPending() }
    }

    private fun renderRegions(regions: List<OverlayRegion>) {
        if (regions.isEmpty()) {
            overlayView?.setRegions(emptyList())
            return
        }
        val displayBounds = currentDisplayBounds()
        val next = ArrayList<ActiveRegion>(regions.size)
        try {
            regions.forEach { region ->
                if (region.bitmap.isRecycled || region.sourceWidth <= 0 || region.sourceHeight <= 0) {
                    FrameBlurRenderer.releaseOverlayBitmap(region.bitmap)
                    return@forEach
                }
                val bounds = region.bounds.scaleToDisplay(
                    region.sourceWidth,
                    region.sourceHeight,
                    displayBounds,
                )
                if (bounds.width() <= 0 || bounds.height() <= 0) {
                    FrameBlurRenderer.releaseOverlayBitmap(region.bitmap)
                    return@forEach
                }
                region.bitmap.prepareToDraw()
                next += ActiveRegion(region.bitmap, bounds)
            }
            ensureOverlayView().setRegions(next)
        } catch (error: Throwable) {
            next.forEach { FrameBlurRenderer.releaseOverlayBitmap(it.bitmap) }
            regions.forEach { region ->
                if (next.none { it.bitmap === region.bitmap }) {
                    FrameBlurRenderer.releaseOverlayBitmap(region.bitmap)
                }
            }
            throw error
        }
    }

    private fun ensureOverlayView(): FullDeviceBlurOverlayView {
        val existing = overlayView
        if (existing != null && attached) return existing
        val view = existing ?: FullDeviceBlurOverlayView(appContext).also { overlayView = it }
        if (!attached) {
            windowManager.addView(view, createFullScreenLayoutParams())
            attached = true
        }
        return view
    }

    private fun createFullScreenLayoutParams(): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1F
            title = "Halalify Protected Overlay"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    private fun currentDisplayBounds(): Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Rect(windowManager.currentWindowMetrics.bounds)
    } else {
        @Suppress("DEPRECATION")
        Rect(0, 0, appContext.resources.displayMetrics.widthPixels, appContext.resources.displayMetrics.heightPixels)
    }

    private fun Rect.scaleToDisplay(sourceWidth: Int, sourceHeight: Int, display: Rect): Rect {
        val scaleX = display.width().toFloat() / sourceWidth.coerceAtLeast(1)
        val scaleY = display.height().toFloat() / sourceHeight.coerceAtLeast(1)
        return Rect(
            display.left + (left.coerceIn(0, sourceWidth) * scaleX).roundToInt(),
            display.top + (top.coerceIn(0, sourceHeight) * scaleY).roundToInt(),
            display.left + (right.coerceIn(0, sourceWidth) * scaleX).roundToInt(),
            display.top + (bottom.coerceIn(0, sourceHeight) * scaleY).roundToInt(),
        )
    }

    private fun clearOnMainThread() {
        overlayView?.let { view ->
            view.setRegions(emptyList())
            if (attached) runCatching { windowManager.removeViewImmediate(view) }
        }
        attached = false
        overlayView = null
        FrameBlurRenderer.clearBitmapPool()
    }

    private fun List<OverlayRegion>.releaseBitmaps() {
        forEach { FrameBlurRenderer.releaseOverlayBitmap(it.bitmap) }
    }

    private class FullDeviceBlurOverlayView(context: Context) : View(context) {
        private val opaquePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            alpha = 255
        }
        private val bitmapPaint = Paint().apply {
            isAntiAlias = false
            // The reference scales a small per-region crop with filtering;
            // the mosaic itself was already created at low resolution.
            isFilterBitmap = true
            isDither = false
            alpha = 255
        }
        private var regions: List<ActiveRegion> = emptyList()

        fun setRegions(incoming: List<ActiveRegion>) {
            val old = regions
            val used = BooleanArray(old.size)
            val next = ArrayList<ActiveRegion>(incoming.size)
            incoming.forEach { candidate ->
                val index = old.indices.firstOrNull { i ->
                    !used[i] && isSameRegion(old[i].target, candidate.target)
                }
                if (index == null) {
                    next += candidate
                    return@forEach
                }
                used[index] = true
                val existing = old[index]
                existing.target.set(candidate.target)
                val oldBitmap = existing.bitmap
                existing.bitmap = candidate.bitmap
                if (oldBitmap !== candidate.bitmap) FrameBlurRenderer.releaseOverlayBitmap(oldBitmap)
                next += existing
            }
            old.forEachIndexed { i, region ->
                if (!used[i]) FrameBlurRenderer.releaseOverlayBitmap(region.bitmap)
            }
            regions = next
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            var moving = false
            regions.forEach { region ->
                if (region.bitmap.isRecycled) return@forEach
                moving = moveTowards(region) || moving
                if (region.current.width() <= 0 || region.current.height() <= 0) return@forEach
                canvas.drawRect(region.current, opaquePaint)
                canvas.drawBitmap(region.bitmap, null, region.current, bitmapPaint)
            }
            if (moving) postInvalidateOnAnimation()
        }

        private fun moveTowards(region: ActiveRegion): Boolean {
            var changed = false
            Edge.values().forEach { edge ->
                val from = edge.get(region.current)
                val to = edge.get(region.target)
                if (from == to) return@forEach
                val distance = to - from
                val step = if (distance > 0) {
                    (distance * ANIMATION_ALPHA).roundToInt().coerceIn(1, distance)
                } else {
                    (distance * ANIMATION_ALPHA).roundToInt().coerceIn(distance, -1)
                }
                edge.set(region.current, from + step)
                changed = true
            }
            return changed
        }

        private fun isSameRegion(first: Rect, second: Rect): Boolean {
            val left = maxOf(first.left, second.left)
            val top = maxOf(first.top, second.top)
            val right = minOf(first.right, second.right)
            val bottom = minOf(first.bottom, second.bottom)
            val overlap = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
            val union = first.width() * first.height() + second.width() * second.height() - overlap
            if (union > 0 && overlap.toFloat() / union >= IOU_THRESHOLD) return true
            val distanceX = abs(first.centerX() - second.centerX()).toFloat()
            val distanceY = abs(first.centerY() - second.centerY()).toFloat()
            val maxDistance = maxOf(first.width(), first.height(), second.width(), second.height()) *
                    CENTER_DISTANCE_FACTOR
            return distanceX <= maxDistance && distanceY <= maxDistance
        }

        private enum class Edge {
            LEFT, TOP, RIGHT, BOTTOM;

            fun get(rect: Rect): Int = when (this) {
                LEFT -> rect.left
                TOP -> rect.top
                RIGHT -> rect.right
                BOTTOM -> rect.bottom
            }

            fun set(rect: Rect, value: Int) {
                when (this) {
                    LEFT -> rect.left = value
                    TOP -> rect.top = value
                    RIGHT -> rect.right = value
                    BOTTOM -> rect.bottom = value
                }
            }
        }

        private companion object {
            const val ANIMATION_ALPHA = 0.55F
            const val IOU_THRESHOLD = 0.08F
            const val CENTER_DISTANCE_FACTOR = 0.9F
        }
    }
}
