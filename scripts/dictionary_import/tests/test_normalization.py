import unittest

from scripts.dictionary_import.jlas_import.normalization import (
    is_kanji_codepoint,
    normalize_reading,
    normalize_text,
    reading_to_romaji,
)


class NormalizationTest(unittest.TestCase):

    def test_nfkc_trim_and_whitespace_collapse(self):
        self.assertEqual("ABC 日本", normalize_text("  ＡＢＣ\t 日本  "))

    def test_reading_key_converts_katakana_to_hiragana(self):
        self.assertEqual("がっこう", normalize_reading(" ガッコウ "))

    def test_romaji_uses_hepburn(self):
        self.assertEqual("gakkou", reading_to_romaji("がっこう"))
        self.assertEqual("shinbun", reading_to_romaji("しんぶん"))

    def test_kanji_detection_handles_bmp_and_supplementary_codepoints(self):
        self.assertTrue(is_kanji_codepoint("学"))
        self.assertTrue(is_kanji_codepoint("𠮷"))
        self.assertFalse(is_kanji_codepoint("が"))
        self.assertFalse(is_kanji_codepoint("ab"))


if __name__ == "__main__":
    unittest.main()
