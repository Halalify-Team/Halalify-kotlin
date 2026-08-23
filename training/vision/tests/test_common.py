from __future__ import annotations

import unittest

from training.vision.common import Box, projected_min_side, stable_split


class CommonTest(unittest.TestCase):
    def test_split_is_source_stable(self) -> None:
        first = stable_split("fairface:123", 0.1, 77)
        self.assertEqual(first, stable_split("fairface:123", 0.1, 77))

    def test_yolo_conversion(self) -> None:
        self.assertEqual(
            "0 0.5000000 0.5000000 0.5000000 0.5000000",
            Box(0, 0.25, 0.25, 0.75, 0.75).to_yolo(),
        )

    def test_portrait_projection_matches_mobile_letterbox(self) -> None:
        box = Box(0, 0.1, 0.1, 0.1 + 28 / 1080, 0.1 + 28 / 2400)
        self.assertAlmostEqual(
            28 * 416 / 2400,
            projected_min_side(box, 1080, 2400),
        )


if __name__ == "__main__":
    unittest.main()
