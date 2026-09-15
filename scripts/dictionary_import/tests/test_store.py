import unittest

from scripts.dictionary_import.jlas_import.store import kanji_positions, radical_character


class StoreMappingTest(unittest.TestCase):

    def test_kanji_positions_are_unicode_aware_one_based_and_keep_repeats(self):
        self.assertEqual(
            (("𠮷", 1), ("日", 3), ("日", 4)),
            kanji_positions("𠮷の日日"),
        )

    def test_radical_number_maps_to_kangxi_unicode_block(self):
        self.assertEqual("⼀", radical_character(1))
        self.assertEqual("⿕", radical_character(214))
        with self.assertRaises(ValueError):
            radical_character(0)


if __name__ == "__main__":
    unittest.main()
