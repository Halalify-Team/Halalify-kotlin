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
import androidx.annotation.RequiresApi
import java.io.Closeable
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

    private class ActiveRenderRegion(
        val bitmap: Bitmap,
        val displayBounds: Rect,
    )

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateLock = Any()
    private var pendingRegions: List<OverlayRegion>? = null
    private var updatePosted = false
    @Volatile
    private var closed = false

    private var overlayView: FullDeviceBlurOverlayView? = null
    private var isViewAttached = false

    /** Takes ownership of every bitmap in [regions]. */
    override fun update(regions: List<OverlayRegion>) {
        if (closed) {
            regions.recycleBitmaps()
            return
        }
        val superseded: List<OverlayRegion>?
        val shouldPost: Boolean
        synchronized(updateLock) {
            if (closed) {
                regions.recycleBitmaps()
                return
            }
            superseded = pendingRegions
            pendingRegions = regions
            shouldPost = !updatePosted
            updatePosted = true
        }
        superseded?.recycleBitmaps()
        if (shouldPost) mainHandler.post { applyPendingUpdate() }
    }

    private fun applyPendingUpdate() {
        val regions = synchronized(updateLock) {
            val next = pendingRegions
            pendingRegions = null
            updatePosted = false
            next
        } ?: return

        if (closed) {
            regions.recycleBitmaps()
            return
        }
        try {
            if (!Settings.canDrawOverlays(appContext)) {
                regions.recycleBitmaps()
                clearOnMainThread()
                onError("Display-over-other-apps permission was revoked.")
            } else {
                renderRegions(regions)
            }
        } catch (error: Throwable) {
            clearOnMainThread()
            regions.recycleBitmaps()
            onError(error.message ?: error.javaClass.simpleName)
        }

        val shouldPost = synchronized(updateLock) {
            if (pendingRegions != null && !updatePosted) {
                updatePosted = true
                true
            } else {
                false
            }
        }
        if (shouldPost) mainHandler.post { applyPendingUpdate() }
    }

    fun clear() = update(emptyList())

    override fun close() {
        closed = true
        val pending = synchronized(updateLock) {
            val next = pendingRegions
            pendingRegions = null
            updatePosted = false
            next
        }
        pending?.recycleBitmaps()
        mainHandler.post { clearOnMainThread() }
    }

    private fun ensureOverlayView(): FullDeviceBlurOverlayView {
        val existing = overlayView
        if (existing != null && isViewAttached) return existing

        val view = existing ?: FullDeviceBlurOverlayView(appContext).also { overlayView = it }
        if (!isViewAttached) {
            val params = createFullScreenLayoutParams()
            windowManager.addView(view, params)
            isViewAttached = true
        }
        return view
    }

    private fun renderRegions(regions: List<OverlayRegion>) {
        if (regions.isEmpty()) {
            overlayView?.setRegions(emptyList())
            return
        }
        val displayBounds = currentDisplayBounds()
        val renderList = ArrayList<ActiveRenderRegion>(regions.size)
        regions.forEach { region ->
            val scaledBounds = region.bounds.scaleToDisplay(
                sourceWidth = region.sourceWidth,
                sourceHeight = region.sourceHeight,
                displayBounds = displayBounds,
            )
            region.bitmap.prepareToDraw()
            renderList.add(ActiveRenderRegion(region.bitmap, scaledBounds))
        }

        val view = ensureOverlayView()
        view.setRegions(renderList)
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
            alpha = 1.0F
            x = 0
            y = 0
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
        overlayView?.let { view ->
            view.setRegions(emptyList())
            if (isViewAttached) {
                runCatching { windowManager.removeViewImmediate(view) }
                isViewAttached = false
            }
        }
        overlayView = null
    }

    private fun List<OverlayRegion>.recycleBitmaps() {
        forEach { region ->
            if (!region.bitmap.isRecycled) region.bitmap.recycle()
        }
    }

    private class FullDeviceBlurOverlayView(
        context: Context,
    ) : View(context) {
        private val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
        }
        private var currentRegions: List<ActiveRenderRegion> = emptyList()

        fun setRegions(newRegions: List<ActiveRenderRegion>) {
            val oldRegions = currentRegions
            currentRegions = newRegions
            // Recycle old bitmaps that are not reused
            oldRegions.forEach { old ->
                if (!old.bitmap.isRecycled && newRegions.none { it.bitmap === old.bitmap }) {
                    old.bitmap.recycle()
                }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val regions = currentRegions
            if (regions.isEmpty()) return
            for (i in regions.indices) {
                val region = regions[i]
                if (!region.bitmap.isRecycled) {
                    canvas.drawBitmap(region.bitmap, null, region.displayBounds, paint)
                }
            }
        }
    }
}
