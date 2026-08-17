package com.halalify.kotlin.capture

import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.halalify.kotlin.audio.AudioMonitorFactory
import com.halalify.kotlin.audio.AudioProtectionSession
import com.halalify.kotlin.audio.BundledAudioProcessorProvider
import com.halalify.kotlin.audio.PlaybackAudioFocusController
import com.halalify.kotlin.audio.PlaybackAudioMonitor
import com.halalify.kotlin.media.DeviceBlurOverlay
import com.halalify.kotlin.model.NativeVisionEngine
import com.halalify.kotlin.settings.BlurSettings
import com.halalify.kotlin.settings.BlurSettingsRepository

/** Android entry point that coordinates visual and audio protection session lifecycles. */
internal class ProtectionCaptureService : Service() {
    private lateinit var notifications: CaptureNotificationController
    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var screenSession: ScreenProtectionSession? = null
    private var audioSession: AudioProtectionSession? = null
    private var stopping = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture("Android stopped the capture session.")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = CaptureNotificationController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> publishMessage(
                "Choose the app or screen to protect in Android's sharing dialog.",
            )

            ACTION_START -> startCapture(intent)
            ACTION_STOP -> {
                stopCapture("Capture stopped.")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        stopCapture("Preparing capture session.")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            publishMessage("This feature needs Android 10 or newer.")
            stopSelf()
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, INVALID_RESULT_CODE)

        @Suppress("DEPRECATION")
        val projectionData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        if (resultCode == INVALID_RESULT_CODE || projectionData == null) {
            publishMessage("Capture session data is invalid.")
            stopSelf()
            return
        }

        notifications.start()
        try {
            val activeProjection = checkNotNull(
                getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(resultCode, projectionData),
            ) { "MediaProjection was not created." }
            activeProjection.registerCallback(projectionCallback, mainHandler)
            projection = activeProjection

            val settings = BlurSettingsRepository(applicationContext).load()
            check(settings.hasEnabledProtection) { "No protection type is enabled." }
            if (settings.hasVisualProtection) {
                check(Settings.canDrawOverlays(this)) {
                    "Display-over-other-apps permission is required for device-level blur."
                }
                startScreenProtection(activeProjection, settings)
            }
            if (settings.isolateMusic) startAudioProtection(activeProjection)

            CaptureSessionStore.updateState { current ->
                current.copy(
                    isCapturing = true,
                    targetLabel = settings.targetLabel(),
                    message = settings.startedMessage(),
                )
            }
        } catch (error: Exception) {
            stopCapture(
                "Could not start capture: ${error.message ?: error.javaClass.simpleName}",
            )
            stopSelf()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startScreenProtection(
        activeProjection: MediaProjection,
        settings: BlurSettings,
    ) {
        val processor = NativeVisionEngine(applicationContext, settings.target)
        val overlay = try {
            DeviceBlurOverlay(applicationContext) { message ->
                publishMessage("Device blur overlay failed: $message")
            }
        } catch (error: Exception) {
            processor.close()
            throw error
        }
        val session = ScreenProtectionSession(
            resources = resources,
            mediaProjection = activeProjection,
            settings = settings,
            visionProcessor = processor,
            overlay = overlay,
            statePublisher = CaptureSessionStore,
        )
        screenSession = session
        session.start()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startAudioProtection(activeProjection: MediaProjection) {
        val session = AudioProtectionSession(
            statePublisher = CaptureSessionStore,
            processorProvider = BundledAudioProcessorProvider(applicationContext),
            monitorFactory = AudioMonitorFactory { processor, onMusicDetected, onEvent ->
                PlaybackAudioMonitor(
                    mediaProjection = activeProjection,
                    processor = processor,
                    onMusicDetected = onMusicDetected,
                    onEvent = onEvent,
                )
            },
            musicBlocker = PlaybackAudioFocusController(applicationContext),
            updateNotification = notifications::update,
        )
        audioSession = session
        session.start()
    }

    private fun BlurSettings.targetLabel(): String? =
        if (hasVisualProtection) "Blur target: ${target.title}" else null

    private fun BlurSettings.startedMessage(): String = when {
        hasVisualProtection && isolateMusic ->
            "Device-level blur and music protection are active over the shared screen."

        hasVisualProtection -> "Device-level blur is active over the shared screen."
        else -> "Music protection is active for eligible playback."
    }

    private fun publishMessage(message: String) {
        CaptureSessionStore.updateState { current -> current.copy(message = message) }
    }

    private fun stopCapture(message: String) {
        if (stopping) return
        stopping = true
        try {
            closeResource("audio session") { audioSession?.close() }
            audioSession = null
            closeResource("screen session") { screenSession?.close() }
            screenSession = null

            val activeProjection = projection
            projection = null
            closeResource("projection callback") {
                activeProjection?.unregisterCallback(projectionCallback)
            }
            closeResource("media projection") { activeProjection?.stop() }

            CaptureSessionStore.updateState { current ->
                current.copy(
                    isCapturing = false,
                    targetLabel = null,
                    message = message,
                    audioStatus = null,
                    previewJpeg = null,
                )
            }
        } finally {
            stopping = false
        }
    }

    private inline fun closeResource(name: String, close: () -> Unit) {
        try {
            close()
        } catch (error: Exception) {
            Log.w(TAG, "Could not close $name.", error)
        }
    }

    override fun onDestroy() {
        stopCapture("Capture stopped.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PREPARE = "com.halalify.kotlin.PREPARE_CAPTURE"
        const val ACTION_START = "com.halalify.kotlin.START_CAPTURE"
        const val ACTION_STOP = "com.halalify.kotlin.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val INVALID_RESULT_CODE = Int.MIN_VALUE
        private const val TAG = "HalalifyCapture"
    }
}
