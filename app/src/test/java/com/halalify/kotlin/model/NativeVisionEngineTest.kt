package com.halalify.kotlin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVisionEngineTest {
    @Test
    fun nativeValuesAreConvertedToDetections() {
        val detections = floatArrayOf(
            0.1F, 0.2F, 0.8F, 0.9F, 0.89F, 0F, 1F, 1F,
        ).asListOfDetections()

        assertEquals(1, detections.size)
        assertEquals("female", detections.single().label)
        assertTrue(detections.single().shouldBlur)
        assertTrue(detections.single().isNsfw)
    }
}
