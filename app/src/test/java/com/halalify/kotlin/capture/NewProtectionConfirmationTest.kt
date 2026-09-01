package com.halalify.kotlin.capture

import com.halalify.kotlin.model.Detection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewProtectionConfirmationTest {
    private val protected = Detection(
        x1 = 0.20F,
        y1 = 0.20F,
        x2 = 0.60F,
        y2 = 0.80F,
        confidence = 0.45F,
        classId = 0,
        shouldBlur = true,
        isNsfw = false,
    )

    @Test
    fun `isolated new result does not create protection`() {
        val confirmation = NewProtectionConfirmation()

        val result = confirmation.apply(listOf(protected), confirmationRequired = true)

        assertFalse(result.single().shouldBlur)
    }

    @Test
    fun `matching consecutive results confirm protection`() {
        val confirmation = NewProtectionConfirmation()
        confirmation.apply(listOf(protected), confirmationRequired = true)

        val result = confirmation.apply(
            listOf(protected.copy(x1 = 0.22F, x2 = 0.62F)),
            confirmationRequired = true,
        )

        assertTrue(result.single().shouldBlur)
    }

    @Test
    fun `strong new result creates protection immediately`() {
        val confirmation = NewProtectionConfirmation()

        val result = confirmation.apply(
            listOf(protected.copy(confidence = 0.80F)),
            confirmationRequired = true,
        )

        assertTrue(result.single().shouldBlur)
    }

    @Test
    fun `a clean frame resets an unconfirmed candidate`() {
        val confirmation = NewProtectionConfirmation()
        confirmation.apply(listOf(protected), confirmationRequired = true)
        confirmation.apply(emptyList(), confirmationRequired = true)

        val result = confirmation.apply(listOf(protected), confirmationRequired = true)

        assertFalse(result.single().shouldBlur)
    }

    @Test
    fun `existing protection bypasses candidate confirmation`() {
        val confirmation = NewProtectionConfirmation()

        val result = confirmation.apply(listOf(protected), confirmationRequired = false)

        assertTrue(result.single().shouldBlur)
    }

    @Test
    fun `non-initial observations protect without another slow pass`() {
        assertFalse(
            requiresNewProtectionConfirmation(
                hasExistingProtection = false,
                reason = FrameAnalysisReason.STABILIZATION,
            ),
        )
        assertFalse(
            requiresNewProtectionConfirmation(
                hasExistingProtection = false,
                reason = FrameAnalysisReason.CONTENT_CHANGED,
            ),
        )
        assertFalse(
            requiresNewProtectionConfirmation(
                hasExistingProtection = false,
                reason = FrameAnalysisReason.SAFETY_REFRESH,
            ),
        )
        assertTrue(
            requiresNewProtectionConfirmation(
                hasExistingProtection = false,
                reason = FrameAnalysisReason.INITIAL,
            ),
        )
    }
}
