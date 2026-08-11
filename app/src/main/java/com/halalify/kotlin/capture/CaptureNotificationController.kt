package com.halalify.kotlin.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build

/** Owns notification-channel and foreground-notification concerns for the capture service. */
internal class CaptureNotificationController(
    private val service: Service,
) {
    private val manager = service.getSystemService(NotificationManager::class.java)

    fun start(content: String = DEFAULT_CONTENT) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = build(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun update(content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            service.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(NOTIFICATION_ID, build(content))
    }

    @Suppress("DEPRECATION")
    private fun build(content: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(service, CHANNEL_ID)
        } else {
            Notification.Builder(service)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(content)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "halalify_capture"
        const val CHANNEL_NAME = "Capture"
        const val NOTIFICATION_ID = 41
        const val NOTIFICATION_TITLE = "Halalify capture is active"
        const val DEFAULT_CONTENT = "Monitoring shared content"
    }
}
