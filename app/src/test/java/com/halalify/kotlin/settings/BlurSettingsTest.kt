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
}
