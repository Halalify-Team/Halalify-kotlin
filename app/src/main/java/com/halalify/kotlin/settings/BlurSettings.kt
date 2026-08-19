package com.halalify.kotlin.settings

import android.content.Context
import androidx.core.content.edit

internal enum class BlurTarget(val title: String, val description: String) {
    FEMALE("Female", "Blur detections classified as female."),
    MALE("Male", "Blur detections classified as male."),
}

internal enum class BlurStyle(val title: String, val description: String) {
    SOFT_BLUR("Soft blur", "A smooth, low-detail blur that blends the protected area."),
    PIXELATED("Pixelated", "Small, hard censor blocks focused on the detected subject."),
    SOLID("Solid", "A fully opaque black cover for maximum privacy."),
}

internal enum class AppThemeMode(val title: String, val description: String) {
    NORMAL("Normal", "Follow your phone's light or dark appearance."),
    DARK("Dark", "Always use the deep forest theme."),
    LIGHT("Light", "Always use the bright forest theme."),
}

// Allow the full intensity range so each of the five slider levels
// produces a meaningfully different mosaic density (16 columns at 0f
// down to 4 columns at 1f).
internal const val MIN_BLUR_INTENSITY = 0f
internal const val DEFAULT_BLUR_INTENSITY = 1f

internal fun normalizeBlurIntensity(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MIN_BLUR_INTENSITY, 1f) else 1f

internal data class BlurSettings(
    val target: BlurTarget = BlurTarget.FEMALE,
    val blurImages: Boolean = true,
    val blurVideos: Boolean = true,
    val style: BlurStyle = BlurStyle.SOFT_BLUR,
    val isolateMusic: Boolean = false,
    val blockAdultSites: Boolean = false,
    val musicSourceUrl: String = "",
    val musicSourceFileName: String = "",
    val musicSourceUri: String = "",
    val themeMode: AppThemeMode = AppThemeMode.NORMAL,
    /** Intensity of the blur effect.
     * 0f = no blur (lightest), 1f = maximum blur (heaviest).
     */
    val intensity: Float = DEFAULT_BLUR_INTENSITY,
) {
    val hasVisualProtection: Boolean
        get() = blurImages || blurVideos

    val hasMusicIsolationSource: Boolean
        get() = musicSourceUrl.isNotBlank() || musicSourceFileName.isNotBlank() || musicSourceUri.isNotBlank()

    val hasEnabledProtection: Boolean
        get() = hasVisualProtection || isolateMusic

    fun shouldBlurLabel(label: String): Boolean {
        val normalized = label.trim().lowercase()
        return when (target) {
            BlurTarget.FEMALE -> normalized == "female" || normalized == "a" || normalized.contains("female")
            BlurTarget.MALE -> normalized == "male" || normalized == "b" || normalized.contains("male")
        }
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
        style = loadStyle(),
        isolateMusic = preferences.getBoolean(KEY_ISOLATE_MUSIC, false),
        blockAdultSites = preferences.getBoolean(KEY_BLOCK_ADULT_SITES, false),
        musicSourceUrl = preferences.getString(KEY_MUSIC_SOURCE_URL, "") ?: "",
        musicSourceFileName = preferences.getString(KEY_MUSIC_SOURCE_FILE_NAME, "") ?: "",
        musicSourceUri = preferences.getString(KEY_MUSIC_SOURCE_URI, "") ?: "",
        themeMode = preferences.getString(KEY_THEME_MODE, null)
            ?.let { savedValue -> AppThemeMode.entries.firstOrNull { it.name == savedValue } }
            ?: AppThemeMode.NORMAL,
        intensity = loadIntensity(),
    )

    private fun loadIntensity(): Float {
        val revision = preferences.getInt(KEY_BLUR_SETTINGS_REVISION, 0)
        if (revision < CURRENT_BLUR_SETTINGS_REVISION) return DEFAULT_BLUR_INTENSITY
        return normalizeBlurIntensity(
            preferences.getFloat(KEY_BLUR_INTENSITY, DEFAULT_BLUR_INTENSITY),
        )
    }

    private fun loadStyle(): BlurStyle {
        val revision = preferences.getInt(KEY_BLUR_SETTINGS_REVISION, 0)
        if (revision < CURRENT_BLUR_SETTINGS_REVISION) return BlurStyle.SOFT_BLUR
        return preferences.getString(KEY_BLUR_STYLE, null)
            ?.let { savedValue -> BlurStyle.entries.firstOrNull { it.name == savedValue } }
            ?: BlurStyle.SOFT_BLUR
    }

    fun save(settings: BlurSettings) {
        preferences.edit {
            putString(KEY_TARGET, settings.target.name)
            putBoolean(KEY_BLUR_IMAGES, settings.blurImages)
            putBoolean(KEY_BLUR_VIDEOS, settings.blurVideos)
            putString(KEY_BLUR_STYLE, settings.style.name)
            putBoolean(KEY_ISOLATE_MUSIC, settings.isolateMusic)
            putBoolean(KEY_BLOCK_ADULT_SITES, settings.blockAdultSites)
            putString(KEY_MUSIC_SOURCE_URL, settings.musicSourceUrl)
            putString(KEY_MUSIC_SOURCE_FILE_NAME, settings.musicSourceFileName)
            putString(KEY_MUSIC_SOURCE_URI, settings.musicSourceUri)
            putString(KEY_THEME_MODE, settings.themeMode.name)
            putFloat(KEY_BLUR_INTENSITY, normalizeBlurIntensity(settings.intensity))
            putInt(KEY_BLUR_SETTINGS_REVISION, CURRENT_BLUR_SETTINGS_REVISION)
        }
    }

    private companion object {
        private const val PREFERENCES_NAME = "halalify_blur_settings"
        private const val KEY_TARGET = "target"
        private const val KEY_BLUR_IMAGES = "blur_images"
        private const val KEY_BLUR_VIDEOS = "blur_videos"
        private const val KEY_BLUR_STYLE = "blur_style"
        private const val KEY_ISOLATE_MUSIC = "isolate_music"
        private const val KEY_BLOCK_ADULT_SITES = "block_adult_sites"
        private const val KEY_MUSIC_SOURCE_URL = "music_source_url"
        private const val KEY_MUSIC_SOURCE_FILE_NAME = "music_source_file_name"
        private const val KEY_MUSIC_SOURCE_URI = "music_source_uri"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BLUR_INTENSITY = "blur_intensity"
        const val KEY_BLUR_SETTINGS_REVISION = "blur_settings_revision"
        const val CURRENT_BLUR_SETTINGS_REVISION = 5
    }
}
