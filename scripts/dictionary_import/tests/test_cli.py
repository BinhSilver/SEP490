import io
import json
import tempfile
import unittest
from pathlib import Path

from scripts.dictionary_import.jlas_import.cli import compute_checksum, main
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


if __name__ == "__main__":
    unittest.main()
