package com.halalify.kotlin.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurSettingsTest {

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
}
