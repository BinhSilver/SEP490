import unittest
from pathlib import Path

from scripts.dictionary_import.jlas_import.models import InvalidRecord
from scripts.dictionary_import.jlas_import.parsers import iter_jmdict


FIXTURES = Path(__file__).parent / "fixtures"


class JmDictParserTest(unittest.TestCase):

    def test_streams_variants_readings_senses_glosses_pos_and_all_domains(self):
        records = list(iter_jmdict(FIXTURES / "jmdict.xml"))
        school = next(row for row in records if row.external_id == "1000001")

        self.assertEqual("学校", school.expression)
        self.assertEqual("がっこう", school.reading)
        self.assertEqual(("學校",), school.variants)
        self.assertEqual("ガクコウ", school.alternate_readings[0].reading)
        self.assertEqual(2, len(school.senses))
        self.assertEqual(
            (("eng", "school"), ("vie", "trường học")),
            tuple((gloss.language_code, gloss.text) for gloss in school.senses[0].glosses),
        )
        self.assertEqual(("n",), tuple(item.code for item in school.senses[0].parts_of_speech))
        self.assertEqual(("n",), tuple(item.code for item in school.senses[1].parts_of_speech))
        self.assertEqual(
            {"comp", "law", "med"},
            {item.code for item in school.senses[0].domains},
        )

    def test_kana_only_reading_is_not_selected_for_a_written_expression(self):
        records = list(iter_jmdict(FIXTURES / "jmdict.xml"))
        today = next(row for row in records if row.external_id == "1000007")

        self.assertEqual("今日", today.expression)
        self.assertEqual("こんにち", today.reading)
        self.assertEqual("きょう", today.alternate_readings[0].reading)
        self.assertEqual("kana-only", today.alternate_readings[0].usage_note)

    def test_keeps_restrictions_in_notes_and_emits_lossy_mapping_warning(self):
        school = list(iter_jmdict(FIXTURES / "jmdict.xml"))[0]

        self.assertEqual("written forms: 學校", school.alternate_readings[0].usage_note)
        self.assertEqual("written forms: 学校", school.senses[1].usage_note)
        self.assertEqual(
            {"JMDICT_READING_RESTRICTION", "JMDICT_SENSE_RESTRICTION"},
            {warning.code for warning in school.warnings},
        )

    def test_missing_required_field_yields_failure_and_stream_continues(self):
        records = list(iter_jmdict(FIXTURES / "jmdict-missing-reading.xml"))

        self.assertIsInstance(records[0], InvalidRecord)
        self.assertEqual("bad-reading", records[0].record_id)
        self.assertIn("reading", records[0].message)
        self.assertEqual("after-bad", records[1].external_id)


if __name__ == "__main__":
    unittest.main()
