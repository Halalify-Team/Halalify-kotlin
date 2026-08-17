package com.halalify.kotlin.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurSettingsTest {

    @Test
    fun `new installations default to maximum blur strength`() {
        assertEquals(DEFAULT_BLUR_INTENSITY, BlurSettings().intensity, 0f)
        assertEquals(1f, BlurSettings().intensity, 0f)
    }

    @Test
    fun `invalid blur intensity is replaced with a safe value`() {
        assertEquals(1f, normalizeBlurIntensity(Float.NaN), 0f)
        assertEquals(1f, normalizeBlurIntensity(2f), 0f)
        assertEquals(0f, normalizeBlurIntensity(-1f), 0f)
    }

    @Test
    fun `female target blurs only the female model label`() {
        val settings = BlurSettings(target = BlurTarget.FEMALE)

        assertTrue(settings.shouldBlurLabel("a"))
        assertFalse(settings.shouldBlurLabel("b"))
        assertFalse(settings.shouldBlurLabel("c"))
    }

    @Test
    fun `male target blurs only the male model label`() {
        val settings = BlurSettings(target = BlurTarget.MALE)

        assertFalse(settings.shouldBlurLabel("a"))
        assertTrue(settings.shouldBlurLabel("b"))
    }

    @Test
    fun `unknown model labels are never blurred`() {
        val femaleSettings = BlurSettings(target = BlurTarget.FEMALE)
        val maleSettings = BlurSettings(target = BlurTarget.MALE)

        assertFalse(femaleSettings.shouldBlurLabel("c"))
        assertFalse(maleSettings.shouldBlurLabel("unknown"))
    }

    @Test
    fun `visual protection is enabled by either visual media option`() {
        assertTrue(BlurSettings(blurImages = true, blurVideos = false).hasVisualProtection)
        assertTrue(BlurSettings(blurImages = false, blurVideos = true).hasVisualProtection)
        assertFalse(BlurSettings(blurImages = false, blurVideos = false).hasVisualProtection)
    }

    @Test
    fun `audio-only settings still enable protection`() {
        val settings = BlurSettings(
            blurImages = false,
            blurVideos = false,
            isolateMusic = true,
        )

        assertFalse(settings.hasVisualProtection)
        assertTrue(settings.hasEnabledProtection)
    }

    @Test
    fun `settings with all coverage disabled cannot start protection`() {
        val settings = BlurSettings(
            blurImages = false,
            blurVideos = false,
            isolateMusic = false,
        )

        assertFalse(settings.hasEnabledProtection)
    }

    @Test
    fun `music isolation source supports url and file placeholders`() {
        val settings = BlurSettings(
            isolateMusic = true,
            musicSourceUrl = "https://youtu.be/example",
            musicSourceFileName = "demo_audio.mp3",
        )

        assertTrue(settings.isolateMusic)
        assertTrue(settings.musicSourceUrl.isNotBlank())
        assertTrue(settings.musicSourceFileName.isNotBlank())
        assertTrue(settings.hasMusicIsolationSource)
    }
}
