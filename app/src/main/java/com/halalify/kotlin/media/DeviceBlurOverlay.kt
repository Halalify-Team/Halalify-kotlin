package com.halalify.kotlin.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import java.io.Closeable
import kotlin.math.roundToInt

internal class DeviceBlurOverlay(
    context: Context,
    private val onError: (String) -> Unit,
) : Closeable {
    private data class OverlayWindow(
        val view: BlurOverlayView,
        val params: WindowManager.LayoutParams,
    )

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windows = mutableListOf<OverlayWindow>()
    @Volatile private var closed = false

    /** Takes ownership of every bitmap in [regions]. */
    fun update(regions: List<OverlayRegion>) {
        if (closed) {
            regions.recycleBitmaps()
            return
        }
        mainHandler.post {
            if (closed) {
                regions.recycleBitmaps()
                return@post
            }
            try {
                if (!Settings.canDrawOverlays(appContext)) {
                    regions.recycleBitmaps()
                    clearOnMainThread()
                    onError("Display-over-other-apps permission was revoked.")
                    return@post
                }
                replaceRegions(regions)
            } catch (error: Throwable) {
                clearOnMainThread()
                regions.recycleBitmaps()
                onError(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun clear() = update(emptyList())

    override fun close() {
        closed = true
        mainHandler.post { clearOnMainThread() }
    }

    private fun replaceRegions(regions: List<OverlayRegion>) {
        if (regions.isEmpty()) {
            clearOnMainThread()
            return
        }
        val displayBounds = currentDisplayBounds()
        regions.forEachIndexed { index, region ->
            val scaledBounds = region.bounds.scaleToDisplay(
                sourceWidth = region.sourceWidth,
                sourceHeight = region.sourceHeight,
                displayBounds = displayBounds,
            )
            val existing = windows.getOrNull(index)
            if (existing == null) {
                val view = BlurOverlayView(appContext).apply { replaceBitmap(region.bitmap) }
                val params = createLayoutParams(scaledBounds, index)
                windowManager.addView(view, params)
                windows += OverlayWindow(view, params)
            } else {
                existing.view.replaceBitmap(region.bitmap)
                existing.params.setBounds(scaledBounds)
                windowManager.updateViewLayout(existing.view, existing.params)
            }
        }
        while (windows.size > regions.size) {
            removeWindow(windows.removeAt(windows.lastIndex))
        }
    }

    private fun createLayoutParams(bounds: Rect, index: Int): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        return WindowManager.LayoutParams(
            bounds.width().coerceAtLeast(1),
            bounds.height().coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1F
            x = bounds.left
            y = bounds.top
            title = "Halalify protected region $index"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    private fun WindowManager.LayoutParams.setBounds(bounds: Rect) {
        width = bounds.width().coerceAtLeast(1)
        height = bounds.height().coerceAtLeast(1)
        x = bounds.left
        y = bounds.top
    }

    private fun currentDisplayBounds(): Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Rect(windowManager.currentWindowMetrics.bounds)
    } else {
        @Suppress("DEPRECATION")
        Rect(0, 0, appContext.resources.displayMetrics.widthPixels, appContext.resources.displayMetrics.heightPixels)
    }

    private fun Rect.scaleToDisplay(
        sourceWidth: Int,
        sourceHeight: Int,
        displayBounds: Rect,
    ): Rect {
        val scaleX = displayBounds.width().toFloat() / sourceWidth.coerceAtLeast(1)
        val scaleY = displayBounds.height().toFloat() / sourceHeight.coerceAtLeast(1)
        return Rect(
            displayBounds.left + (left * scaleX).roundToInt(),
            displayBounds.top + (top * scaleY).roundToInt(),
            displayBounds.left + (right * scaleX).roundToInt(),
            displayBounds.top + (bottom * scaleY).roundToInt(),
        )
    }

    private fun clearOnMainThread() {
        while (windows.isNotEmpty()) {
            removeWindow(windows.removeAt(windows.lastIndex))
        }
    }

    private fun removeWindow(window: OverlayWindow) {
        window.view.replaceBitmap(null)
        runCatching { windowManager.removeViewImmediate(window.view) }
    }

    private fun List<OverlayRegion>.recycleBitmaps() {
        forEach { region ->
            if (!region.bitmap.isRecycled) region.bitmap.recycle()
        }
    }

    private class BlurOverlayView(context: Context) : View(context) {
        private val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
        }
        private val destination = Rect()
        private var bitmap: Bitmap? = null

        fun replaceBitmap(next: Bitmap?) {
            if (next === bitmap) return
            bitmap?.let { previous ->
                if (!previous.isRecycled) previous.recycle()
            }
            bitmap = next
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val frame = bitmap ?: return
            destination.set(0, 0, width, height)
            canvas.drawBitmap(frame, null, destination, paint)
        }
    }
}
