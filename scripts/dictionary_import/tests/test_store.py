import unittest

from mysql.connector import errors as mysql_errors

from scripts.dictionary_import.jlas_import.store import (
    MySqlDictionaryStore,
    kanji_positions,
    radical_character,
)


class StoreMappingTest(unittest.TestCase):

    def test_kanji_positions_are_unicode_aware_one_based_and_keep_repeats(self):
        self.assertEqual(
            (("𠮷", 1), ("日", 3), ("日", 4)),
            kanji_positions("𠮷の日日"),
        )

    def test_radical_number_maps_to_unified_cjk_character(self):
        self.assertEqual("一", radical_character(1))
        self.assertEqual("龠", radical_character(214))
        with self.assertRaises(ValueError):
            radical_character(0)

    def test_only_connection_level_database_errors_are_fatal(self):
        store = MySqlDictionaryStore(connection=None)

        self.assertTrue(
            store.is_fatal_error(mysql_errors.OperationalError("lost", errno=2013))
        )
        self.assertTrue(store.is_fatal_error(mysql_errors.InterfaceError("closed")))
        self.assertFalse(
            store.is_fatal_error(mysql_errors.IntegrityError("bad row", errno=1062))
        )


if __name__ == "__main__":
    unittest.main()
