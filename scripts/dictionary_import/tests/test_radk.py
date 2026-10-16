import tempfile
import unittest
from pathlib import Path

from scripts.dictionary_import.jlas_import.radk import iter_radkfile

SAMPLE = """# comment line
$ 一 1
亜唖
悪一
$ 化 2 js01
休体
$ 木 4
休本
$ 汁 3 4653
汁
"""


class RadkfileParserTest(unittest.TestCase):

    def test_parses_components_kanji_and_real_glyphs(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "radkfilex"
            path.write_bytes(SAMPLE.encode("euc_jis_2004"))

            records = list(iter_radkfile(path))

        self.assertEqual(["一", "化", "木", "汁"], [r.source_character for r in records])
        # Substitute kanji are shown as the real component glyph and linked to the Kangxi radical.
        self.assertEqual(["一", "亻", "木", "氵"], [r.character for r in records])
        self.assertEqual("人", records[1].radical_character)
        self.assertEqual("水", records[3].radical_character)
        self.assertEqual([1, 2, 4, 3], [r.stroke_count for r in records])
        self.assertEqual([1, 2, 3, 4], [r.display_order for r in records])
        self.assertEqual(("亜", "唖", "悪", "一"), records[0].kanji)
        self.assertEqual(("休", "体"), records[1].kanji)


if __name__ == "__main__":
    unittest.main()
