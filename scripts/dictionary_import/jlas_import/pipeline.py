"""Import orchestration, transaction boundaries, counters, and safe reporting."""

from __future__ import annotations

import re
from dataclasses import dataclass, replace
from typing import Iterable

from .models import ImportWarning, InvalidRecord


_SECRET = re.compile(
    r"(?i)\b(?:password|passwd|pwd|secret|token|api[_-]?key)\s*[:=]\s*[^\s,;]+"
)
_WHITESPACE = re.compile(r"\s+")


@dataclass(frozen=True, slots=True)
class ImportMetadata:
    source_code: str
    source_version: str | None
    checksum: str
    dry_run: bool = False


@dataclass(frozen=True, slots=True)
class ImportOutcome:
    action: str
    warnings: tuple[ImportWarning, ...] = ()

    def __post_init__(self):
        if self.action not in {"inserted", "updated", "skipped"}:
            raise ValueError(f"Unsupported import action: {self.action}")


@dataclass(frozen=True, slots=True)
class ImportSummary:
    status: str
    total: int = 0
    inserted: int = 0
    updated: int = 0
    skipped: int = 0
    failed: int = 0
    batch_id: int | None = None
    error_message: str | None = None

    @property
    def mutation_counters(self) -> tuple[int, int, int, int]:
        return self.inserted, self.updated, self.skipped, self.failed


class ImportPipeline:
    def __init__(self, store, report_writer, chunk_size: int = 500):
        if chunk_size <= 0:
            raise ValueError("chunk_size must be positive")
        self.store = store
        self.report_writer = report_writer
        self.chunk_size = chunk_size

    def run(self, metadata: ImportMetadata, records: Iterable) -> ImportSummary:
        source_id = self.store.require_active_source(metadata.source_code)
        if not metadata.dry_run and self.store.find_completed_batch(
            source_id, metadata.source_version, metadata.checksum
        ):
            batch_id = self.store.create_skipped_batch(
                source_id, metadata.source_version, metadata.checksum
            )
            return ImportSummary(status="SKIPPED", batch_id=batch_id)

        batch_id = None
        if metadata.dry_run:
            self.store.begin_dry_run()
        else:
            batch_id = self.store.start_batch(
                source_id, metadata.source_version, metadata.checksum
            )

        summary = ImportSummary(status="RUNNING", batch_id=batch_id)
        processed_in_chunk = 0
        pending_inserted = 0
        pending_updated = 0
        try:
            for sequence, record in enumerate(records, start=1):
                summary = replace(summary, total=summary.total + 1)
                processed_in_chunk += 1
                record_id = getattr(record, "record_id", f"record-{sequence}")

                if isinstance(record, InvalidRecord):
                    summary = replace(summary, failed=summary.failed + 1)
                    self._report(
                        batch_id,
                        metadata.source_code,
                        record_id,
                        "ERROR",
                        "RECORD_VALIDATION_FAILED",
                        record.message,
                    )
                else:
                    for warning in getattr(record, "warnings", ()):
                        self._write_warning(batch_id, metadata.source_code, record_id, warning)
                    savepoint = self.store.savepoint(f"record_{sequence}")
                    try:
                        outcome = self.store.import_record(source_id, record)
                        summary = self._count_outcome(summary, outcome.action)
                        if outcome.action == "inserted":
                            pending_inserted += 1
                        elif outcome.action == "updated":
                            pending_updated += 1
                        for warning in outcome.warnings:
                            self._write_warning(
                                batch_id, metadata.source_code, record_id, warning
                            )
                        self.store.release_savepoint(savepoint)
                    except Exception as error:  # one bad source record is recoverable
                        self.store.rollback_to_savepoint(savepoint)
                        self.store.release_savepoint(savepoint)
                        summary = replace(summary, failed=summary.failed + 1)
                        self._report(
                            batch_id,
                            metadata.source_code,
                            record_id,
                            "ERROR",
                            "RECORD_IMPORT_FAILED",
                            sanitize_error(error),
                        )

                if processed_in_chunk >= self.chunk_size:
                    if not metadata.dry_run:
                        self.store.commit_chunk(batch_id, summary)
                    processed_in_chunk = 0
                    pending_inserted = 0
                    pending_updated = 0

            if processed_in_chunk and not metadata.dry_run:
                self.store.commit_chunk(batch_id, summary)
            status = "COMPLETED_WITH_ERRORS" if summary.failed else "COMPLETED"
            summary = replace(summary, status=status)
            if metadata.dry_run:
                self.store.rollback()
            else:
                self.store.finish_batch(batch_id, summary)
            return summary
        except Exception as error:  # malformed stream/connection loss is fatal
            self.store.rollback()
            message = sanitize_error(error)
            summary = replace(
                summary,
                status="FAILED",
                inserted=max(0, summary.inserted - pending_inserted),
                updated=max(0, summary.updated - pending_updated),
                error_message=message,
            )
            if not metadata.dry_run:
                try:
                    self.store.finish_batch(batch_id, summary, error_message=message)
                except Exception:
                    # A lost database connection cannot update its own audit row.
                    pass
            return summary

    @staticmethod
    def _count_outcome(summary: ImportSummary, action: str) -> ImportSummary:
        if action == "inserted":
            return replace(summary, inserted=summary.inserted + 1)
        if action == "updated":
            return replace(summary, updated=summary.updated + 1)
        if action == "skipped":
            return replace(summary, skipped=summary.skipped + 1)
        raise ValueError(f"Unsupported import action: {action}")

    def _write_warning(
        self, batch_id, source_code: str, record_id: str, warning: ImportWarning
    ) -> None:
        self._report(
            batch_id, source_code, record_id, "WARNING", warning.code, warning.message
        )

    def _report(
        self,
        batch_id,
        source_code: str,
        record_id: str,
        severity: str,
        code: str,
        message: str,
    ) -> None:
        self.report_writer.write(
            batch_id,
            source_code,
            str(record_id),
            severity,
            code,
            sanitize_message(message),
        )


def sanitize_error(error: BaseException) -> str:
    return sanitize_message(str(error) or error.__class__.__name__)


def sanitize_message(message: str) -> str:
    redacted = _SECRET.sub("[redacted]", message or "")
    return _WHITESPACE.sub(" ", redacted).strip()[:2000]
