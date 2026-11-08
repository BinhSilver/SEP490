import unittest
from pathlib import Path

from scripts.dictionary_import.jlas_import.parsers import iter_kanjidic2, iter_kanjivg


FIXTURES = Path(__file__).parent / "fixtures"


class KanjiParserTest(unittest.TestCase):

    def test_kanjidic2_keeps_joyo_and_separate_japanese_readings(self):
        records = list(iter_kanjidic2(FIXTURES / "kanjidic2.xml"))

        self.assertEqual(["日"], [row.literal for row in records])
        day = records[0]
        self.assertEqual("65E5", day.unicode_code)
        self.assertEqual(4, day.stroke_count)
        self.assertEqual(72, day.radical_number)
        self.assertEqual(
            (("ON", "ニチ"), ("KUN", "ひ"), ("NANORI", "あ")),
            tuple((reading.kind, reading.text) for reading in day.readings),
        )
        self.assertIsNone(day.jlpt_level)

    def test_kanjidic2_all_kanji_includes_non_joyo_and_skips_compatibility_forms(self):
        records = list(iter_kanjidic2(FIXTURES / "kanjidic2.xml", include_non_joyo=True))
        self.assertEqual(["日", "亙"], [row.literal for row in records])

        compat = FIXTURES.parent / "_compat_kanjidic2.xml"
        compat.write_text(
            "<kanjidic2><character><literal>渚</literal>"
            "<codepoint><cp_value cp_type=\"ucs\">FA46</cp_value></codepoint>"
            "<misc><stroke_count>11</stroke_count></misc></character></kanjidic2>",
            encoding="utf-8",
        )
        try:
            self.assertEqual([], list(iter_kanjidic2(compat, include_non_joyo=True)))
        finally:
            compat.unlink()

    def test_kanjivg_accepts_directory_and_builds_stable_uri(self):
        records = list(iter_kanjivg(FIXTURES / "kanjivg", "/kanjivg/kanji/"))

        self.assertEqual(["65E5", "9999"], [row.unicode_code for row in records])
        self.assertEqual("/kanjivg/kanji/065e5.svg", records[0].svg_uri)
        self.assertEqual("/kanjivg/kanji/09999.svg", records[1].svg_uri)

    def test_kanjivg_accepts_one_svg_file(self):
        records = list(
            iter_kanjivg(FIXTURES / "kanjivg" / "065e5.svg", "assets/kanji")
        )

        self.assertEqual(1, len(records))
        self.assertEqual("assets/kanji/065e5.svg", records[0].svg_uri)


if __name__ == "__main__":
    unittest.main()
