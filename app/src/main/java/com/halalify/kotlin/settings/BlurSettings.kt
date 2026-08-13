package com.halalify.kotlin.settings

import android.content.Context
import androidx.core.content.edit

internal enum class BlurTarget(val title: String, val description: String) {
    FEMALE("Female", "Blur detections classified as female."),
    MALE("Male", "Blur detections classified as male."),
}

internal enum class BlurStyle(val title: String, val description: String) {
    PIXELATED("Pixelated", "Large, hard censor blocks for maximum visual concealment."),
}

internal data class BlurSettings(
    val target: BlurTarget = BlurTarget.FEMALE,
    val blurImages: Boolean = true,
    val blurVideos: Boolean = true,
    val style: BlurStyle = BlurStyle.PIXELATED,
    val isolateMusic: Boolean = false,
) {
    val hasVisualProtection: Boolean
        get() = blurImages || blurVideos

    val hasEnabledProtection: Boolean
        get() = hasVisualProtection || isolateMusic
}

internal class BlurSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): BlurSettings = BlurSettings(
        target = preferences.getString(KEY_TARGET, null)
            ?.let { savedValue -> BlurTarget.entries.firstOrNull { it.name == savedValue } }
            ?: BlurTarget.FEMALE,
        blurImages = preferences.getBoolean(KEY_BLUR_IMAGES, true),
        blurVideos = preferences.getBoolean(KEY_BLUR_VIDEOS, true),
        style = preferences.getString(KEY_BLUR_STYLE, null)
            ?.let { savedValue -> BlurStyle.entries.firstOrNull { it.name == savedValue } }
            ?: BlurStyle.PIXELATED,
        isolateMusic = preferences.getBoolean(KEY_ISOLATE_MUSIC, false),
    )

    fun save(settings: BlurSettings) {
        preferences.edit {
            putString(KEY_TARGET, settings.target.name)
            putBoolean(KEY_BLUR_IMAGES, settings.blurImages)
            putBoolean(KEY_BLUR_VIDEOS, settings.blurVideos)
            putString(KEY_BLUR_STYLE, settings.style.name)
            putBoolean(KEY_ISOLATE_MUSIC, settings.isolateMusic)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "blur_settings"
        const val KEY_TARGET = "target"
        const val KEY_BLUR_IMAGES = "blur_images"
        const val KEY_BLUR_VIDEOS = "blur_videos"
        const val KEY_BLUR_STYLE = "blur_style"
        const val KEY_ISOLATE_MUSIC = "isolate_music"
    }
}
