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
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import java.io.Closeable
import kotlin.math.roundToInt

private const val HALALIFY_OVERLAY_TAG = "HalalifyOverlay"
private const val MAX_UNTRUSTED_PASS_THROUGH_OPACITY = 0.8F

internal interface ProtectionOverlay : Closeable {
    /** Takes ownership of every bitmap in [regions]. */
    fun update(regions: List<OverlayRegion>)

    /** Moves the currently visible regions with scrolled screen content. */
    fun offset(deltaX: Int, deltaY: Int) = Unit
}

@RequiresApi(Build.VERSION_CODES.O)
internal class DeviceBlurOverlay(
    context: Context,
    private val onError: (String) -> Unit,
) : ProtectionOverlay {

    private data class RegionWindow(
        val view: OpaqueRegionView,
        val params: WindowManager.LayoutParams,
        var protectionId: Long? = null,
    )

    private data class PreparedRegion(
        val bitmap: Bitmap,
        val bounds: Rect,
        val isFiltered: Boolean,
        val protectionId: Long?,
        val looksRedactedBlack: Boolean,
    )

    private data class RetainedWindow(
        val bitmap: Bitmap?,
        val solid: Boolean,
        val bounds: Rect,
        val protectionId: Long?,
    )

    private val appContext = context.applicationContext
    private var trustedAccessibilityService = TrustedOverlayHost.currentService()
    // AccessibilityService already owns the trusted overlay token. Reusing
    // its context is required; a newly-created WindowContext has no valid
    // accessibility token on Android 12+/the emulator.
    private var trustedWindowContext = trustedAccessibilityService
    private var usesTrustedOverlay = trustedWindowContext != null
    private var windowManager = resolveWindowManager()
    private var windowType = resolveWindowType()
    private var windowAlpha = resolveWindowAlpha()
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

        // Accessibility navigation callbacks already run on the main looper.
        // Remove stale windows inside that callback instead of placing the
        // clear behind pending bitmap/render work.
        if (regions.isEmpty() && Looper.myLooper() == Looper.getMainLooper()) {
            val oldPending = synchronized(updateLock) {
                pending.also {
                    pending = null
                    updatePosted = false
                }
            }
            oldPending?.releaseBitmaps()
            clearOnMainThread()
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

    override fun offset(deltaX: Int, deltaY: Int) {
        if (closed || (deltaX == 0 && deltaY == 0)) return

        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyPending()
            offsetOnMainThread(deltaX, deltaY)
        } else {
            mainHandler.post {
                if (!closed) {
                    applyPending()
                    offsetOnMainThread(deltaX, deltaY)
                }
            }
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
            if (!usesTrustedOverlay && !Settings.canDrawOverlays(appContext)) {
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
     * Every protected region lives in its own PixelFormat.OPAQUE overlay window.
     * On Android 12+ an enabled accessibility service supplies a trusted window,
     * so the region can be fully opaque without intercepting touch or scroll.
     *
     * SOLID      -> the window draws pure black.
     * PIXELATED  -> the window draws the opaque pixelated bitmap.
     * SOFT_BLUR  -> the window draws the opaque blurred bitmap.
     */
    private fun renderRegions(regions: List<OverlayRegion>) {
        refreshWindowHost()
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
                protectionId = region.protectionId,
                looksRedactedBlack =
                    region.isFiltered && region.bitmap.looksLikeRedactedBlack(),
            )
        }

        matchWindowsToRegions(prepared)

        prepared.forEachIndexed { index, region ->
            val regionWindow = windows[index]
            val params = regionWindow.params
            val rect = region.bounds
            val previousBounds = Rect(
                params.x,
                params.y,
                params.x + params.width,
                params.y + params.height,
            )

            val layoutChanged =
                params.x != rect.left ||
                params.y != rect.top ||
                params.width != rect.width() ||
                params.height != rect.height()

            params.x = rect.left
            params.y = rect.top
            params.width = rect.width()
            params.height = rect.height()
            params.alpha = windowAlpha
            params.format = PixelFormat.OPAQUE

            val preserveBitmap = shouldPreserveOverlayBitmap(
                currentProtectionId = regionWindow.protectionId,
                hasFilteredBitmap = regionWindow.view.hasFilteredBitmap(),
                newProtectionId = region.protectionId,
                newIsFiltered = region.isFiltered,
                newBitmapLooksRedacted = region.looksRedactedBlack,
                hasSpatialContinuity =
                    previousBounds.overlapFraction(rect) >= MIN_REGION_OVERLAP,
            )
            val oldBitmap = if (preserveBitmap) {
                FrameBlurRenderer.releaseOverlayBitmap(region.bitmap)
                null
            } else {
                regionWindow.view.replaceRegion(
                    bitmap = region.bitmap,
                    solid = !region.isFiltered,
                )
            }
            regionWindow.protectionId = region.protectionId

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
                "protected region[$index] id=${region.protectionId} bounds=$rect filtered=${region.isFiltered} redacted=${region.looksRedactedBlack} preserved=$preserveBitmap type=$windowType alpha=${params.alpha}",
            )
        }
    }

    private fun matchWindowsToRegions(regions: List<PreparedRegion>) {
        while (windows.size < regions.size) {
            val view = OpaqueRegionView(trustedWindowContext ?: appContext)
            val params = createRegionLayoutParams()

            windowManager.addView(view, params)
            windows += RegionWindow(view, params)

            Log.d(
                HALALIFY_OVERLAY_TAG,
                "added pass-through protected region window type=$windowType alpha=${params.alpha}",
            )
        }

        val incomingIds = regions.mapNotNull(PreparedRegion::protectionId).toSet()
        val available = windows.toMutableList()
        val ordered = ArrayList<RegionWindow>(regions.size)
        regions.forEach { region ->
            val matchingWindow = region.protectionId?.let { protectionId ->
                available.firstOrNull { it.protectionId == protectionId }
            }
            val reusableCandidates = available.filter {
                it.protectionId == null || it.protectionId !in incomingIds
            }
            val spatialWindow = reusableCandidates
                .filter { it.view.hasFilteredBitmap() }
                .maxByOrNull { window ->
                    Rect(
                        window.params.x,
                        window.params.y,
                        window.params.x + window.params.width,
                        window.params.y + window.params.height,
                    ).overlapFraction(region.bounds)
                }
                ?.takeIf { window ->
                    Rect(
                        window.params.x,
                        window.params.y,
                        window.params.x + window.params.width,
                        window.params.y + window.params.height,
                    ).overlapFraction(region.bounds) >= MIN_REGION_OVERLAP
                }
            val reusableWindow =
                matchingWindow ?: spatialWindow ?: reusableCandidates.firstOrNull() ?: available.first()
            available.remove(reusableWindow)
            ordered += reusableWindow
        }

        available.forEach { removed ->
            removed.view.detachBitmap()?.let {
                FrameBlurRenderer.releaseOverlayBitmap(it)
            }

            runCatching {
                windowManager.removeViewImmediate(removed.view)
            }
        }

        windows.clear()
        windows += ordered
    }

    private fun offsetOnMainThread(deltaX: Int, deltaY: Int) {
        windows.forEach { regionWindow ->
            val params = regionWindow.params
            params.x += deltaX
            params.y += deltaY
            windowManager.updateViewLayout(regionWindow.view, params)
        }
    }

    /**
     * Android can reconnect an accessibility service while screen capture is
     * still running (for example after an app update). Its old WindowManager
     * then retains an invalid accessibility token. Resolve the active service
     * before every render and move the already-filtered bitmaps to the new
     * host. Re-rendering them from MediaProjection would capture Android's
     * redacted black rectangles instead of the original filtered pixels.
     */
    private fun refreshWindowHost() {
        val currentService = TrustedOverlayHost.currentService()
        if (currentService === trustedAccessibilityService) return

        val previousWindowManager = windowManager
        val retained = windows.map { regionWindow ->
            RetainedWindow(
                bitmap = regionWindow.view.detachBitmap(),
                solid = regionWindow.view.isSolid(),
                bounds = Rect(
                    regionWindow.params.x,
                    regionWindow.params.y,
                    regionWindow.params.x + regionWindow.params.width,
                    regionWindow.params.y + regionWindow.params.height,
                ),
                protectionId = regionWindow.protectionId,
            ).also {
                runCatching {
                    previousWindowManager.removeViewImmediate(regionWindow.view)
                }
            }
        }
        windows.clear()

        trustedAccessibilityService = currentService
        trustedWindowContext = currentService
        usesTrustedOverlay = currentService != null
        windowManager = resolveWindowManager()
        windowType = resolveWindowType()
        windowAlpha = resolveWindowAlpha()

        retained.forEach { retainedWindow ->
            val view = OpaqueRegionView(trustedWindowContext ?: appContext)
            val params = createRegionLayoutParams().apply {
                x = retainedWindow.bounds.left
                y = retainedWindow.bounds.top
                width = retainedWindow.bounds.width().coerceAtLeast(1)
                height = retainedWindow.bounds.height().coerceAtLeast(1)
            }
            view.restoreRegion(
                bitmap = retainedWindow.bitmap,
                solid = retainedWindow.solid,
            )

            try {
                windowManager.addView(view, params)
                windows += RegionWindow(
                    view = view,
                    params = params,
                    protectionId = retainedWindow.protectionId,
                )
            } catch (error: Throwable) {
                view.detachBitmap()?.let(FrameBlurRenderer::releaseOverlayBitmap)
                throw error
            }
        }

        if (retained.isNotEmpty()) {
            Log.d(
                HALALIFY_OVERLAY_TAG,
                "migrated ${retained.size} protected region windows without replacing their bitmaps type=$windowType alpha=$windowAlpha",
            )
        }
    }

    private fun resolveWindowManager(): WindowManager =
        trustedWindowContext?.getSystemService(WindowManager::class.java)
            ?: appContext.getSystemService(WindowManager::class.java)

    private fun resolveWindowType(): Int =
        if (usesTrustedOverlay) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

    private fun resolveWindowAlpha(): Float =
        if (usesTrustedOverlay || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            1F
        } else {
            MAX_UNTRUSTED_PASS_THROUGH_OPACITY
        }

    private fun createRegionLayoutParams(): WindowManager.LayoutParams {
        // Let touches pass through to the app below. A protected body can
        // cover most of a phone screen, so consuming this window would make
        // scrolling the underlying page impossible.
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        return WindowManager.LayoutParams(
            1,
            1,
            windowType,
            flags,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = windowAlpha
            title = "Halalify Protected Region"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    private fun currentDisplayBounds(): Rect = appContext.getRealDisplayBounds()

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

    private fun Rect.overlapFraction(other: Rect): Float {
        val intersectionLeft = maxOf(left, other.left)
        val intersectionTop = maxOf(top, other.top)
        val intersectionRight = minOf(right, other.right)
        val intersectionBottom = minOf(bottom, other.bottom)
        if (
            intersectionRight <= intersectionLeft ||
            intersectionBottom <= intersectionTop
        ) {
            return 0F
        }

        val intersectionArea =
            (intersectionRight - intersectionLeft).toLong() *
                (intersectionBottom - intersectionTop).toLong()
        val thisArea = width().coerceAtLeast(1).toLong() * height().coerceAtLeast(1)
        val otherArea = other.width().coerceAtLeast(1).toLong() * other.height().coerceAtLeast(1)
        return intersectionArea.toFloat() / minOf(thisArea, otherArea).toFloat()
    }

    private fun Bitmap.looksLikeRedactedBlack(): Boolean {
        if (isRecycled || width <= 0 || height <= 0) return false

        var blackSamples = 0
        var totalSamples = 0
        repeat(REDACTION_SAMPLE_GRID_SIZE) { row ->
            val y =
                (((row + 0.5F) * height) / REDACTION_SAMPLE_GRID_SIZE)
                    .toInt()
                    .coerceIn(0, height - 1)
            repeat(REDACTION_SAMPLE_GRID_SIZE) { column ->
                val x =
                    (((column + 0.5F) * width) / REDACTION_SAMPLE_GRID_SIZE)
                        .toInt()
                        .coerceIn(0, width - 1)
                val pixel = getPixel(x, y)
                if (
                    Color.alpha(pixel) >= REDACTION_MIN_ALPHA &&
                    Color.red(pixel) <= REDACTION_MAX_CHANNEL &&
                    Color.green(pixel) <= REDACTION_MAX_CHANNEL &&
                    Color.blue(pixel) <= REDACTION_MAX_CHANNEL
                ) {
                    blackSamples += 1
                }
                totalSamples += 1
            }
        }

        return blackSamples.toFloat() / totalSamples >= REDACTION_BLACK_RATIO
    }

    private fun clearOnMainThread() {
        val removedWindowCount = windows.size
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
        if (removedWindowCount > 0) {
            Log.d(HALALIFY_OVERLAY_TAG, "cleared $removedWindowCount protected region windows")
        }
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
            isClickable = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                // Android marks this small region window secure only while a
                // MediaProjection is active. The user still sees the blur, but
                // Halalify's detector receives a redacted region instead of a
                // recursive copy of its own pixelated output.
                contentSensitivity = View.CONTENT_SENSITIVITY_SENSITIVE
            }
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

        fun restoreRegion(
            bitmap: Bitmap?,
            solid: Boolean,
        ) {
            regionBitmap = bitmap
            this.solid = solid
            invalidate()
        }

        fun isSolid(): Boolean = solid

        fun hasFilteredBitmap(): Boolean =
            !solid && regionBitmap?.isRecycled == false

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
    }
}

private const val MIN_REGION_OVERLAP = 0.15F
private const val REDACTION_SAMPLE_GRID_SIZE = 8
private const val REDACTION_MIN_ALPHA = 240
private const val REDACTION_MAX_CHANNEL = 12
private const val REDACTION_BLACK_RATIO = 0.9F
