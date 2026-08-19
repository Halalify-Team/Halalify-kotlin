package com.halalify.kotlin.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import java.io.Closeable
import kotlin.math.roundToInt

private const val HALALIFY_OVERLAY_TAG = "HalalifyOverlay"

internal interface ProtectionOverlay : Closeable {
    /** Takes ownership of every bitmap in [regions]. */
    fun update(regions: List<OverlayRegion>)
}

@RequiresApi(Build.VERSION_CODES.O)
internal class DeviceBlurOverlay(
    context: Context,
    private val onError: (String) -> Unit,
) : ProtectionOverlay {

    private data class RegionWindow(
        val view: OpaqueRegionView,
        val params: WindowManager.LayoutParams,
    )

    private data class PreparedRegion(
        val bitmap: Bitmap,
        val bounds: Rect,
        val isFiltered: Boolean,
    )

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateLock = Any()

    private var pending: List<OverlayRegion>? = null
    private var updatePosted = false
    private val windows = mutableListOf<RegionWindow>()

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

        if (shouldPost) {
            mainHandler.post { applyPending() }
        }
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
            } else {
                false
            }
        }

        if (shouldPost) {
            mainHandler.post { applyPending() }
        }
    }

    /**
     * Every protected region lives in its own touch-consuming PixelFormat.OPAQUE
     * overlay window. This keeps Android 12+ from applying the pass-through
     * obscuring-opacity cap.
     *
     * SOLID      -> the window draws pure black.
     * PIXELATED  -> the window draws the opaque pixelated bitmap.
     * SOFT_BLUR  -> the window draws the opaque blurred bitmap.
     */
    private fun renderRegions(regions: List<OverlayRegion>) {
        val displayBounds = currentDisplayBounds()
        val prepared = ArrayList<PreparedRegion>(regions.size)

        regions.forEach { region ->
            if (
                region.bitmap.isRecycled ||
                region.sourceWidth <= 0 ||
                region.sourceHeight <= 0
            ) {
                FrameBlurRenderer.releaseOverlayBitmap(region.bitmap)
                return@forEach
            }

            val scaled = region.bounds.scaleToDisplay(
                sourceWidth = region.sourceWidth,
                sourceHeight = region.sourceHeight,
                display = displayBounds,
            )

            if (scaled.width() <= 0 || scaled.height() <= 0) {
                FrameBlurRenderer.releaseOverlayBitmap(region.bitmap)
                return@forEach
            }

            region.bitmap.prepareToDraw()

            prepared += PreparedRegion(
                bitmap = region.bitmap,
                bounds = scaled,
                isFiltered = region.isFiltered,
            )
        }

        ensureWindowCount(prepared.size)

        prepared.forEachIndexed { index, region ->
            val regionWindow = windows[index]
            val params = regionWindow.params
            val rect = region.bounds

            val layoutChanged =
                params.x != rect.left ||
                params.y != rect.top ||
                params.width != rect.width() ||
                params.height != rect.height()

            params.x = rect.left
            params.y = rect.top
            params.width = rect.width()
            params.height = rect.height()
            params.alpha = 1F
            params.format = PixelFormat.OPAQUE

            val oldBitmap = regionWindow.view.replaceRegion(
                bitmap = region.bitmap,
                solid = !region.isFiltered,
            )

            if (
                oldBitmap != null &&
                oldBitmap !== region.bitmap
            ) {
                FrameBlurRenderer.releaseOverlayBitmap(oldBitmap)
            }

            regionWindow.view.visibility = View.VISIBLE

            if (layoutChanged) {
                windowManager.updateViewLayout(
                    regionWindow.view,
                    params,
                )
            }

            Log.d(
                HALALIFY_OVERLAY_TAG,
                "OPAQUE region[$index] bounds=$rect filtered=${region.isFiltered} format=${params.format} alpha=${params.alpha}",
            )
        }
    }

    private fun ensureWindowCount(required: Int) {
        while (windows.size < required) {
            val view = OpaqueRegionView(appContext)
            val params = createRegionLayoutParams()

            windowManager.addView(view, params)
            windows += RegionWindow(view, params)

            Log.d(
                HALALIFY_OVERLAY_TAG,
                "added TOUCH-CONSUMING OPAQUE region window format=${params.format} alpha=${params.alpha}",
            )
        }

        while (windows.size > required) {
            val removed = windows.removeAt(windows.lastIndex)

            removed.view.detachBitmap()?.let {
                FrameBlurRenderer.releaseOverlayBitmap(it)
            }

            runCatching {
                windowManager.removeViewImmediate(removed.view)
            }
        }
    }

    private fun createRegionLayoutParams(): WindowManager.LayoutParams {
        // Deliberately no FLAG_NOT_TOUCHABLE:
        // the protected rectangle consumes touches so Android does not cap
        // TYPE_APPLICATION_OVERLAY opacity on Android 12+.
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        return WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 1F
            title = "Halalify Opaque Protected Region"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    private fun currentDisplayBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(windowManager.currentWindowMetrics.bounds)
        } else {
            @Suppress("DEPRECATION")
            Rect(
                0,
                0,
                appContext.resources.displayMetrics.widthPixels,
                appContext.resources.displayMetrics.heightPixels,
            )
        }

    private fun Rect.scaleToDisplay(
        sourceWidth: Int,
        sourceHeight: Int,
        display: Rect,
    ): Rect {
        val scaleX =
            display.width().toFloat() /
                sourceWidth.coerceAtLeast(1)

        val scaleY =
            display.height().toFloat() /
                sourceHeight.coerceAtLeast(1)

        return Rect(
            display.left +
                (left.coerceIn(0, sourceWidth) * scaleX)
                    .roundToInt(),

            display.top +
                (top.coerceIn(0, sourceHeight) * scaleY)
                    .roundToInt(),

            display.left +
                (right.coerceIn(0, sourceWidth) * scaleX)
                    .roundToInt(),

            display.top +
                (bottom.coerceIn(0, sourceHeight) * scaleY)
                    .roundToInt(),
        )
    }

    private fun clearOnMainThread() {
        windows.forEach { regionWindow ->
            regionWindow.view.detachBitmap()?.let {
                FrameBlurRenderer.releaseOverlayBitmap(it)
            }

            runCatching {
                windowManager.removeViewImmediate(
                    regionWindow.view,
                )
            }
        }

        windows.clear()
        FrameBlurRenderer.clearBitmapPool()
    }

    private fun List<OverlayRegion>.releaseBitmaps() {
        forEach {
            FrameBlurRenderer.releaseOverlayBitmap(it.bitmap)
        }
    }

    /**
     * The view itself is always in a PixelFormat.OPAQUE window.
     * It starts by replacing the full surface with opaque black, then:
     * - SOLID: leaves it black.
     * - filtered modes: paints the already-opaque generated bitmap on top.
     */
    private class OpaqueRegionView(
        context: Context,
    ) : View(context) {

        private var regionBitmap: Bitmap? = null
        private var solid = true

        private val bitmapPaint =
            Paint(Paint.FILTER_BITMAP_FLAG).apply {
                alpha = 255
                isDither = false
            }

        init {
            setBackgroundColor(Color.BLACK)
            setWillNotDraw(false)
            isClickable = true
        }

        fun replaceRegion(
            bitmap: Bitmap,
            solid: Boolean,
        ): Bitmap? {
            val previous = regionBitmap

            regionBitmap = bitmap
            this.solid = solid

            invalidate()
            return previous
        }

        fun detachBitmap(): Bitmap? {
            val old = regionBitmap
            regionBitmap = null
            return old
        }

        override fun onDraw(canvas: Canvas) {
            // Replace every surface pixel with fully opaque black first.
            canvas.drawColor(
                Color.BLACK,
                PorterDuff.Mode.SRC,
            )

            if (solid) {
                return
            }

            val bitmap = regionBitmap
                ?.takeUnless(Bitmap::isRecycled)
                ?: return

            canvas.drawBitmap(
                bitmap,
                null,
                Rect(
                    0,
                    0,
                    width.coerceAtLeast(1),
                    height.coerceAtLeast(1),
                ),
                bitmapPaint,
            )
        }

        // Required for full opacity on Android 12+ SAW overlays.
        override fun onTouchEvent(
            event: MotionEvent,
        ): Boolean = true
    }
}