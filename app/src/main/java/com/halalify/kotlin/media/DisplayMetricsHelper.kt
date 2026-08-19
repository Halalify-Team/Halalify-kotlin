package com.halalify.kotlin.media

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager

internal fun Context.getRealDisplayBounds(): Rect {
    val windowManager = getSystemService(WindowManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
        Rect(windowManager.currentWindowMetrics.bounds)
    } else {
        @Suppress("DEPRECATION")
        val display = windowManager?.defaultDisplay
        if (display != null) {
            val point = Point()
            display.getRealSize(point)
            Rect(0, 0, point.x, point.y)
        } else {
            Rect(
                0,
                0,
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
            )
        }
    }
}
