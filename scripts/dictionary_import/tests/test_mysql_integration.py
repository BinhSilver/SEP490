import os
import unittest
from pathlib import Path

import mysql.connector

from scripts.dictionary_import.jlas_import.normalization import normalize_reading, normalize_text
from scripts.dictionary_import.jlas_import.parsers import iter_jmdict, iter_kanjidic2, iter_kanjivg
from scripts.dictionary_import.jlas_import.pipeline import ImportMetadata, ImportPipeline
from scripts.dictionary_import.jlas_import.store import MySqlDictionaryStore


FIXTURES = Path(__file__).parent / "fixtures"
SCHEMA = Path(__file__).parent / "mysql_test_schema.sql"


class MemoryReport:
    def __init__(self):
        self.events = []

    def write(self, *event):
        self.events.append(event)


@unittest.skipUnless(
    os.getenv("JLAS_IMPORT_TEST_MYSQL") == "1",
    "set JLAS_IMPORT_TEST_MYSQL=1 and test database variables to run MySQL proof",
)
class MySqlImportIntegrationTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        database = os.environ["JLAS_IMPORT_TEST_DB_NAME"]
        if not database.lower().endswith("_test"):
            raise RuntimeError("JLAS_IMPORT_TEST_DB_NAME must end with _test")
        cls.connection = mysql.connector.connect(
            host=os.getenv("JLAS_IMPORT_TEST_DB_HOST", "localhost"),
            port=int(os.getenv("JLAS_IMPORT_TEST_DB_PORT", "3306")),
            database=database,
            user=os.environ["JLAS_IMPORT_TEST_DB_USERNAME"],
            password=os.environ["JLAS_IMPORT_TEST_DB_PASSWORD"],
            charset="utf8mb4",
        )
        cls._execute_script(SCHEMA.read_text(encoding="utf-8"))
        cursor = cls.connection.cursor()
        cursor.executemany(
            "INSERT IGNORE INTO dictionary_source (code, name, is_active) VALUES (%s, %s, 1)",
            [
                ("JMDICT_TEST", "JMdict integration test"),
                ("KANJIDIC2_TEST", "KANJIDIC2 integration test"),
                ("KANJIVG_TEST", "KanjiVG integration test"),
            ],
        )
        cursor.close()
        cls.connection.commit()

    @classmethod
    def tearDownClass(cls):
        cls._cleanup_fixture_rows()
        cls.connection.close()

    @classmethod
    def _execute_script(cls, script):
        cursor = cls.connection.cursor()
        try:
            for statement in script.split(";"):
                sql = statement.strip()
                if sql:
                    cursor.execute(sql)
        finally:
            cursor.close()
        cls.connection.commit()

    @classmethod
    def _cleanup_fixture_rows(cls):
        cursor = cls.connection.cursor(dictionary=True)
        try:
            cursor.execute(
                "SELECT id FROM dictionary_source WHERE code IN "
                "('JMDICT_TEST','KANJIDIC2_TEST','KANJIVG_TEST')"
            )
            source_ids = [row["id"] for row in cursor.fetchall()]
            if source_ids:
                markers = ",".join(["%s"] * len(source_ids))
                cursor.execute(
                    f"SELECT vocabulary_id FROM vocabulary_external_reference WHERE source_id IN ({markers})",
                    tuple(source_ids),
                )
                vocabulary_ids = [row["vocabulary_id"] for row in cursor.fetchall()]
                cursor.execute(
                    f"DELETE FROM dictionary_import_batch WHERE source_id IN ({markers})",
                    tuple(source_ids),
                )
                cursor.execute(
                    f"DELETE FROM vocabulary_external_reference WHERE source_id IN ({markers})",
                    tuple(source_ids),
                )
                if vocabulary_ids:
                    word_markers = ",".join(["%s"] * len(vocabulary_ids))
                    cursor.execute(
                        f"DELETE FROM vocabulary_entry WHERE id IN ({word_markers})",
                        tuple(vocabulary_ids),
                    )
                cursor.execute(
                    f"DELETE FROM dictionary_source WHERE id IN ({markers})",
                    tuple(source_ids),
                )
            cursor.execute("DELETE FROM kanji WHERE unicode_code IN ('65E5','9999')")
            cursor.execute("DELETE FROM radical WHERE radical_number = 72")
            cursor.execute("DELETE FROM dictionary_domain WHERE code IN ('comp','law','med')")
            cursor.execute("DELETE FROM dictionary_part_of_speech WHERE code IN ('n','vs')")
            cursor.execute("DELETE FROM content_item WHERE id = 9000001")
            cls.connection.commit()
        finally:
            cursor.close()

    def setUp(self):
        self._cleanup_fixture_rows()
        self._seed_sources_and_textbook_word()

    def _seed_sources_and_textbook_word(self):
        cursor = self.connection.cursor()
        try:
            cursor.executemany(
                "INSERT INTO dictionary_source (code, name, is_active) VALUES (%s, %s, 1)",
                [
                    ("JMDICT_TEST", "JMdict integration test"),
                    ("KANJIDIC2_TEST", "KANJIDIC2 integration test"),
                    ("KANJIVG_TEST", "KanjiVG integration test"),
                ],
            )
            cursor.execute("INSERT INTO content_item (id) VALUES (9000001)")
            cursor.execute(
                "INSERT INTO vocabulary_entry "
                "(expression, normalized_expression, reading, normalized_reading, meaning, "
                "word_type, jlpt_level, example_ja, example_vi, usage_scope, dictionary_status) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,'TEXTBOOK','PUBLISHED')",
                (
                    "学校", normalize_text("学校"), "がっこう", normalize_reading("がっこう"),
                    "trường học", "noun", "N5", "学校へ行く。", "Đi đến trường.",
                ),
            )
            vocabulary_id = cursor.lastrowid
            cursor.execute(
                "INSERT INTO content_vocabulary (content_item_id, vocabulary_id) VALUES (9000001, %s)",
                (vocabulary_id,),
            )
            self.connection.commit()
        finally:
            cursor.close()

    def test_full_import_second_run_textbook_merge_domains_kanji_and_failures(self):
        store = MySqlDictionaryStore(self.connection)
        report = MemoryReport()

        kanji = ImportPipeline(store, report, 2).run(
            ImportMetadata("KANJIDIC2_TEST", "fixture", "1" * 64),
            iter_kanjidic2(FIXTURES / "kanjidic2.xml"),
        )
        first = ImportPipeline(store, report, 2).run(
            ImportMetadata("JMDICT_TEST", "fixture", "2" * 64),
            iter_jmdict(FIXTURES / "jmdict.xml"),
        )
        before = self._row_counts()
        second = ImportPipeline(store, report, 2).run(
            ImportMetadata("JMDICT_TEST", "fixture", "2" * 64),
            iter_jmdict(FIXTURES / "jmdict.xml"),
        )
        after = self._row_counts()
        svg = ImportPipeline(store, report, 2).run(
            ImportMetadata("KANJIVG_TEST", "fixture", "3" * 64),
            iter_kanjivg(FIXTURES / "kanjivg", "/kanjivg/kanji"),
        )
        partial = ImportPipeline(store, report, 2).run(
            ImportMetadata("JMDICT_TEST", "bad-fixture", "4" * 64),
            iter_jmdict(FIXTURES / "jmdict-missing-reading.xml"),
        )

        self.assertEqual(("COMPLETED", 1, 0), (kanji.status, kanji.inserted, kanji.failed))
        self.assertEqual(("COMPLETED", 6, 1, 0), (first.status, first.inserted, first.updated, first.failed))
        self.assertEqual("SKIPPED", second.status)
        self.assertEqual(before, after)
        self.assertEqual(("COMPLETED", 0, 1, 1), (svg.status, svg.inserted, svg.updated, svg.skipped))
        self.assertEqual(("COMPLETED_WITH_ERRORS", 1, 1), (partial.status, partial.inserted, partial.failed))

        cursor = self.connection.cursor(dictionary=True)
        try:
            cursor.execute(
                "SELECT meaning, word_type, jlpt_level, example_ja, example_vi, usage_scope "
                "FROM vocabulary_entry WHERE normalized_expression = %s AND normalized_reading = %s",
                (normalize_text("学校"), normalize_reading("がっこう")),
            )
            school = cursor.fetchone()
            self.assertEqual(
                ("trường học", "noun", "N5", "学校へ行く。", "Đi đến trường.", "BOTH"),
                tuple(school.values()),
            )
            cursor.execute("SELECT code FROM dictionary_domain ORDER BY code")
            self.assertEqual({"comp", "law", "med"}, {row["code"] for row in cursor.fetchall()})
            cursor.execute(
                "SELECT character_position FROM vocabulary_kanji vk "
                "JOIN vocabulary_entry v ON v.id = vk.vocabulary_id "
                "WHERE v.expression = '日日' ORDER BY character_position"
            )
            self.assertEqual([1, 2], [row["character_position"] for row in cursor.fetchall()])
            cursor.execute("SELECT stroke_svg_uri FROM kanji WHERE unicode_code = '65E5'")
            self.assertEqual("/kanjivg/kanji/065e5.svg", cursor.fetchone()["stroke_svg_uri"])
            cursor.execute(
                "SELECT COUNT(*) AS count FROM content_vocabulary WHERE content_item_id = 9000001"
            )
            self.assertEqual(1, cursor.fetchone()["count"])
        finally:
            cursor.close()

    def _row_counts(self):
        tables = (
            "vocabulary_entry", "vocabulary_reading", "vocabulary_variant",
            "vocabulary_sense", "vocabulary_gloss", "dictionary_part_of_speech",
            "sense_part_of_speech", "dictionary_domain", "sense_domain",
            "vocabulary_kanji", "vocabulary_external_reference",
        )
        cursor = self.connection.cursor(dictionary=True)
        try:
            counts = []
            for table in tables:
                cursor.execute(f"SELECT COUNT(*) AS count FROM {table}")
                counts.append(cursor.fetchone()["count"])
            return tuple(counts)
        finally:
            cursor.close()


if __name__ == "__main__":
    unittest.main()
