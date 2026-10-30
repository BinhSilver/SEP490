import unittest

from scripts.dictionary_import.jlas_import.antonyms import Antonym, parse_antonym, upsert_antonym


class FakeCursor:
    def __init__(self):
        self.rows = {}
        self.result = None

    def execute(self, sql, params):
        if sql.startswith("SELECT"):
            vocabulary_id, text, reading = params
            self.result = self.rows.get((vocabulary_id, text, reading))
        elif sql.startswith("INSERT"):
            vocabulary_id, text, reading, sense, source_id = params
            self.rows[(vocabulary_id, text, reading)] = (len(self.rows) + 1, sense, source_id)
        elif sql.startswith("UPDATE"):
            sense, source_id, row_id = params
            key = next(key for key, row in self.rows.items() if row[0] == row_id)
            self.rows[key] = (row_id, sense, source_id)

    def fetchone(self):
        return self.result


class AntonymImportTest(unittest.TestCase):
    def test_parses_jmdict_antonym_forms(self):
        self.assertEqual(Antonym("低い", None, 1), parse_antonym("低い・1"))
        self.assertEqual(Antonym("下", "しも", 1), parse_antonym("下・しも・1"))
        self.assertEqual(Antonym("ルーラル", "ルーラル", None), parse_antonym("ルーラル"))
        self.assertEqual(Antonym("インナー", "インナー", 1), parse_antonym("インナー・1"))
        with self.assertRaises(ValueError):
            parse_antonym("下・invalid・1")

    def test_upsert_is_idempotent_including_null_reading(self):
        cursor = FakeCursor()
        antonym = Antonym("低い", None, 1)
        self.assertEqual("inserted", upsert_antonym(cursor, 10, 3, antonym))
        self.assertEqual("unchanged", upsert_antonym(cursor, 10, 3, antonym))
        self.assertEqual(1, len(cursor.rows))
        self.assertEqual("updated", upsert_antonym(cursor, 10, 3, Antonym("低い", None, 2)))
        self.assertEqual(1, len(cursor.rows))


if __name__ == "__main__":
    unittest.main()
