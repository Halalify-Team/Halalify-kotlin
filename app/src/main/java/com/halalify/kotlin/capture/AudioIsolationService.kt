package com.halalify.kotlin.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.halalify.kotlin.audio.MediaIsolationProcessor
import com.halalify.kotlin.audio.MediaIsolationRequest
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground worker for processing a selected media file without blocking the activity UI. */
internal class AudioIsolationService : Service() {
    private var worker: Thread? = null
    private val stopping = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping.set(true)
                worker?.interrupt()
                stopSelfResult(startId)
            }
            ACTION_START -> startIsolation(intent, startId)
        }
        return START_NOT_STICKY
    }

    private fun startIsolation(intent: Intent, startId: Int) {
        if (worker?.isAlive == true) {
            CaptureSessionStore.updateState { current ->
                current.copy(audioStatus = "Isolation is already processing another file.")
            }
            return
        }

        val localUri = intent.getStringExtra(EXTRA_LOCAL_URI)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
        val remoteUrl = intent.getStringExtra(EXTRA_REMOTE_URL)?.takeIf { it.isNotBlank() }
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "media"
        if (localUri == null && remoteUrl == null) {
            CaptureSessionStore.updateState { current ->
                current.copy(audioStatus = "Choose a media file or enter a direct media URL first.")
            }
            stopSelfResult(startId)
            return
        }

        stopping.set(false)
        try {
            startForegroundNotification()
        } catch (error: Exception) {
            CaptureSessionStore.updateState { current ->
                current.copy(
                    message = "Could not start media isolation.",
                    audioStatus = error.message ?: error.javaClass.simpleName,
                )
            }
            stopSelfResult(startId)
            return
        }
        CaptureSessionStore.updateState { current ->
            current.copy(
                message = "Music isolation is processing the selected media.",
                audioStatus = "Isolation: preparing source...",
            )
        }
        worker = Thread({
            try {
                val result = MediaIsolationProcessor(applicationContext) { progress ->
                    CaptureSessionStore.updateState { current ->
                        current.copy(audioStatus = "Isolation: $progress%")
                    }
                    updateNotification("Removing music: $progress%")
                }.process(
                    MediaIsolationRequest(
                        localUri = localUri,
                        remoteUrl = remoteUrl,
                        displayName = displayName,
                    ),
                )
                if (!stopping.get()) {
                    CaptureSessionStore.updateState { current ->
                        current.copy(
                            message = "Isolation complete. The music-free file was saved to Movies/Music > Halalify.",
                            audioStatus = "Ready: ${result.outputName}",
                        )
                    }
                    updateNotification("Saved ${result.outputName}")
                }
            } catch (error: Throwable) {
                if (!stopping.get()) {
                    CaptureSessionStore.updateState { current ->
                        current.copy(
                            message = "Isolation failed.",
                            audioStatus = error.message ?: error.javaClass.simpleName,
                        )
                    }
                    updateNotification("Isolation failed")
                }
            } finally {
                worker = null
                stopSelfResult(startId)
            }
        }, "halalify-media-isolation").also { it.start() }
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Media isolation", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = buildNotification("Preparing media isolation")
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(content),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(content: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Halalify media isolation")
            .setContentText(content)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopping.set(true)
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.halalify.kotlin.START_MEDIA_ISOLATION"
        const val ACTION_STOP = "com.halalify.kotlin.STOP_MEDIA_ISOLATION"
        const val EXTRA_LOCAL_URI = "local_uri"
        const val EXTRA_REMOTE_URL = "remote_url"
        const val EXTRA_DISPLAY_NAME = "display_name"
        private const val CHANNEL_ID = "halalify_media_isolation"
        private const val NOTIFICATION_ID = 42
    }
}
