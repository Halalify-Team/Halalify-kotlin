package com.halalify.kotlin.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurSettingsTest {
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
