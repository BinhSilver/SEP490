#!/usr/bin/env python3
"""Export a Dictionary/Kanji test database as the Git LFS seed used by seed_bootstrap.py.

The dump holds every table/view definition of the source database but data for
only the dictionary tables listed in ``DICTIONARY_TABLES`` (no accounts, history
or other business rows). DEFINER clauses are removed so the seed restores under
any MySQL account. ``manifest.json`` is rewritten with the new file name,
checksum, size, schema fingerprint and exact row counts; the KanjiVG section is
kept unchanged.

Example::

    python scripts/dictionary_import/export_seed.py --snapshot JLAS2-34-ja-vi-2026-10-15
"""

from __future__ import annotations

import argparse
import datetime
import gzip
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

import mysql.connector

from seed_bootstrap import _find_mysql_binary

DICTIONARY_TABLES = (
    "dictionary_source",
    "dictionary_import_batch",
    "radical",
    "kanji",
    "kanji_reading",
    "vocabulary_entry",
    "vocabulary_external_reference",
    "vocabulary_reading",
    "vocabulary_variant",
    "vocabulary_sense",
    "vocabulary_gloss",
    "dictionary_part_of_speech",
    "sense_part_of_speech",
    "dictionary_domain",
    "sense_domain",
    "vocabulary_kanji",
    "kanji_component",
    "kanji_component_map",
)
SEED_DIR = Path(__file__).resolve().parents[2] / "data" / "dictionary-seed"
_DEFINER = re.compile(r"/\*!50013 DEFINER=`[^`]*`@`[^`]*` SQL SECURITY DEFINER \*/\n?|DEFINER=`[^`]*`@`[^`]*` ?")


EXTRA_ASSET_DIR = SEED_DIR.parents[1] / "src" / "main" / "resources" / "static" / "assets" / "stroke-order-extra"
EXTRA_ZIP_NAME = "stroke-order-extra.zip"


def pack_extra_stroke_order(source: Path, archive: Path) -> int:
    """Zip the extra stroke-order SVGs (files at the ZIP root) with fixed timestamps."""
    files = sorted(path for path in source.iterdir() if path.is_file())
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for path in files:
            info = zipfile.ZipInfo(path.name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            output.writestr(info, path.read_bytes())
    return len(files)


def schema_fingerprint(inventory: dict[str, int | None]) -> tuple[int, str]:
    """Same canonical form as seed_bootstrap.classify_database."""
    canonical = "".join(
        f"{'VIEW' if count is None else 'BASE TABLE'}\t{name}\n"
        for name, count in sorted(inventory.items())
    ).encode("utf-8")
    return len(inventory), hashlib.sha256(canonical).hexdigest()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--database", default="jlas_dictionary_test")
    parser.add_argument("--snapshot", required=True, help="snapshot label stored in the manifest")
    parser.add_argument("--manifest", type=Path, default=SEED_DIR / "manifest.json")
    parser.add_argument("--mysql-bin")
    args = parser.parse_args(argv)

    environment = os.environ
    host = environment.get("DB_HOST", "localhost")
    port = environment.get("DB_PORT", "3306")
    username = environment.get("DB_USERNAME")
    password = environment.get("DB_PASSWORD")
    if not username or not password:
        print("error: DB_USERNAME and DB_PASSWORD are required", file=sys.stderr)
        return 2

    connection = mysql.connector.connect(
        host=host, port=int(port), user=username, password=password, database=args.database
    )
    cursor = connection.cursor()
    cursor.execute(
        "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema = %s",
        (args.database,),
    )
    inventory: dict[str, int | None] = {}
    for table, table_type in cursor.fetchall():
        if table_type == "VIEW":
            inventory[table] = None
        elif table in DICTIONARY_TABLES:
            cursor.execute(f"SELECT COUNT(*) FROM `{table}`")
            inventory[table] = int(cursor.fetchone()[0])
        else:
            inventory[table] = 0  # data is not exported, so restored tables are empty
    connection.close()
    missing = [table for table in DICTIONARY_TABLES if table not in inventory]
    if missing:
        print(f"error: dictionary tables missing: {', '.join(missing)}", file=sys.stderr)
        return 1

    mysqldump = str(Path(_find_mysql_binary(args.mysql_bin)).with_name(
        "mysqldump.exe" if os.name == "nt" else "mysqldump"))
    common = [
        mysqldump, f"--host={host}", f"--port={port}", f"--user={username}",
        "--default-character-set=utf8mb4", "--single-transaction", "--skip-comments",
        "--skip-dump-date", "--no-tablespaces", "--skip-triggers", "--set-gtid-purged=OFF",
    ]
    dump_environment = dict(environment, MYSQL_PWD=password)
    schema = subprocess.run(common + ["--no-data", args.database],
                            capture_output=True, env=dump_environment, check=True).stdout
    data = subprocess.run(common + ["--no-create-info", args.database, *DICTIONARY_TABLES],
                          capture_output=True, env=dump_environment, check=True).stdout
    sql = _DEFINER.sub("", (schema + data).decode("utf-8")).encode("utf-8")

    created = datetime.date.today()
    seed_file = args.manifest.parent / f"jlas-dictionary-test-seed-{created:%Y%m%d}.sql.gz"
    with gzip.GzipFile(seed_file, "wb", compresslevel=9, mtime=0) as stream:
        stream.write(sql)
    digest = hashlib.sha256(seed_file.read_bytes()).hexdigest()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    previous = manifest["database"].get("file")
    object_count, object_sha = schema_fingerprint(inventory)
    manifest["snapshot"] = args.snapshot
    manifest["created_at"] = created.isoformat()
    manifest["database"] = {
        "file": seed_file.name,
        "sha256": digest,
        "size_bytes": seed_file.stat().st_size,
        "schema_object_count": object_count,
        "schema_objects_sha256": object_sha,
        "table_counts": {table: inventory[table] for table in DICTIONARY_TABLES},
    }
    if EXTRA_ASSET_DIR.is_dir():
        extra_zip = args.manifest.parent / EXTRA_ZIP_NAME
        file_count = pack_extra_stroke_order(EXTRA_ASSET_DIR, extra_zip)
        manifest["stroke_order_extra"] = {
            "file": extra_zip.name,
            "sha256": hashlib.sha256(extra_zip.read_bytes()).hexdigest(),
            "size_bytes": extra_zip.stat().st_size,
            "file_count": file_count,
            "extract_target": "src/main/resources/static/assets/stroke-order-extra",
        }
    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                             encoding="utf-8", newline="\n")
    if previous and previous != seed_file.name and (args.manifest.parent / previous).exists():
        (args.manifest.parent / previous).unlink()

    print(json.dumps({"file": seed_file.name, "size_bytes": seed_file.stat().st_size,
                      "schema_objects": object_count, "replaced": previous}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
