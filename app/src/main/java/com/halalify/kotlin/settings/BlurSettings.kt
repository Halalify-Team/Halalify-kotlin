package com.halalify.kotlin.settings

import android.content.Context
import androidx.core.content.edit

internal enum class BlurTarget(val title: String, val description: String) {
    FEMALE("Female", "Blur detections classified as female."),
    MALE("Male", "Blur detections classified as male."),
}

internal enum class BlurStyle(val title: String, val description: String) {
    PIXELATED("Pixelated", "Small, hard censor blocks focused on the detected subject."),
}

// Start with a deliberately strong privacy-preserving effect. Existing saved
// values below this floor are also raised when settings are loaded.
internal const val MIN_BLUR_INTENSITY = 0.7f

internal fun normalizeBlurIntensity(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MIN_BLUR_INTENSITY, 1f) else MIN_BLUR_INTENSITY

internal data class BlurSettings(
    val target: BlurTarget = BlurTarget.FEMALE,
    val blurImages: Boolean = true,
    val blurVideos: Boolean = true,
    val style: BlurStyle = BlurStyle.PIXELATED,
    val isolateMusic: Boolean = false,
    val blockAdultSites: Boolean = false,
    val musicSourceUrl: String = "",
    val musicSourceFileName: String = "",
    val musicSourceUri: String = "",
    /** Intensity of the blur effect.
     * 0f = no blur (lightest), 1f = maximum blur (heaviest).
     */
    val intensity: Float = MIN_BLUR_INTENSITY,
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
        style = preferences.getString(KEY_BLUR_STYLE, null)
            ?.let { savedValue -> BlurStyle.entries.firstOrNull { it.name == savedValue } }
            ?: BlurStyle.PIXELATED,
        isolateMusic = preferences.getBoolean(KEY_ISOLATE_MUSIC, false),
        blockAdultSites = preferences.getBoolean(KEY_BLOCK_ADULT_SITES, false),
        musicSourceUrl = preferences.getString(KEY_MUSIC_SOURCE_URL, "") ?: "",
        musicSourceFileName = preferences.getString(KEY_MUSIC_SOURCE_FILE_NAME, "") ?: "",
        musicSourceUri = preferences.getString(KEY_MUSIC_SOURCE_URI, "") ?: "",
        intensity = normalizeBlurIntensity(
            preferences.getFloat(KEY_BLUR_INTENSITY, MIN_BLUR_INTENSITY),
        ),
    )

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
            putFloat(KEY_BLUR_INTENSITY, normalizeBlurIntensity(settings.intensity))
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "blur_settings"
        const val KEY_TARGET = "target"
        const val KEY_BLUR_IMAGES = "blur_images"
        const val KEY_BLUR_VIDEOS = "blur_videos"
        const val KEY_BLUR_STYLE = "blur_style"
        const val KEY_ISOLATE_MUSIC = "isolate_music"
        const val KEY_BLOCK_ADULT_SITES = "block_adult_sites"
        const val KEY_MUSIC_SOURCE_URL = "music_source_url"
        const val KEY_MUSIC_SOURCE_FILE_NAME = "music_source_file_name"
        const val KEY_MUSIC_SOURCE_URI = "music_source_uri"
        const val KEY_BLUR_INTENSITY = "blur_intensity"
    }
}
