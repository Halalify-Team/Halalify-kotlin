package com.halalify.kotlin.appselection

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

internal data class AppTarget(val label: String, val packageName: String, val uid: Int, val icon: Drawable)

internal fun loadLaunchableApps(context: Context): List<AppTarget> =
    context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        0,
    ).map { info ->
        val app = info.activityInfo.applicationInfo
        AppTarget(info.loadLabel(context.packageManager).toString(), app.packageName, app.uid, info.loadIcon(context.packageManager))
    }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
