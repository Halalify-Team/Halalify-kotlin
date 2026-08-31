package com.halalify.kotlin.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayRegionLayoutTest {
    @Test
    fun `padding between vertically adjacent cards never overlaps`() {
        val firstRaw = OverlayBounds(80, 100, 400, 350)
        val secondRaw = OverlayBounds(220, 380, 420, 600)

        val resolved = resolvePaddingOnlyOverlaps(
            listOf(
                PaddedOverlayBounds(firstRaw, OverlayBounds(70, 90, 410, 370)),
                PaddedOverlayBounds(secondRaw, OverlayBounds(210, 360, 430, 610)),
            ),
        )

        assertFalse(resolved[0].intersects(resolved[1]))
        assertEquals(365, resolved[0].bottom)
        assertEquals(365, resolved[1].top)
        assertTrue(resolved[0].contains(firstRaw))
        assertTrue(resolved[1].contains(secondRaw))
    }

    @Test
    fun `padding between horizontally adjacent cards never overlaps`() {
        val firstRaw = OverlayBounds(100, 80, 350, 400)
        val secondRaw = OverlayBounds(380, 220, 600, 420)

        val resolved = resolvePaddingOnlyOverlaps(
            listOf(
                PaddedOverlayBounds(firstRaw, OverlayBounds(90, 70, 370, 410)),
                PaddedOverlayBounds(secondRaw, OverlayBounds(360, 210, 610, 430)),
            ),
        )

        assertFalse(resolved[0].intersects(resolved[1]))
        assertEquals(365, resolved[0].right)
        assertEquals(365, resolved[1].left)
        assertTrue(resolved[0].contains(firstRaw))
        assertTrue(resolved[1].contains(secondRaw))
    }

    @Test
    fun `genuinely overlapping detector boxes remain fully protected`() {
        val firstRaw = OverlayBounds(80, 100, 400, 390)
        val secondRaw = OverlayBounds(220, 380, 420, 600)
        val firstPadded = OverlayBounds(70, 90, 410, 410)
        val secondPadded = OverlayBounds(210, 360, 430, 610)

        val resolved = resolvePaddingOnlyOverlaps(
            listOf(
                PaddedOverlayBounds(firstRaw, firstPadded),
                PaddedOverlayBounds(secondRaw, secondPadded),
            ),
        )

        assertEquals(firstPadded, resolved[0])
        assertEquals(secondPadded, resolved[1])
        assertTrue(resolved[0].intersects(resolved[1]))
    }
}
