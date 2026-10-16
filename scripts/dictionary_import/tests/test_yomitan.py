import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.dictionary_import.jlas_import.models import InvalidRecord
from scripts.dictionary_import.jlas_import.yomitan import (
    decode_kanjidic2_code,
    format_kanji_meanings,
    iter_kanjidict_vn,
    iter_mazii_vi,
    parse_mazii_definition,
    parse_radical,
    pos_name_vi,
)


MAZII_TERMS = [
    ["猫", "ねこま", "", "", -99, ["MIÊU\n1. mèo\n"], 229928, ""],
    ["猫", "ねこ", "", "", 726.9, ["MIÊU\n1. mèo\n"], 63869, ""],
    ["猫", "ネコ", "", "", 726.8, ["MIÊU\n1. mèo .\n2. con mèo\n"], 63870, ""],
    ["食べる", "たべる", "", "v1", 714.9, ["THỰC\n1. ăn .\n"], 49221, ""],
    [" 食べる", "", "", "", 0, ["\n1. ăn.\n2. sống nhờ\n"], 0, ""],
    ["かき", "", "", "", 3, ["\n1. con hàu\n"], 0, ""],
    ["かぎ", "", "", "", 2, ["\n1. chìa khóa\n"], 0, ""],
    ["あしらう", "", "", "", 0, ["\n"], 0, ""],
]

JMDICT_VI_TERMS = [
    ["食べる", "たべる", "v1 vt", "v1", 700, ["to eat"], 1358280, ""],
    ["猫", "ねこ", "n", "", 700, ["cat"], 1467640, ""],
]
JMDICT_VI_TAGS = [
    ["v1", "partOfSpeech", 0, "Ichidan verb", 0],
    ["vt", "partOfSpeech", 0, "transitive verb", 0],
    ["n", "partOfSpeech", 0, "noun", 0],
    ["uk", "", 0, "usually kana", 0],
]


