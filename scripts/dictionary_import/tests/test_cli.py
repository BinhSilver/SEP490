import io
import json
import tempfile
import unittest
from pathlib import Path

from scripts.dictionary_import.jlas_import.cli import compute_checksum, main
from scripts.dictionary_import.jlas_import.pipeline import sanitize_message
from scripts.dictionary_import.jlas_import.reporting import JsonlReportWriter


class CliTest(unittest.TestCase):

    def test_cli_rejects_missing_input_before_database_connection(self):
        connected = False

        def connector(**kwargs):
            nonlocal connected
            connected = True

        stderr = io.StringIO()
        exit_code = main(
            ["--source", "jmdict", "--file", "missing.xml"],
            environ={},
            connector_factory=connector,
            stdout=io.StringIO(),
            stderr=stderr,
        )

        self.assertEqual(2, exit_code)
        self.assertIn("input path does not exist", stderr.getvalue())
        self.assertFalse(connected)

    def test_cli_never_echoes_password_on_connection_error(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "fixture.xml"
            fixture.write_text("<JMdict/>", encoding="utf-8")
            stderr = io.StringIO()

            def failing_connector(**kwargs):
                raise RuntimeError(f"password={kwargs['password']} transport failed")

            exit_code = main(
                ["--source", "jmdict", "--file", str(fixture)],
                environ={
                    "DB_HOST": "localhost",
                    "DB_PORT": "3306",
                    "DB_NAME": "jlas",
                    "DB_USERNAME": "tester",
                    "DB_PASSWORD": "never-print-this",
                },
                connector_factory=failing_connector,
                stdout=io.StringIO(),
                stderr=stderr,
            )

        self.assertEqual(1, exit_code)
        self.assertNotIn("never-print-this", stderr.getvalue())
        self.assertNotIn("password", stderr.getvalue().lower())
        self.assertIn("[redacted]", stderr.getvalue())

    def test_cli_redacts_password_even_without_a_password_label(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "fixture.xml"
            fixture.write_text("<JMdict/>", encoding="utf-8")
            stderr = io.StringIO()

            def failing_connector(**kwargs):
                raise RuntimeError(
                    f"transport rejected credential {kwargs['password']}"
                )

            exit_code = main(
                ["--source", "jmdict", "--file", str(fixture)],
                environ={
                    "DB_NAME": "jlas",
                    "DB_USERNAME": "tester",
                    "DB_PASSWORD": "raw secret value",
                },
                connector_factory=failing_connector,
                stdout=io.StringIO(),
                stderr=stderr,
            )

        self.assertEqual(1, exit_code)
        self.assertNotIn("raw secret value", stderr.getvalue())
        self.assertIn("[redacted]", stderr.getvalue())

    def test_sanitizer_redacts_quoted_values_and_credentials_in_uris(self):
        message = (
            "password=alpha beta; token='tok en'; "
            "mysql://tester:uri-secret@localhost/jlas"
        )

        sanitized = sanitize_message(message)

        self.assertNotIn("alpha beta", sanitized)
        self.assertNotIn("tok en", sanitized)
        self.assertNotIn("uri-secret", sanitized)
        self.assertIn("[redacted]", sanitized)

    def test_checksum_is_sha256_and_directory_order_is_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "b.svg").write_bytes(b"B")
            (root / "a.svg").write_bytes(b"A")

            first = compute_checksum(root)
            second = compute_checksum(root)

        self.assertEqual(64, len(first))
        self.assertEqual(first, second)

    def test_jsonl_report_keeps_traceable_record_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            report_path = Path(directory) / "report.jsonl"
            with JsonlReportWriter(report_path) as report:
                report.write(12, "JMDICT", "1001", "WARNING", "CODE", "message")
            event = json.loads(report_path.read_text(encoding="utf-8"))

        self.assertEqual(
            {
                "batch_id": 12,
                "source": "JMDICT",
                "record_id": "1001",
                "severity": "WARNING",
                "code": "CODE",
                "message": "message",
            },
            event,
        )

    def test_jsonl_report_preserves_previous_run_events(self):
        with tempfile.TemporaryDirectory() as directory:
            report_path = Path(directory) / "report.jsonl"
            with JsonlReportWriter(report_path) as report:
                report.write(1, "JMDICT", "1001", "WARNING", "FIRST", "one")
            with JsonlReportWriter(report_path) as report:
                report.write(2, "JMDICT", "1002", "ERROR", "SECOND", "two")

            events = [
                json.loads(line)
                for line in report_path.read_text(encoding="utf-8").splitlines()
            ]

        self.assertEqual([1, 2], [event["batch_id"] for event in events])

    def test_cli_rejects_report_path_equal_to_input_before_connecting(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "fixture.xml"
            original = "<JMdict/>"
            fixture.write_text(original, encoding="utf-8")
            connected = False

            def connector(**kwargs):
                nonlocal connected
                connected = True
                raise RuntimeError("must not connect")

            exit_code = main(
                [
                    "--source", "jmdict", "--file", str(fixture),
                    "--report-file", str(fixture),
                ],
                environ={
                    "DB_NAME": "jlas",
                    "DB_USERNAME": "tester",
                    "DB_PASSWORD": "secret",
                },
                connector_factory=connector,
                stdout=io.StringIO(),
                stderr=io.StringIO(),
            )

            self.assertEqual(original, fixture.read_text(encoding="utf-8"))

        self.assertEqual(2, exit_code)
        self.assertFalse(connected)

    def test_cli_rejects_report_inside_kanjivg_source_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "kanji"
            source.mkdir()
            (source / "065e5.svg").write_text("<svg/>", encoding="utf-8")
            connected = False

            def connector(**kwargs):
                nonlocal connected
                connected = True
                raise RuntimeError("must not connect")

            exit_code = main(
                [
                    "--source", "kanjivg", "--file", str(source),
                    "--report-file", str(source / "report.jsonl"),
                ],
                environ={
                    "DB_NAME": "jlas",
                    "DB_USERNAME": "tester",
                    "DB_PASSWORD": "secret",
                },
                connector_factory=connector,
                stdout=io.StringIO(),
                stderr=io.StringIO(),
            )

        self.assertEqual(2, exit_code)
        self.assertFalse(connected)


if __name__ == "__main__":
    unittest.main()
