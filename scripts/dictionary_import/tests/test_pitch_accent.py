import tempfile
import unittest
from pathlib import Path

from scripts.dictionary_import.jlas_import.pitch_accent import (
    UNIDIC_ATYPE, UNIDIC_FIELD_COUNT, UNIDIC_KANA, load_kanjium, mora_count, select_accents, valid_accents,
)


class FakeNode:
    def __init__(self, surface, kana, accent):
        self.surface = surface
        feature = ["*"] * UNIDIC_FIELD_COUNT
        feature[UNIDIC_KANA] = kana
        feature[UNIDIC_ATYPE] = accent
        self.feature = tuple(feature)


class FakeTagger:
    def __init__(self, analyses):
        self.analyses = analyses

    def __call__(self, text):
        return self.analyses.get(text, [])


class PitchAccentTest(unittest.TestCase):
    def test_mora_count_treats_small_kana_as_part_of_previous_mora(self):
        self.assertEqual(5, mora_count("たんじょうび"))  # た ん じょ う び
        self.assertEqual(4, mora_count("がっこう"))
        self.assertEqual(3, mora_count("ボシュー"))

    def test_valid_accents_drops_values_beyond_mora_count(self):
        self.assertEqual("0,2", valid_accents("0,2", "たかい"))
        self.assertEqual("0", valid_accents("0,9", "たかい"))
        self.assertIsNone(valid_accents("9", "たかい"))
        self.assertIsNone(valid_accents("*", "たかい"))
        self.assertIsNone(valid_accents("(副)0,(名)3", "ふたつ"))

    def test_load_kanjium_skips_annotated_and_conflicting_rows(self):
        text = ("誕生日\tたんじょうび\t3\n"
                "かさかさ\t\t(副)1,(形動)0\n"
                "ひかり\t\t3\n"
                "重複\tちょうふく\t0\n"
                "重複\tちょうふく\t2\n")
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "accents.txt"
            path.write_text(text, encoding="utf-8")
            found, counts = load_kanjium(path)
        self.assertEqual("3", found[("誕生日", "たんじょうび")])
        self.assertEqual("3", found[("ひかり", "ひかり")])
        self.assertNotIn(("重複", "ちょうふく"), found)
        self.assertEqual(1, counts["annotated"])
        self.assertEqual(1, counts["conflicting"])

    def test_unidic_first_then_kanjium_then_nothing(self):
        tagger = FakeTagger({
            "募集": [FakeNode("募集", "ボシュウ", "0")],
            "誕生日": [FakeNode("誕生", "タンジョウ", "0"), FakeNode("日", "ヒ", "0,1")],
            "日本": [FakeNode("日本", "ニッポン", "3")],
        })
        kanjium = {("誕生日", "たんじょうび"): "3", ("日本", "にほん"): "2"}
        self.assertEqual(("0", "UNIDIC_CWJ"), select_accents(tagger, kanjium, "募集", "ぼしゅう"))
        # Từ ghép bị tách thành nhiều token -> dùng Kanjium.
        self.assertEqual(("3", "KANJIUM"), select_accents(tagger, kanjium, "誕生日", "たんじょうび"))
        # UniDic đọc khác (ニッポン) -> không dùng UniDic cho にほん.
        self.assertEqual(("2", "KANJIUM"), select_accents(tagger, kanjium, "日本", "にほん"))
        self.assertIsNone(select_accents(tagger, kanjium, "未知語", "みちご"))


if __name__ == "__main__":
    unittest.main()
