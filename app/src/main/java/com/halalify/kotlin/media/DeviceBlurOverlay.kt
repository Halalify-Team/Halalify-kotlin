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
import com.halalify.kotlin.BuildConfig
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
        val restoredOverlapCount: Int,
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
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearOnMainThread()
        } else {
            mainHandler.post { clearOnMainThread() }
        }
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

            val restoredOverlapCount = if (region.isFiltered) {
                restoreRedactedOverlaps(
                    bitmap = region.bitmap,
                    targetBounds = scaled,
                    protectionId = region.protectionId,
                )
            } else {
                0
            }
            region.bitmap.prepareToDraw()

            prepared += PreparedRegion(
                bitmap = region.bitmap,
                bounds = scaled,
                isFiltered = region.isFiltered,
                protectionId = region.protectionId,
                looksRedactedBlack =
                    region.isFiltered && region.bitmap.looksLikeRedactedBlack(),
                restoredOverlapCount = restoredOverlapCount,
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
                retireDisplayedBitmap(oldBitmap)
            }

            regionWindow.view.visibility = View.VISIBLE

            if (layoutChanged) {
                windowManager.updateViewLayout(
                    regionWindow.view,
                    params,
                )
            }

            if (BuildConfig.DEBUG) {
                Log.d(
                    HALALIFY_OVERLAY_TAG,
                    "protected region[$index] id=${region.protectionId} bounds=$rect filtered=${region.isFiltered} redacted=${region.looksRedactedBlack} restored=${region.restoredOverlapCount} preserved=$preserveBitmap type=$windowType alpha=${params.alpha}",
                )
            }
        }
    }

    /**
     * Android 15 replaces sensitive overlay pixels in MediaProjection with
     * pure black. A new detector box can overlap only part of an existing
     * window, so checking the whole patch misses that contamination. Restore
     * just those black intersections from the already-filtered visible
     * bitmap; clean pixels in the new capture remain untouched.
     */
    private fun restoreRedactedOverlaps(
        bitmap: Bitmap,
        targetBounds: Rect,
        protectionId: Long?,
    ): Int {
        var restored = 0
        // Restore neighbouring windows first and the stable matching window
        // last. If masks overlap, the previous bitmap of the same subject is
        // the best source for the shared pixels.
        windows.sortedBy { regionWindow ->
            if (
                protectionId != null &&
                regionWindow.protectionId == protectionId
            ) {
                1
            } else {
                0
            }
        }.forEach { regionWindow ->
            val params = regionWindow.params
            val sourceBounds = Rect(
                params.x,
                params.y,
                params.x + params.width,
                params.y + params.height,
            )
            if (
                regionWindow.view.restoreRedactedOverlapInto(
                    target = bitmap,
                    targetBounds = targetBounds,
                    sourceBounds = sourceBounds,
                )
            ) {
                restored += 1
            }
        }
        return restored
    }

    private fun matchWindowsToRegions(regions: List<PreparedRegion>) {
        while (windows.size < regions.size) {
            val view = OpaqueRegionView(trustedWindowContext ?: appContext)
            val params = createRegionLayoutParams()

            windowManager.addView(view, params)
            windows += RegionWindow(view, params)

            if (BuildConfig.DEBUG) {
                Log.d(
                    HALALIFY_OVERLAY_TAG,
                    "added pass-through protected region window type=$windowType alpha=${params.alpha}",
                )
            }
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
                retireDisplayedBitmap(it)
            }

            runCatching {
                windowManager.removeViewImmediate(removed.view)
            }
        }

        windows.clear()
        windows += ordered
    }

    private fun offsetOnMainThread(deltaX: Int, deltaY: Int) {
        val displayBounds = currentDisplayBounds()
        val iterator = windows.iterator()
        while (iterator.hasNext()) {
            val regionWindow = iterator.next()
            val params = regionWindow.params
            params.x += deltaX
            params.y += deltaY
            if (
                !windowIntersectsDisplay(
                    left = params.x,
                    top = params.y,
                    width = params.width,
                    height = params.height,
                    displayLeft = displayBounds.left,
                    displayTop = displayBounds.top,
                    displayRight = displayBounds.right,
                    displayBottom = displayBounds.bottom,
                )
            ) {
                regionWindow.view.detachBitmap()?.let {
                    retireDisplayedBitmap(it)
                }
                runCatching {
                    windowManager.removeViewImmediate(regionWindow.view)
                }
                iterator.remove()
                continue
            }
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
            if (BuildConfig.DEBUG) {
                Log.d(
                    HALALIFY_OVERLAY_TAG,
                    "migrated ${retained.size} protected region windows without replacing their bitmaps type=$windowType alpha=$windowAlpha",
                )
            }
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
            // Keep Halalify's rendered protection out of its own MediaProjection input.
            // View content sensitivity also makes the window secure, but Android 15+
            // displays a disruptive "content hidden" toast for every new sensitive window.
            WindowManager.LayoutParams.FLAG_SECURE or
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

    private fun clearOnMainThread() {
        val removedWindowCount = windows.size
        windows.forEach { regionWindow ->
            regionWindow.view.detachBitmap()?.let {
                retireDisplayedBitmap(it)
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
            if (BuildConfig.DEBUG) {
                Log.d(HALALIFY_OVERLAY_TAG, "cleared $removedWindowCount protected region windows")
            }
        }
    }

    /**
     * Hardware rendering can retain the previous bitmap for another display
     * frame. Returning it to the pool immediately erases it to black while
     * Android may still be presenting that frame.
     */
    private fun retireDisplayedBitmap(bitmap: Bitmap) {
        mainHandler.postDelayed(
            {
                FrameBlurRenderer.releaseOverlayBitmap(bitmap)
                if (closed) FrameBlurRenderer.clearBitmapPool()
            },
            DISPLAYED_BITMAP_RETIRE_DELAY_MS,
        )
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

        fun restoreRedactedOverlapInto(
            target: Bitmap,
            targetBounds: Rect,
            sourceBounds: Rect,
        ): Boolean {
            if (solid || target.isRecycled || !target.isMutable) return false
            val source = regionBitmap
                ?.takeUnless(Bitmap::isRecycled)
                ?: return false
            val mapping = bitmapOverlapMapping(
                sourceBounds = sourceBounds,
                sourceWidth = source.width,
                sourceHeight = source.height,
                targetBounds = targetBounds,
                targetWidth = target.width,
                targetHeight = target.height,
            ) ?: return false

            val redactedDestinations =
                if (
                    target.looksLikeRedactedBlack(
                        sampleBounds = mapping.destination,
                        requiredRatio = PARTIAL_REDACTION_BLACK_RATIO,
                    )
                ) {
                    listOf(mapping.destination)
                } else {
                    target.findRedactedTiles(mapping.destination)
                }
            if (redactedDestinations.isEmpty()) {
                return false
            }

            val canvas = Canvas(target)
            redactedDestinations.forEach { destination ->
                val sourceRegion = mapping.sourceForDestination(destination)
                    ?: return@forEach
                canvas.drawBitmap(
                    source,
                    sourceRegion,
                    destination,
                    bitmapPaint,
                )
            }
            target.setHasAlpha(false)
            return true
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
    }
}

private const val MIN_REGION_OVERLAP = 0.15F
private const val DISPLAYED_BITMAP_RETIRE_DELAY_MS = 64L
private const val REDACTION_SAMPLE_GRID_SIZE = 8
private const val REDACTION_TILE_GRID_SIZE = 6
private const val REDACTION_TILE_SAMPLE_GRID_SIZE = 3
private const val REDACTION_MIN_ALPHA = 240
private const val REDACTION_MAX_CHANNEL = 12
private const val REDACTION_BLACK_RATIO = 0.9F
private const val PARTIAL_REDACTION_BLACK_RATIO = 0.50F
private const val REDACTION_TILE_BLACK_RATIO = 0.66F

private fun Bitmap.findRedactedTiles(sampleBounds: Rect): List<Rect> {
    if (isRecycled || width <= 0 || height <= 0) return emptyList()
    val clipped = Rect(sampleBounds)
    if (!clipped.intersect(0, 0, width, height)) return emptyList()

    val redacted = ArrayList<Rect>()
    repeat(REDACTION_TILE_GRID_SIZE) { row ->
        repeat(REDACTION_TILE_GRID_SIZE) { column ->
            val tile = Rect(
                clipped.left + clipped.width() * column / REDACTION_TILE_GRID_SIZE,
                clipped.top + clipped.height() * row / REDACTION_TILE_GRID_SIZE,
                clipped.left + clipped.width() * (column + 1) / REDACTION_TILE_GRID_SIZE,
                clipped.top + clipped.height() * (row + 1) / REDACTION_TILE_GRID_SIZE,
            )
            if (
                tile.width() > 0 &&
                tile.height() > 0 &&
                looksLikeRedactedBlack(
                    sampleBounds = tile,
                    requiredRatio = REDACTION_TILE_BLACK_RATIO,
                    sampleGridSize = REDACTION_TILE_SAMPLE_GRID_SIZE,
                )
            ) {
                redacted += tile
            }
        }
    }
    return redacted
}

private fun Bitmap.looksLikeRedactedBlack(
    sampleBounds: Rect = Rect(0, 0, width, height),
    requiredRatio: Float = REDACTION_BLACK_RATIO,
    sampleGridSize: Int = REDACTION_SAMPLE_GRID_SIZE,
): Boolean {
    if (isRecycled || width <= 0 || height <= 0) return false
    val clipped = Rect(sampleBounds)
    if (!clipped.intersect(0, 0, width, height)) return false
    if (clipped.width() <= 0 || clipped.height() <= 0) return false

    var blackSamples = 0
    var totalSamples = 0
    val gridSize = sampleGridSize.coerceAtLeast(1)
    repeat(gridSize) { row ->
        val y =
            (
                clipped.top +
                    ((row + 0.5F) * clipped.height()) / gridSize
                )
                .toInt()
                .coerceIn(clipped.top, clipped.bottom - 1)
        repeat(gridSize) { column ->
            val x =
                (
                    clipped.left +
                        ((column + 0.5F) * clipped.width()) / gridSize
                    )
                    .toInt()
                    .coerceIn(clipped.left, clipped.right - 1)
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

    return blackSamples.toFloat() / totalSamples >= requiredRatio
}

private data class BitmapOverlapMapping(
    val source: Rect,
    val destination: Rect,
)

private fun BitmapOverlapMapping.sourceForDestination(
    subregion: Rect,
): Rect? {
    val clipped = Rect(subregion)
    if (!clipped.intersect(destination)) return null

    fun map(
        coordinate: Int,
        destinationStart: Int,
        destinationSize: Int,
        sourceStart: Int,
        sourceSize: Int,
    ): Int = (
        sourceStart +
            (coordinate - destinationStart).toFloat() *
                sourceSize.toFloat() /
                destinationSize.coerceAtLeast(1).toFloat()
        ).roundToInt().coerceIn(sourceStart, sourceStart + sourceSize)

    val mapped = Rect(
        map(clipped.left, destination.left, destination.width(), source.left, source.width()),
        map(clipped.top, destination.top, destination.height(), source.top, source.height()),
        map(clipped.right, destination.left, destination.width(), source.left, source.width()),
        map(clipped.bottom, destination.top, destination.height(), source.top, source.height()),
    )
    return mapped.takeIf { it.width() > 0 && it.height() > 0 }
}

private fun bitmapOverlapMapping(
    sourceBounds: Rect,
    sourceWidth: Int,
    sourceHeight: Int,
    targetBounds: Rect,
    targetWidth: Int,
    targetHeight: Int,
): BitmapOverlapMapping? {
    if (
        sourceBounds.width() <= 0 ||
        sourceBounds.height() <= 0 ||
        targetBounds.width() <= 0 ||
        targetBounds.height() <= 0 ||
        sourceWidth <= 0 ||
        sourceHeight <= 0 ||
        targetWidth <= 0 ||
        targetHeight <= 0
    ) {
        return null
    }

    val intersection = Rect(sourceBounds)
    if (!intersection.intersect(targetBounds)) return null

    fun mapCoordinate(
        coordinate: Int,
        boundsStart: Int,
        boundsSize: Int,
        bitmapSize: Int,
    ): Int = (
        (coordinate - boundsStart).toFloat() *
            bitmapSize.toFloat() /
            boundsSize.toFloat()
        ).roundToInt().coerceIn(0, bitmapSize)

    val sourceRect = Rect(
        mapCoordinate(intersection.left, sourceBounds.left, sourceBounds.width(), sourceWidth),
        mapCoordinate(intersection.top, sourceBounds.top, sourceBounds.height(), sourceHeight),
        mapCoordinate(intersection.right, sourceBounds.left, sourceBounds.width(), sourceWidth),
        mapCoordinate(intersection.bottom, sourceBounds.top, sourceBounds.height(), sourceHeight),
    )
    val destinationRect = Rect(
        mapCoordinate(intersection.left, targetBounds.left, targetBounds.width(), targetWidth),
        mapCoordinate(intersection.top, targetBounds.top, targetBounds.height(), targetHeight),
        mapCoordinate(intersection.right, targetBounds.left, targetBounds.width(), targetWidth),
        mapCoordinate(intersection.bottom, targetBounds.top, targetBounds.height(), targetHeight),
    )
    return if (
        sourceRect.width() > 0 &&
        sourceRect.height() > 0 &&
        destinationRect.width() > 0 &&
        destinationRect.height() > 0
    ) {
        BitmapOverlapMapping(sourceRect, destinationRect)
    } else {
        null
    }
}

internal fun windowIntersectsDisplay(
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    displayLeft: Int,
    displayTop: Int,
    displayRight: Int,
    displayBottom: Int,
): Boolean {
    val right = left.toLong() + width.coerceAtLeast(0)
    val bottom = top.toLong() + height.coerceAtLeast(0)
    return right > displayLeft &&
        left < displayRight &&
        bottom > displayTop &&
        top < displayBottom
}
