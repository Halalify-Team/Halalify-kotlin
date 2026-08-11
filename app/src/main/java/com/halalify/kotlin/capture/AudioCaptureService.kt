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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import java.io.ByteArrayOutputStream

internal class AudioCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var imageReader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var audioThread: Thread? = null
    private var lastFrameAt = 0L
    @Volatile private var running = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture("Android stopped the capture session.")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
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
            startScreenPreview(mediaProjection)
            startAudioPassThrough(mediaProjection)
            CaptureSessionStore.update(
                isCapturing = true,
                targetLabel = "Shared app or screen",
                message = "Monitoring the content selected in Android's secure sharing dialog.",
            )
        } catch (error: Throwable) {
            stopCapture("Could not start capture: ${error.message ?: error.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun startAudioPassThrough(mediaProjection: MediaProjection) {
        val inputFormat = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val config = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val inputBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(BUFFER_BYTES)
        val audioRecord = AudioRecord.Builder().setAudioFormat(inputFormat).setBufferSizeInBytes(inputBuffer * 2)
            .setAudioPlaybackCaptureConfig(config).build()
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "Audio input could not be initialized." }

        val outputFormat = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val outputBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(BUFFER_BYTES)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(outputFormat).setBufferSizeInBytes(outputBuffer * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
        check(audioTrack.state == AudioTrack.STATE_INITIALIZED) { "Audio output could not be initialized." }

        recorder = audioRecord
        player = audioTrack
        running = true
        audioRecord.startRecording()
        audioTrack.play()
        audioThread = Thread({
            val buffer = ByteArray(BUFFER_BYTES)
            while (running) {
                val count = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) audioTrack.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING)
            }
        }, "halalify-audio").apply { start() }
    }

    private fun startScreenPreview(mediaProjection: MediaProjection) {
        val metrics = resources.displayMetrics
        val width = PREVIEW_WIDTH.coerceAtMost(metrics.widthPixels)
        val height = (metrics.heightPixels.toFloat() * width / metrics.widthPixels).toInt().coerceAtLeast(1)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < PREVIEW_INTERVAL_MS) return@setOnImageAvailableListener
                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val bitmap = Bitmap.createBitmap(
                    image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888,
                )
                plane.buffer.rewind()
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                if (cropped !== bitmap) bitmap.recycle()
                val stream = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                cropped.recycle()
                lastFrameAt = now
                CaptureSessionStore.update(previewJpeg = stream.toByteArray())
            } catch (_: Throwable) {
                // A preview decoding failure must not interrupt the audio session.
            } finally {
                image.close()
            }
        }, null)
        imageReader = reader
        display = mediaProjection.createVirtualDisplay(
            "HalalifyPreview", width, height, resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null,
        )
    }

    private fun stopCapture(message: String) {
        running = false
        audioThread?.interrupt(); audioThread = null
        runCatching { recorder?.stop() }; runCatching { player?.pause() }
        recorder?.release(); player?.release(); recorder = null; player = null
        display?.release(); display = null
        imageReader?.close(); imageReader = null
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
        const val ACTION_START = "com.halalify.kotlin.START_CAPTURE"
        const val ACTION_STOP = "com.halalify.kotlin.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "halalify_capture"
        private const val NOTIFICATION_ID = 41
        private const val SAMPLE_RATE = 48_000
        private const val BUFFER_BYTES = 8192
        private const val PREVIEW_WIDTH = 480
        private const val PREVIEW_INTERVAL_MS = 1_000L
        private const val JPEG_QUALITY = 70
    }
}
