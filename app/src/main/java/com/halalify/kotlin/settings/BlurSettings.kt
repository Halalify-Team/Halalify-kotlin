package com.halalify.kotlin.settings

import android.content.Context

internal enum class BlurTarget(val title: String, val description: String) {
    FEMALE("Female", "Blur detections classified as female."),
    MALE("Male", "Blur detections classified as male."),
}

internal data class BlurSettings(
    val target: BlurTarget = BlurTarget.FEMALE,
    val blurImages: Boolean = true,
    val blurVideos: Boolean = true,
) {
    /** Model mapping verified in the extracted web integration: a=female, b=male. */
    fun shouldBlurLabel(label: String): Boolean = when (label.lowercase()) {
        "a" -> target == BlurTarget.FEMALE
        "b" -> target == BlurTarget.MALE
        else -> false
    }
}

internal class BlurSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): BlurSettings = BlurSettings(
        target = preferences.getString(KEY_TARGET, null)
            ?.let { savedValue -> BlurTarget.entries.firstOrNull { it.name == savedValue } }
            ?: BlurTarget.FEMALE,
        blurImages = preferences.getBoolean(KEY_BLUR_IMAGES, true),
        blurVideos = preferences.getBoolean(KEY_BLUR_VIDEOS, true),
    )

    fun save(settings: BlurSettings) {
        preferences.edit()
            .putString(KEY_TARGET, settings.target.name)
            .putBoolean(KEY_BLUR_IMAGES, settings.blurImages)
            .putBoolean(KEY_BLUR_VIDEOS, settings.blurVideos)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "blur_settings"
        const val KEY_TARGET = "target"
        const val KEY_BLUR_IMAGES = "blur_images"
        const val KEY_BLUR_VIDEOS = "blur_videos"
    }
}
