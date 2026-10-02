"""Command-line interface for explicit one-time dictionary imports."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

import mysql.connector

from .parsers import iter_jmdict, iter_kanjidic2, iter_kanjivg
from .pipeline import ImportMetadata, ImportPipeline, sanitize_error
from .reporting import JsonlReportWriter
from .store import MySqlDictionaryStore


_SOURCE_CODES = {
    "jmdict": "JMDICT",
    "kanjidic2": "KANJIDIC2",
    "kanjivg": "KANJIVG",
}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Import J-LAS Dictionary/Kanji base data into MySQL"
    )
    parser.add_argument("--source", choices=tuple(_SOURCE_CODES), required=True)
    parser.add_argument("--file", required=True, dest="input_path")
    parser.add_argument("--source-version")
    parser.add_argument("--chunk-size", type=int, default=500)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--report-file", default="dictionary-import-report.jsonl"
    )
    parser.add_argument(
        "--svg-uri-prefix", default="/kanjivg/kanji", help="KanjiVG URI prefix"
    )
    return parser


def main(
    argv=None,
    *,
    environ=None,
    connector_factory=None,
    stdout=None,
    stderr=None,
) -> int:
    environment = os.environ if environ is None else environ
    connect = mysql.connector.connect if connector_factory is None else connector_factory
    out = sys.stdout if stdout is None else stdout
    err = sys.stderr if stderr is None else stderr
    args = build_parser().parse_args(argv)

    input_path = Path(args.input_path)
    if not input_path.exists():
        print("error: input path does not exist", file=err)
        return 2
    if args.source != "kanjivg" and not input_path.is_file():
        print("error: JMdict and KANJIDIC2 input must be a file", file=err)
        return 2
    if args.chunk_size <= 0:
        print("error: chunk size must be positive", file=err)
        return 2

    input_resolved = input_path.resolve()
    report_resolved = Path(args.report_file).resolve()
    if report_resolved == input_resolved:
        print("error: report file must not overwrite the input", file=err)
        return 2
    if input_path.is_dir():
        try:
            report_resolved.relative_to(input_resolved)
        except ValueError:
            pass
        else:
            print("error: report file must be outside the input directory", file=err)
            return 2

    required = ("DB_NAME", "DB_USERNAME", "DB_PASSWORD")
    missing = [name for name in required if not environment.get(name)]
    if missing:
        print(f"error: missing environment variable(s): {', '.join(missing)}", file=err)
        return 2
    secrets = (environment["DB_PASSWORD"],)

    try:
        checksum = compute_checksum(input_path)
    except OSError as error:
        print(
            f"error: cannot read input: {sanitize_error(error, secrets)}", file=err
        )
        return 2

    connection = None
    try:
        connection = connect(
            host=environment.get("DB_HOST", "localhost"),
            port=int(environment.get("DB_PORT", "3306")),
            database=environment["DB_NAME"],
            user=environment["DB_USERNAME"],
            password=environment["DB_PASSWORD"],
            charset="utf8mb4",
            autocommit=False,
        )
        records = _records(args.source, input_path, args.svg_uri_prefix)
        metadata = ImportMetadata(
            source_code=_SOURCE_CODES[args.source],
            source_version=args.source_version or input_path.name,
            checksum=checksum,
            dry_run=args.dry_run,
        )
        with JsonlReportWriter(args.report_file) as report:
            summary = ImportPipeline(
                MySqlDictionaryStore(connection), report, args.chunk_size, secrets
            ).run(metadata, records)
        print(
            json.dumps(
                {
                    "batch_id": summary.batch_id,
                    "status": summary.status,
                    "total_records": summary.total,
                    "inserted_records": summary.inserted,
                    "updated_records": summary.updated,
                    "skipped_records": summary.skipped,
                    "failed_records": summary.failed,
                    "report_file": str(Path(args.report_file)),
                    "dry_run": args.dry_run,
                },
                ensure_ascii=False,
            ),
            file=out,
        )
        return 1 if summary.status == "FAILED" else 0
    except Exception as error:
        print(f"error: {sanitize_error(error, secrets)}", file=err)
        return 1
    finally:
        if connection is not None:
            connection.close()


def _records(source: str, input_path: Path, svg_uri_prefix: str):
    if source == "jmdict":
        return iter_jmdict(input_path)
    if source == "kanjidic2":
        return iter_kanjidic2(input_path)
    return iter_kanjivg(input_path, svg_uri_prefix)


def compute_checksum(path: str | Path) -> str:
    source_path = Path(path)
    digest = hashlib.sha256()
    files = [source_path] if source_path.is_file() else sorted(
        candidate for candidate in source_path.rglob("*") if candidate.is_file()
    )
    for candidate in files:
        if source_path.is_dir():
            digest.update(candidate.relative_to(source_path).as_posix().encode("utf-8"))
            digest.update(b"\0")
        with candidate.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()
