package com.halalify.kotlin.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplacementRefreshPolicyTest {
    @Test
    fun `fresh partial observation retains moved protection`() {
        assertEquals(
            ReplacementRefreshAction.KEEP,
            decideReplacementRefreshAction(
                analysesRemaining = 6,
                hasProtectedObservation = false,
            ),
        )
        assertEquals(
            ReplacementRefreshAction.INACTIVE,
            decideReplacementRefreshAction(
                analysesRemaining = 2,
                hasProtectedObservation = true,
            ),
        )
    }

    @Test
    fun `target-free replacement expires only on the final analysis`() {
        assertEquals(
            ReplacementRefreshAction.EXPIRE,
            decideReplacementRefreshAction(
                analysesRemaining = 1,
                hasProtectedObservation = false,
            ),
        )
        assertEquals(
            ReplacementRefreshAction.INACTIVE,
            decideReplacementRefreshAction(
                analysesRemaining = 0,
                hasProtectedObservation = false,
            ),
        )
    }
}
