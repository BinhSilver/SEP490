import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from build_extra_stroke_svgs import (  # noqa: E402
    ComponentIndex,
    animcjk_medians,
    bbox,
    compose,
    fit_into,
    kanjivg_layout,
    parse_path,
)

STROKE = re.compile(r'<path id="kvg:[0-9a-f]{5}-s\d+"')


class ExtraStrokeSvgTest(unittest.TestCase):

    def test_parse_path_makes_relative_and_smooth_curves_absolute(self):
        segments = parse_path("M10,20c1,2 3,4 5,6s7,8 9,10")
        self.assertEqual(("M", [(10.0, 20.0)]), segments[0])
        self.assertEqual(("C", [(11.0, 22.0), (13.0, 24.0), (15.0, 26.0)]), segments[1])
        # S reflects the previous control point (13,24) around the current point (15,26).
        self.assertEqual(("C", [(17.0, 28.0), (22.0, 34.0), (24.0, 36.0)]), segments[2])

    def test_fit_into_maps_bounding_box(self):
        fitted = fit_into(["M0,0 L100,100"], (10, 50, 30, 60))
        self.assertEqual((10.0, 50.0, 30.0, 60.0), bbox(fitted))

    def test_animcjk_medians_are_scaled_to_kanjivg_frame(self):
        svg = ('<path style="--d:1s;" pathLength="3333" clip-path="url(#z1c2)" d="M0 0L1024 1024"/>'
               '<path style="--d:1s;" pathLength="3333" clip-path="url(#z1c1)" d="M512 0L512 512"/>')
        medians = animcjk_medians(svg)
        self.assertEqual([(512.0, 0.0), (512.0, 512.0)], medians[0])
        text = kanjivg_layout("日", medians, "")
        self.assertIn('viewBox="0 0 109 109"', text)
        self.assertIn('d="M54.50,0.00 L54.50,54.50"', text)
        self.assertEqual(2, len(STROKE.findall(text)))

    def test_compose_uses_component_donors_and_checks_stroke_count(self):
        index = ComponentIndex()
        left = ["M1,1 L2,2"] * 11
        right = ["M60,1 L70,2"] * 8
        index.parts[("魚", "left")].append(("鯉", left, {"left": left, "right": ["M60,1 L90,90"]}))
        index.parts[("里", "right")].append(("理", right, {"left": ["M1,1 L9,9"], "right": right}))

        text = compose("鯉", 19, ["⿰魚里"], index)
        self.assertEqual(19, len(STROKE.findall(text)))
        self.assertIsNone(compose("鯉", 20, ["⿰魚里"], index))

    def test_compose_fits_whole_character_into_frame_slot(self):
        index = ComponentIndex()
        top = ["M10,5 L40,10"] * 6
        index.parts[("竹", "top")].append(("箱", top, {"top": top, "bottom": ["M10,50 L90,100"]}))
        index.standalone["洪"] = ["M0,0 L100,100"] * 9

        text = compose("篊", 15, ["⿱竹洪"], index)
        self.assertEqual(15, len(STROKE.findall(text)))
        self.assertIn("whole character", text)
        self.assertIn('d="M10.00,50.00L90.00,100.00"', text)


if __name__ == "__main__":
    unittest.main()
