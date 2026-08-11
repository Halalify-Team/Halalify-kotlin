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

internal class DeviceBlurOverlay(
    context: Context,
    private val onError: (String) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: BlurOverlayView? = null
    @Volatile private var closed = false

    /** Takes ownership of [bitmap] and recycles the previously displayed frame. */
    fun update(bitmap: Bitmap?) {
        if (closed) {
            bitmap?.recycle()
            return
        }
        mainHandler.post {
            if (closed) {
                bitmap?.recycle()
                return@post
            }
            if (bitmap == null && overlayView == null) return@post
            var deliveredToView = false
            try {
                if (!Settings.canDrawOverlays(appContext)) {
                    bitmap?.recycle()
                    clearOnMainThread()
                    onError("Display-over-other-apps permission was revoked.")
                    return@post
                }
                val view = ensureView()
                view.replaceBitmap(bitmap)
                deliveredToView = true
                view.visibility = if (bitmap == null) View.INVISIBLE else View.VISIBLE
            } catch (error: Throwable) {
                if (!deliveredToView) bitmap?.recycle()
                clearOnMainThread()
                onError(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun clear() = update(null)

    override fun close() {
        closed = true
        mainHandler.post { clearOnMainThread() }
    }

    private fun ensureView(): BlurOverlayView {
        overlayView?.let { return it }
        val view = BlurOverlayView(appContext)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = MAX_TOUCH_THROUGH_ALPHA
            title = "Halalify device blur"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        windowManager.addView(view, params)
        overlayView = view
        return view
    }

    private fun clearOnMainThread() {
        val view = overlayView ?: return
        view.replaceBitmap(null)
        runCatching { windowManager.removeViewImmediate(view) }
        overlayView = null
    }

    private class BlurOverlayView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val destination = Rect()
        private var bitmap: Bitmap? = null

        fun replaceBitmap(next: Bitmap?) {
            if (next === bitmap) return
            bitmap?.recycle()
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

    private companion object {
        // Android 12+ permits touch pass-through for one application-overlay window
        // only when its window alpha is at or below the system obscuring limit.
        const val MAX_TOUCH_THROUGH_ALPHA = 0.8F
    }
}
