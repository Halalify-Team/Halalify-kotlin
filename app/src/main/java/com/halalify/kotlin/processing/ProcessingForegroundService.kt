package com.halalify.kotlin.processing

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.halalify.kotlin.R
import com.halalify.kotlin.model.ProcessingState

internal class ProcessingForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FINISH -> {
                val notification = buildNotification(
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Halalify video ready" },
                    text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "Complete. Ready to watch." },
                    total = 1,
                    completed = 1,
                    indeterminate = false,
                    ongoing = false,
                )
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, notification)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
                    .ifBlank { "Halalify is processing" }
                val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
                    .ifBlank { "Preparing chunks..." }
                val total = intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0
                val completed = intent?.getIntExtra(EXTRA_COMPLETED, 0) ?: 0
                val notification = buildNotification(
                    title = title,
                    text = text,
                    total = total.coerceAtLeast(1),
                    completed = completed.coerceAtLeast(0),
                    indeterminate = total <= 0,
                    ongoing = true,
                )
                startForeground(NOTIFICATION_ID, notification)
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(
        title: String,
        text: String,
        total: Int,
        completed: Int,
        indeterminate: Boolean,
        ongoing: Boolean,
    ): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(total, completed.coerceAtMost(total), indeterminate)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Video processing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress while Halalify downloads and cleans video chunks."
        }
        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "halalify_processing"
        private const val NOTIFICATION_ID = 2401
        private const val ACTION_PROGRESS = "com.halalify.kotlin.processing.PROGRESS"
        private const val ACTION_FINISH = "com.halalify.kotlin.processing.FINISH"
        private const val ACTION_STOP = "com.halalify.kotlin.processing.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TOTAL = "total"
        private const val EXTRA_COMPLETED = "completed"

        fun update(context: Context, state: ProcessingState) {
            if (!canRunForegroundNotification(context)) return
            val total = state.totalChunks.coerceAtLeast(1)
            val completed = state.completedChunks.coerceIn(0, total)
            val text = when {
                completed > 0 -> "Ready $completed/$total chunks"
                else -> state.currentPhaseLabel.ifBlank { "Preparing chunks..." }
            }
            val intent = Intent(context, ProcessingForegroundService::class.java)
                .setAction(ACTION_PROGRESS)
                .putExtra(EXTRA_TITLE, state.videoTitle.ifBlank { "Halalify is processing" })
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_TOTAL, state.totalChunks)
                .putExtra(EXTRA_COMPLETED, state.completedChunks)
            ContextCompat.startForegroundService(context, intent)
        }

        fun finish(context: Context, state: ProcessingState) {
            if (!canRunForegroundNotification(context)) return
            val isFailure = state.errorMessage != null
            val intent = Intent(context, ProcessingForegroundService::class.java)
                .setAction(ACTION_FINISH)
                .putExtra(
                    EXTRA_TITLE,
                    if (isFailure) "Halalify processing failed" else "Halalify video ready",
                )
                .putExtra(
                    EXTRA_TEXT,
                    if (isFailure) {
                        state.errorMessage?.take(180) ?: "Processing stopped."
                    } else {
                        state.videoTitle.ifBlank { "Complete. Ready to watch." }
                    },
                )
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProcessingForegroundService::class.java)
                .setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        private fun canRunForegroundNotification(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
