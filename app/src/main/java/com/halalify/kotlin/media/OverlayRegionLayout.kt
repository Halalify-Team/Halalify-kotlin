package com.halalify.kotlin.media

internal data class OverlayBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun intersects(other: OverlayBounds): Boolean =
        left < other.right && other.left < right &&
            top < other.bottom && other.top < bottom

    fun contains(other: OverlayBounds): Boolean =
        left <= other.left && top <= other.top &&
            right >= other.right && bottom >= other.bottom
}

internal data class PaddedOverlayBounds(
    val raw: OverlayBounds,
    val padded: OverlayBounds,
)

/**
 * Detector boxes can be close without touching (for example adjacent image
 * cards). Their safety padding must share the empty gap instead of creating
 * two opaque windows over the same pixels. Real detector-box overlaps are
 * retained because they may represent genuinely overlapping people.
 */
internal fun resolvePaddingOnlyOverlaps(
    regions: List<PaddedOverlayBounds>,
): List<OverlayBounds> {
    val resolved = regions.map { bounds -> MutableOverlayBounds(bounds.padded) }

    for (firstIndex in resolved.indices) {
        for (secondIndex in firstIndex + 1 until resolved.size) {
            val first = resolved[firstIndex]
            val second = resolved[secondIndex]
            if (!first.intersects(second)) continue

            val firstRaw = regions[firstIndex].raw
            val secondRaw = regions[secondIndex].raw
            if (firstRaw.intersects(secondRaw)) continue

            val verticallySeparated =
                firstRaw.bottom <= secondRaw.top || secondRaw.bottom <= firstRaw.top
            val horizontallySeparated =
                firstRaw.right <= secondRaw.left || secondRaw.right <= firstRaw.left
            val overlapWidth =
                minOf(first.right, second.right) - maxOf(first.left, second.left)
            val overlapHeight =
                minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
            val separateVertically =
                verticallySeparated && (!horizontallySeparated || overlapHeight <= overlapWidth)

            if (separateVertically) {
                separateVertically(first, firstRaw, second, secondRaw)
            } else if (horizontallySeparated) {
                separateHorizontally(first, firstRaw, second, secondRaw)
            }
        }
    }

    return resolved.map(MutableOverlayBounds::toImmutable)
}

private data class MutableOverlayBounds(
    var left: Int,
    var top: Int,
    var right: Int,
    var bottom: Int,
) {
    constructor(bounds: OverlayBounds) : this(
        bounds.left,
        bounds.top,
        bounds.right,
        bounds.bottom,
    )

    fun intersects(other: MutableOverlayBounds): Boolean =
        left < other.right && other.left < right &&
            top < other.bottom && other.top < bottom

    fun toImmutable(): OverlayBounds = OverlayBounds(left, top, right, bottom)
}

private fun separateVertically(
    first: MutableOverlayBounds,
    firstRaw: OverlayBounds,
    second: MutableOverlayBounds,
    secondRaw: OverlayBounds,
) {
    if (firstRaw.bottom <= secondRaw.top) {
        val boundary = firstRaw.bottom + (secondRaw.top - firstRaw.bottom) / 2
        first.bottom = minOf(first.bottom, boundary).coerceAtLeast(firstRaw.bottom)
        second.top = maxOf(second.top, boundary).coerceAtMost(secondRaw.top)
    } else {
        val boundary = secondRaw.bottom + (firstRaw.top - secondRaw.bottom) / 2
        second.bottom = minOf(second.bottom, boundary).coerceAtLeast(secondRaw.bottom)
        first.top = maxOf(first.top, boundary).coerceAtMost(firstRaw.top)
    }
}

private fun separateHorizontally(
    first: MutableOverlayBounds,
    firstRaw: OverlayBounds,
    second: MutableOverlayBounds,
    secondRaw: OverlayBounds,
) {
    if (firstRaw.right <= secondRaw.left) {
        val boundary = firstRaw.right + (secondRaw.left - firstRaw.right) / 2
        first.right = minOf(first.right, boundary).coerceAtLeast(firstRaw.right)
        second.left = maxOf(second.left, boundary).coerceAtMost(secondRaw.left)
    } else {
        val boundary = secondRaw.right + (firstRaw.left - secondRaw.right) / 2
        second.right = minOf(second.right, boundary).coerceAtLeast(secondRaw.right)
        first.left = maxOf(first.left, boundary).coerceAtMost(firstRaw.left)
    }
}
