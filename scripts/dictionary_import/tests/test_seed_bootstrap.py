import gzip
import hashlib
import io
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr
from pathlib import Path

from scripts.dictionary_import.seed_bootstrap import (
    BootstrapError,
    _database_inventory,
    _parser,
    _run_gzip_command,
    classify_database,
    extract_zip_safely,
    main,
    validate_test_database_name,
    verify_artifact,
)


class SeedBootstrapTest(unittest.TestCase):

    def test_cli_does_not_accept_password_on_command_line(self):
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            _parser().parse_args(["--password", "must-not-enter-process-list"])

    def test_gzip_command_receives_decompressed_bytes_on_stdin(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            compressed = root / "seed.sql.gz"
            captured = root / "captured.sql"
            with gzip.open(compressed, "wb") as stream:
                stream.write(b"SELECT 1;\n")

            _run_gzip_command(
                compressed,
                [
                    sys.executable,
                    "-c",
                    "import pathlib,sys; pathlib.Path(sys.argv[1]).write_bytes(sys.stdin.buffer.read())",
                    str(captured),
                ],
                {},
            )

            self.assertEqual(b"SELECT 1;\n", captured.read_bytes())

    def test_database_inventory_qualifies_count_queries_with_target_database(self):
        class Cursor:
            def __init__(self):
                self.rows = []

            def execute(self, query, parameters=None):
                if "information_schema.tables" in query:
                    self.rows = [("kanji", "BASE TABLE")]
                elif query == "SELECT COUNT(*) FROM `jlas_dictionary_test`.`kanji`":
                    self.rows = [(2,)]
                else:
                    self.rows = [(0,)]

            def fetchall(self):
                return self.rows

            def fetchone(self):
                return self.rows[0]

            def close(self):
                pass

        class Connection:
            def cursor(self):
                return Cursor()

        inventory = _database_inventory(Connection(), "jlas_dictionary_test")

        self.assertEqual({"kanji": 2}, inventory)

    def test_cli_rejects_non_test_database_before_external_work(self):
        stderr = io.StringIO()

        exit_code = main(["--database", "jlas"], environ={}, stderr=stderr)

        self.assertEqual(2, exit_code)
        self.assertIn("end in _test", stderr.getvalue())

    def test_database_name_must_be_an_explicit_test_database(self):
        self.assertEqual("jlas_dictionary_test", validate_test_database_name("jlas_dictionary_test"))
        for unsafe_name in ("jlas", "production", "jlas-test", "jlas_test; DROP DATABASE jlas"):
            with self.subTest(unsafe_name=unsafe_name):
                with self.assertRaises(BootstrapError):
                    validate_test_database_name(unsafe_name)

    def test_database_state_is_empty_installed_or_rejected_as_partial(self):
        expected = {"kanji": 2, "vocabulary_entry": 3}

        self.assertEqual("EMPTY", classify_database({}, expected))
        self.assertEqual("INSTALLED", classify_database(expected, expected))
        with self.assertRaisesRegex(BootstrapError, "partially populated"):
            classify_database({"kanji": 2, "vocabulary_entry": 1}, expected)
        with self.assertRaisesRegex(BootstrapError, "partially populated"):
            classify_database({"account": 0}, expected)

    def test_complete_schema_and_zero_business_rows_are_required(self):
        expected_dictionary = {"kanji": 2}
        complete = {"account": 0, "dictionary_view": None, "kanji": 2}
        schema_sha256 = hashlib.sha256(
            b"BASE TABLE\taccount\nVIEW\tdictionary_view\nBASE TABLE\tkanji\n"
        ).hexdigest()

        self.assertEqual(
            "INSTALLED",
            classify_database(complete, expected_dictionary, 3, schema_sha256),
        )
        with self.assertRaisesRegex(BootstrapError, "partially populated"):
            classify_database(
                {"account": 1, "dictionary_view": None, "kanji": 2},
                expected_dictionary,
                3,
                schema_sha256,
            )
        with self.assertRaisesRegex(BootstrapError, "partially populated"):
            classify_database(
                {"account": 0, "kanji": 2},
                expected_dictionary,
                3,
                schema_sha256,
            )

    def test_artifact_hash_and_size_must_match_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "seed.bin"
            artifact.write_bytes(b"dictionary-seed")
            sha256 = hashlib.sha256(b"dictionary-seed").hexdigest()

            verify_artifact(artifact, sha256, 15)
            with self.assertRaisesRegex(BootstrapError, "checksum"):
                verify_artifact(artifact, "0" * 64, 15)
            with self.assertRaisesRegex(BootstrapError, "size"):
                verify_artifact(artifact, sha256, 99)

    def test_zip_extraction_rejects_paths_outside_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "kanjivg.zip"
            destination = root / "static"
            with zipfile.ZipFile(archive, "w") as stream:
                stream.writestr("../escape.svg", "malicious")

            with self.assertRaisesRegex(BootstrapError, "unsafe path"):
                extract_zip_safely(archive, destination)

            self.assertFalse((root / "escape.svg").exists())

    def test_zip_extraction_returns_file_count_and_writes_expected_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "kanjivg.zip"
            destination = root / "assets" / "kanjivg"
            with zipfile.ZipFile(archive, "w") as stream:
                stream.writestr("kanji/065e5.svg", "<svg/>")
                stream.writestr("kanji/065e5-Kaisho.svg", "<svg/>")

            extracted = extract_zip_safely(archive, destination)

            self.assertEqual(2, extracted)
            self.assertEqual(
                "<svg/>",
                (destination / "kanji" / "065e5.svg").read_text(encoding="utf-8"),
            )

    def test_zip_extraction_replaces_stale_asset_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "kanjivg.zip"
            destination = root / "assets" / "kanjivg"
            (destination / "kanji").mkdir(parents=True)
            (destination / "kanji" / "stale.svg").write_text(
                "stale", encoding="utf-8"
            )
            with zipfile.ZipFile(archive, "w") as stream:
                stream.writestr("kanji/065e5.svg", "<svg/>")

            extract_zip_safely(archive, destination)

            self.assertFalse((destination / "kanji" / "stale.svg").exists())
            self.assertTrue((destination / "kanji" / "065e5.svg").is_file())

    def test_zip_extraction_rejects_files_outside_kanji_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "kanjivg.zip"
            with zipfile.ZipFile(archive, "w") as stream:
                stream.writestr("wrong/065e5.svg", "<svg/>")

            with self.assertRaisesRegex(BootstrapError, "kanji/"):
                extract_zip_safely(archive, root / "assets" / "kanjivg")


if __name__ == "__main__":
    unittest.main()