class YomitanParserTest(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.mazii = self.root / "mazii"
        self.mazii.mkdir()
        (self.mazii / "term_bank_1.json").write_text(
            json.dumps(MAZII_TERMS, ensure_ascii=False), encoding="utf-8"
        )
        # The JMdict (Vietnamese) build ships as a ZIP nested in another ZIP.
        inner = self.root / "term_bank_7.zip"
        with zipfile.ZipFile(inner, "w") as archive:
            archive.writestr("term_bank_1.json", json.dumps(JMDICT_VI_TERMS, ensure_ascii=False))
            archive.writestr("tag_bank_1.json", json.dumps(JMDICT_VI_TAGS))
        self.pos_zip = self.root / "jmdict-vi.zip"
        with zipfile.ZipFile(self.pos_zip, "w") as archive:
            archive.write(inner, "dict/term_bank_7.zip")

    def tearDown(self):
        self.temp.cleanup()

    def test_definition_splits_han_viet_and_numbered_meanings(self):
        self.assertEqual(
            ("NHẬT BỔN", ["Nhật Bản", "nước Nhật"]),
            parse_mazii_definition("NHẬT BỔN\n1. Nhật Bản .\n2. nước Nhật\n"),
        )
        self.assertEqual(
            (None, ["không tử tế", "độc ác"]),
            parse_mazii_definition("\n1. \nkhông tử tế\n\n2. \nđộc ác\n\n"),
        )
        self.assertEqual((None, ["v.v."]), parse_mazii_definition("\n1. v.v.\n"))

    def test_mazii_merges_keys_orders_by_score_and_adds_pos(self):
        records = list(iter_mazii_vi(self.mazii, self.pos_zip))
        valid = [record for record in records if not isinstance(record, InvalidRecord)]

        self.assertEqual(
            [("猫", "ねこ"), ("食べる", "たべる"), ("かき", "かき"), ("かぎ", "かぎ"), ("猫", "ねこま")],
            [(record.expression, record.reading) for record in valid],
        )
        cat, eat = valid[0], valid[1]
        self.assertEqual("MIÊU", cat.han_viet)
        self.assertEqual(["mèo", "con mèo"], [s.glosses[0].text for s in cat.senses])
        self.assertEqual("vie", cat.senses[0].glosses[0].language_code)
        self.assertEqual("63869", cat.external_id)
        self.assertEqual(["Danh từ"], [p.name_vi for p in cat.senses[0].parts_of_speech])
        self.assertEqual(["ăn", "sống nhờ"], [s.glosses[0].text for s in eat.senses])
        self.assertEqual("ăn; sống nhờ", eat.meaning)
        self.assertEqual(
            [("v1", "Động từ nhóm II"), ("vt", "Tha động từ")],
            [(p.code, p.name_vi) for p in eat.senses[0].parts_of_speech],
        )
        self.assertEqual((), eat.senses[1].parts_of_speech)
        self.assertEqual(1, sum(isinstance(record, InvalidRecord) for record in records))

    def test_mazii_without_pos_file_uses_deinflection_rules(self):
        eat = next(r for r in iter_mazii_vi(self.mazii) if getattr(r, "expression", "") == "食べる")
        self.assertEqual(["v1"], [p.code for p in eat.senses[0].parts_of_speech])

    def test_pos_labels_keep_ui_filter_keywords(self):
        self.assertEqual("Động từ nhóm I", pos_name_vi("v5k-s"))
        self.assertIn("tính từ", pos_name_vi("adj-na").lower())
        self.assertEqual("xyz", pos_name_vi("xyz"))

    def test_kanjidict_vn_formats_readings_meanings_and_radical(self):
        bank = self.root / "kanji"
        bank.mkdir()
        (bank / "kanji_bank_1.json").write_text(json.dumps([
            ["食", "thực tự", "", "", ["[thực] ăn", "[thực] đồ ăn"],
             {"Radical": "thực 食 (+1 nét)"}],
            ["行", "hành hàng", "", "", ["[hành] đi", "[hàng] hàng lối"],
             {"Radical": "hành 行 (+0 nét)"}],
            ["㖨", "", "", "", [], {"Radical": "khẩu 口 (+8 nét)"}],
        ], ensure_ascii=False), encoding="utf-8")

        records = list(iter_kanjidict_vn(bank))

        self.assertEqual("THỰC, TỰ", records[0].han_viet)
        self.assertEqual("ăn; đồ ăn", records[0].meaning_vi)
        self.assertEqual(("食", "Thực"), (records[0].radical_character, records[0].radical_name_vi))
        self.assertEqual("[hành] đi; [hàng] hàng lối", records[1].meaning_vi)
        self.assertEqual(("口", "Khẩu"), (records[2].radical_character, records[2].radical_name_vi))
        self.assertIsNone(format_kanji_meanings([]))
        self.assertEqual((None, None), parse_radical("không rõ"))

    def test_shinjitai_borrows_meaning_from_traditional_variant(self):
        bank = self.root / "kanji-variants"
        bank.mkdir()
        (bank / "kanji_bank_1.json").write_text(json.dumps([
            ["駅", "dịch", "", "", [], {}],
            ["驛", "dịch", "", "", ["[dịch] ngựa trạm"], {}],
            ["込", "vu", "", "", [], {}],
        ], ensure_ascii=False), encoding="utf-8")
        variants = self.root / "kanjidic2.xml"
        variants.write_text(
            "<kanjidic2><character><literal>駅</literal>"
            "<misc><variant var_type=\"jis208\">1-81-67</variant></misc></character></kanjidic2>",
            encoding="utf-8",
        )

        records = {record.literal: record for record in iter_kanjidict_vn(bank, variants)}

        self.assertEqual("ngựa trạm", records["駅"].meaning_vi)
        self.assertEqual("MEANING_FROM_VARIANT", records["駅"].warnings[0].code)
        self.assertIsNone(records["込"].meaning_vi)
        self.assertEqual("驛", decode_kanjidic2_code("jis208", "1-81-67"))
        self.assertEqual("渚", decode_kanjidic2_code("ucs", "6e1a"))
        self.assertIsNone(decode_kanjidic2_code("deroo", "1234"))


if __name__ == "__main__":
    unittest.main()
