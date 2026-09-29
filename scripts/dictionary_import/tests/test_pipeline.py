import unittest

from scripts.dictionary_import.jlas_import.models import InvalidRecord
from scripts.dictionary_import.jlas_import.pipeline import (
    ImportMetadata,
    ImportOutcome,
    ImportPipeline,
)


class MemoryReport:
    def __init__(self):
        self.events = []

    def write(self, batch_id, source_code, record_id, severity, code, message):
        self.events.append((batch_id, source_code, record_id, severity, code, message))


class FatalConnectionError(RuntimeError):
    pass


class RecordingStore:
    def __init__(self, completed=False, fail_ids=(), fatal_ids=()):
        self.completed = completed
        self.fail_ids = set(fail_ids)
        self.fatal_ids = set(fatal_ids)
        self.batch_statuses = []
        self.mutated_ids = []
        self.rolled_back_records = []
        self.chunk_commits = []
        self.dry_run_started = False
        self.full_rollbacks = 0

    def require_active_source(self, code):
        return 7

    def find_completed_batch(self, source_id, version, checksum):
        return 41 if self.completed else None

    def create_skipped_batch(self, source_id, version, checksum):
        self.batch_statuses.append("SKIPPED")
        return 42

    def start_batch(self, source_id, version, checksum):
        self.batch_statuses.append("RUNNING")
        return 43

    def begin_dry_run(self):
        self.dry_run_started = True

    def savepoint(self, name):
        return name

    def import_record(self, source_id, record):
        if record.record_id in self.fatal_ids:
            raise FatalConnectionError("connection lost")
        if record.record_id in self.fail_ids:
            raise ValueError(f"bad record {record.record_id}")
        self.mutated_ids.append(record.record_id)
        return ImportOutcome("inserted")

    def rollback_to_savepoint(self, savepoint):
        self.rolled_back_records.append(savepoint.removeprefix("record_"))

    def release_savepoint(self, savepoint):
        pass

    def commit_chunk(self, batch_id, summary):
        self.chunk_commits.append(summary.mutation_counters)

    def rollback(self):
        self.full_rollbacks += 1

    def is_fatal_error(self, error):
        return isinstance(error, FatalConnectionError)

    def finish_batch(self, batch_id, summary, error_message=None):
        self.batch_statuses.append(summary.status)


class Record:
    def __init__(self, record_id):
        self.record_id = record_id
        self.warnings = ()


def fatal_records():
    yield Record("committed-one")
    yield Record("committed-two")
    yield Record("pending")
    raise OSError("password=must-not-leak transport failed")


class ImportPipelineTest(unittest.TestCase):

    metadata = ImportMetadata("JMDICT", "2026-09", "a" * 64)

    def test_import_metadata_requires_sha256_checksum(self):
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            ImportMetadata("JMDICT", "2026-09", "not-a-checksum")

    def test_identical_completed_checksum_creates_skipped_audit_batch(self):
        store = RecordingStore(completed=True)

        summary = ImportPipeline(store, MemoryReport(), chunk_size=2).run(
            self.metadata, []
        )

        self.assertEqual("SKIPPED", summary.status)
        self.assertEqual((0, 0, 0, 0), summary.mutation_counters)
        self.assertEqual([], store.mutated_ids)
        self.assertEqual(["SKIPPED"], store.batch_statuses)

    def test_empty_source_fails_instead_of_completing_zero_records(self):
        store = RecordingStore()

        summary = ImportPipeline(store, MemoryReport(), chunk_size=2).run(
            self.metadata, []
        )

        self.assertEqual("FAILED", summary.status)
        self.assertEqual(0, summary.total)
        self.assertIn("no importable records", summary.error_message)
        self.assertEqual(["RUNNING", "FAILED"], store.batch_statuses)

    def test_all_invalid_records_fail_instead_of_completing_with_errors(self):
        store = RecordingStore()

        summary = ImportPipeline(store, MemoryReport(), chunk_size=2).run(
            self.metadata,
            [
                InvalidRecord("bad-1", "reading required"),
                InvalidRecord("bad-2", "expression required"),
            ],
        )

        self.assertEqual("FAILED", summary.status)
        self.assertEqual(2, summary.total)
        self.assertEqual(2, summary.failed)
        self.assertIn("no importable records", summary.error_message)
        self.assertEqual(["RUNNING", "FAILED"], store.batch_statuses)

    def test_failed_record_rolls_back_only_its_savepoint_and_continues(self):
        store = RecordingStore(fail_ids={"bad"})
        report = MemoryReport()

        summary = ImportPipeline(store, report, chunk_size=2).run(
            self.metadata,
            [Record("one"), Record("bad"), InvalidRecord("invalid", "reading required"), Record("two")],
        )

        self.assertEqual("COMPLETED_WITH_ERRORS", summary.status)
        self.assertEqual(4, summary.total)
        self.assertEqual((2, 0, 0, 2), summary.mutation_counters)
        self.assertEqual(1, len(store.rolled_back_records))
        self.assertEqual(["one", "two"], store.mutated_ids)
        self.assertEqual({"RECORD_IMPORT_FAILED", "RECORD_VALIDATION_FAILED"}, {row[4] for row in report.events})

    def test_dry_run_rolls_back_and_never_creates_batch(self):
        store = RecordingStore()
        metadata = ImportMetadata("JMDICT", "2026-09", "b" * 64, dry_run=True)

        summary = ImportPipeline(store, MemoryReport(), chunk_size=2).run(
            metadata, [Record("one")]
        )

        self.assertEqual("COMPLETED", summary.status)
        self.assertTrue(store.dry_run_started)
        self.assertEqual(1, store.full_rollbacks)
        self.assertEqual([], store.batch_statuses)
        self.assertEqual([], store.chunk_commits)

    def test_fatal_stream_error_rolls_back_uncommitted_chunk_and_sanitizes_batch_error(self):
        store = RecordingStore()

        summary = ImportPipeline(store, MemoryReport(), chunk_size=2).run(
            self.metadata, fatal_records()
        )

        self.assertEqual("FAILED", summary.status)
        self.assertEqual(3, summary.total)
        self.assertEqual((2, 0, 0, 1), summary.mutation_counters)
        self.assertEqual(1, store.full_rollbacks)
        self.assertEqual(["RUNNING", "FAILED"], store.batch_statuses)
        self.assertNotIn("password", summary.error_message.lower())
        self.assertNotIn("must-not-leak", summary.error_message)

    def test_connection_failure_stops_processing_instead_of_skipping_one_record(self):
        store = RecordingStore(fatal_ids={"disconnect"})

        summary = ImportPipeline(store, MemoryReport(), chunk_size=10).run(
            self.metadata,
            [Record("one"), Record("disconnect"), Record("must-not-run")],
        )

        self.assertEqual("FAILED", summary.status)
        self.assertEqual(2, summary.total)
        self.assertEqual((0, 0, 0, 2), summary.mutation_counters)
        self.assertEqual(["one"], store.mutated_ids)
        self.assertEqual(1, store.full_rollbacks)


if __name__ == "__main__":
    unittest.main()
