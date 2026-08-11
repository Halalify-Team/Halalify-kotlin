package com.halalify.kotlin.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.halalify.kotlin.media.DeviceBlurOverlay
import com.halalify.kotlin.media.FrameBlurRenderer
import com.halalify.kotlin.media.ProtectionTracker
import com.halalify.kotlin.model.Detection
import com.halalify.kotlin.model.NativeVisionEngine
import com.halalify.kotlin.settings.BlurSettingsRepository
import java.io.ByteArrayOutputStream

internal class AudioCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var visionThread: HandlerThread? = null
    private var visionEngine: NativeVisionEngine? = null
    private var deviceOverlay: DeviceBlurOverlay? = null
    private val protectionTracker = ProtectionTracker()
    private val frameActivityDetector = FrameActivityDetector()
    private var lastChangeCheckAt = 0L
    @Volatile private var visionRunning = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture("Android stopped the capture session.")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> CaptureSessionStore.update(
                message = "Choose the app or screen to protect in Android's sharing dialog.",
            )
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> { stopCapture("Capture stopped."); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        stopCapture("Preparing capture session.")
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        if (resultCode == Int.MIN_VALUE || data == null) {
            CaptureSessionStore.update(message = "Capture session data is invalid.")
            stopSelf()
            return
        }
        startForegroundNotification()
        try {
            val mediaProjection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, data) ?: error("MediaProjection was not created.")
            mediaProjection.registerCallback(projectionCallback, null)
            projection = mediaProjection
            val settings = BlurSettingsRepository(applicationContext).load()
            check(Settings.canDrawOverlays(this)) {
                "Display-over-other-apps permission is required for device-level blur."
            }
            visionEngine = NativeVisionEngine(applicationContext, settings.target)
            deviceOverlay = DeviceBlurOverlay(applicationContext) { message ->
                CaptureSessionStore.update(message = "Device blur overlay failed: $message")
            }
            startScreenPreview(mediaProjection)
            CaptureSessionStore.update(
                isCapturing = true,
                targetLabel = "Blur target: ${settings.target.title}",
                message = "Device-level blur is active over the shared screen.",
            )
        } catch (error: Throwable) {
            stopCapture("Could not start capture: ${error.message ?: error.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun startScreenPreview(mediaProjection: MediaProjection) {
        val metrics = resources.displayMetrics
        val width = CAPTURE_WIDTH.coerceAtMost(metrics.widthPixels)
        val height = (metrics.heightPixels.toFloat() * width / metrics.widthPixels).toInt().coerceAtLeast(1)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val handlerThread = HandlerThread("halalify-vision").apply { start() }
        visionThread = handlerThread
        lastChangeCheckAt = 0L
        frameActivityDetector.reset()
        visionRunning = true
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!visionRunning) return@setOnImageAvailableListener
                val now = SystemClock.elapsedRealtime()
                if (now - lastChangeCheckAt < CHANGE_CHECK_INTERVAL_MS) return@setOnImageAvailableListener
                lastChangeCheckAt = now
                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                if (plane.pixelStride != RGBA_PIXEL_STRIDE) return@setOnImageAvailableListener
                val sample = plane.sampleGrid(image.width, image.height)
                val analysisReason = frameActivityDetector.analysisReason(sample, now)
                    ?: return@setOnImageAvailableListener
                plane.buffer.rewind()
                val detections = visionEngine?.process(
                    rgbaBuffer = plane.buffer,
                    width = image.width,
                    height = image.height,
                    rowStride = plane.rowStride,
                    rotationDegrees = 0,
                    timestampNs = image.timestamp,
                ).orEmpty()
                if (!visionRunning) return@setOnImageAvailableListener
                val protectedDetections = protectionTracker.update(
                    detections,
                    contentChanged = analysisReason == FrameAnalysisReason.CONTENT_CHANGED,
                )
                plane.buffer.rewind()
                val bitmap = plane.toBitmap(image.width, image.height)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                if (cropped !== bitmap) bitmap.recycle()
                val rendered = FrameBlurRenderer.renderSelectedDetections(cropped, protectedDetections)
                deviceOverlay?.update(rendered.overlayRegions)
                    ?: rendered.overlayRegions.forEach { region -> region.bitmap.recycle() }
                updateDetectionStatus(detections, rendered.blurredCount)
                if (CaptureSessionStore.isPreviewRequested) {
                    val stream = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                    CaptureSessionStore.update(previewJpeg = stream.toByteArray())
                }
                cropped.recycle()
            } catch (error: Throwable) {
                CaptureSessionStore.update(
                    message = "Vision frame failed: ${error.message ?: error.javaClass.simpleName}",
                )
            } finally {
                image.close()
            }
        }, Handler(handlerThread.looper))
        imageReader = reader
        display = mediaProjection.createVirtualDisplay(
            "HalalifyPreview", width, height, resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null,
        )
    }

    private fun android.media.Image.Plane.toBitmap(width: Int, height: Int): Bitmap {
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        return Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.copyPixelsFromBuffer(buffer)
        }
    }

    private fun android.media.Image.Plane.sampleGrid(width: Int, height: Int): IntArray {
        val columns = SAMPLE_COLUMNS.coerceAtMost(width)
        val rows = SAMPLE_ROWS.coerceAtMost(height)
        val sample = IntArray(columns * rows)
        val source = buffer
        var outputIndex = 0
        for (row in 0 until rows) {
            val y = (((row + 0.5F) * height) / rows).toInt().coerceIn(0, height - 1)
            for (column in 0 until columns) {
                val x = (((column + 0.5F) * width) / columns).toInt().coerceIn(0, width - 1)
                val sourceIndex = y * rowStride + x * pixelStride
                val red = source.get(sourceIndex).toInt() and 0xFF
                val green = source.get(sourceIndex + 1).toInt() and 0xFF
                val blue = source.get(sourceIndex + 2).toInt() and 0xFF
                sample[outputIndex++] = red shl 16 or (green shl 8) or blue
            }
        }
        return sample
    }

    private fun updateDetectionStatus(detections: List<Detection>, blurredCount: Int) {
        val femaleCount = detections.count { it.classId == 0 }
        val maleCount = detections.count { it.classId == 1 }
        Log.d(TAG, "detections female=$femaleCount male=$maleCount blurred=$blurredCount")
        CaptureSessionStore.update(
            message = "Detected: $femaleCount female, $maleCount male - blurred: $blurredCount",
        )
    }

    private fun stopCapture(message: String) {
        visionRunning = false
        display?.release(); display = null
        imageReader?.close(); imageReader = null
        visionThread?.quitSafely(); visionThread = null
        visionEngine?.close(); visionEngine = null
        protectionTracker.reset()
        frameActivityDetector.reset()
        deviceOverlay?.close(); deviceOverlay = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop(); projection = null
        CaptureSessionStore.update(isCapturing = false, targetLabel = null, message = message, previewJpeg = null)
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.app.Notification.Builder(this, CHANNEL_ID)
        else android.app.Notification.Builder(this)
        notification.setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Halalify capture is active")
            .setContentText("Monitoring shared content").setOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification.build())
    }

    override fun onDestroy() { stopCapture("Capture stopped."); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PREPARE = "com.halalify.kotlin.PREPARE_CAPTURE"
        const val ACTION_START = "com.halalify.kotlin.START_CAPTURE"
        const val ACTION_STOP = "com.halalify.kotlin.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "halalify_capture"
        private const val NOTIFICATION_ID = 41
        private const val CAPTURE_WIDTH = 416
        private const val CHANGE_CHECK_INTERVAL_MS = 250L
        private const val JPEG_QUALITY = 70
        private const val RGBA_PIXEL_STRIDE = 4
        private const val SAMPLE_COLUMNS = 20
        private const val SAMPLE_ROWS = 32
        private const val TAG = "HalalifyVision"
    }
}
