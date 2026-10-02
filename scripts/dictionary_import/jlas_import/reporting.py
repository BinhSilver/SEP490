"""Structured, append-only JSONL reporting for import warnings and failures."""

from __future__ import annotations

import json
from pathlib import Path


class JsonlReportWriter:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self._stream = None

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._stream = self.path.open("a", encoding="utf-8", newline="\n")
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        if self._stream is not None:
            self._stream.close()
            self._stream = None

    def write(
        self,
        batch_id: int | None,
        source_code: str,
        record_id: str,
        severity: str,
        code: str,
        message: str,
    ) -> None:
        if self._stream is None:
            raise RuntimeError("Report writer must be opened as a context manager")
        event = {
            "batch_id": batch_id,
            "source": source_code,
            "record_id": record_id,
            "severity": severity,
            "code": code,
            "message": message,
        }
        self._stream.write(json.dumps(event, ensure_ascii=False) + "\n")
        self._stream.flush()
