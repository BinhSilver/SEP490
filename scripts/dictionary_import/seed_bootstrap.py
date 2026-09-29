"""Install a versioned dictionary test snapshot and KanjiVG assets."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

import mysql.connector


class BootstrapError(RuntimeError):
    """A safe, actionable bootstrap validation failure."""


_PROJECT_ROOT = Path(__file__).resolve().parents[2]
_DEFAULT_MANIFEST = _PROJECT_ROOT / "data" / "dictionary-seed" / "manifest.json"
_DEFAULT_ASSET_TARGET = (
    _PROJECT_ROOT / "src" / "main" / "resources" / "static" / "assets" / "kanjivg"
)


def validate_test_database_name(name):
    if not re.fullmatch(r"[A-Za-z0-9_]+", name or "") or not name.endswith("_test"):
        raise BootstrapError(
            "database name must contain only letters, digits, underscores and end in _test"
        )
    return name


def classify_database(
    actual_counts,
    expected_counts,
    expected_schema_object_count=None,
    expected_schema_sha256=None,
):
    relevant = {table: int(actual_counts.get(table, 0)) for table in expected_counts}
    if not actual_counts:
        return "EMPTY"
    if expected_schema_object_count is not None or expected_schema_sha256 is not None:
        canonical_schema = "".join(
            f"{'VIEW' if count is None else 'BASE TABLE'}\t{name}\n"
            for name, count in sorted(actual_counts.items())
        ).encode("utf-8")
        actual_schema_sha256 = hashlib.sha256(canonical_schema).hexdigest()
        if (
            len(actual_counts) != int(expected_schema_object_count)
            or actual_schema_sha256.lower() != str(expected_schema_sha256).lower()
        ):
            raise BootstrapError(
                "dictionary database is partially populated; schema objects do not match"
            )
        non_dictionary_rows = [
            count
            for table, count in actual_counts.items()
            if table not in expected_counts and count is not None and int(count) != 0
        ]
        if non_dictionary_rows:
            raise BootstrapError(
                "dictionary database is partially populated; business tables are not empty"
            )
    if relevant == {table: int(count) for table, count in expected_counts.items()}:
        return "INSTALLED"
    raise BootstrapError(
        "dictionary database is partially populated; use a fresh _test database "
        "or explicitly replace it"
    )


def verify_artifact(path, expected_sha256, expected_size):
    artifact = Path(path)
    if not artifact.is_file():
        raise BootstrapError(f"artifact does not exist: {artifact}")
    if artifact.stat().st_size != int(expected_size):
        raise BootstrapError(f"artifact size mismatch: {artifact}")
    digest = hashlib.sha256()
    with artifact.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest().lower() != str(expected_sha256).lower():
        raise BootstrapError(f"artifact checksum mismatch: {artifact}")


def extract_zip_safely(archive_path, destination, expected_file_count=None):
    target_root = Path(destination).resolve()
    target_root.parent.mkdir(parents=True, exist_ok=True)
    entries = []
    names = set()
    with zipfile.ZipFile(archive_path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            unix_mode = info.external_attr >> 16
            if stat.S_ISLNK(unix_mode):
                raise BootstrapError(f"unsafe path in ZIP archive: {info.filename}")
            if "\\" in info.filename:
                raise BootstrapError(f"unsafe path in ZIP archive: {info.filename}")
            relative = PurePosixPath(info.filename)
            if relative.is_absolute() or ".." in relative.parts:
                raise BootstrapError(f"unsafe path in ZIP archive: {info.filename}")
            if (
                len(relative.parts) != 2
                or relative.parts[0] != "kanji"
                or relative.suffix.lower() != ".svg"
            ):
                raise BootstrapError(
                    f"KanjiVG ZIP files must be directly under kanji/: {info.filename}"
                )
            normalized_name = relative.as_posix().casefold()
            if normalized_name in names:
                raise BootstrapError(f"duplicate path in ZIP archive: {info.filename}")
            names.add(normalized_name)
            entries.append((info, relative))
        if expected_file_count is not None and len(entries) != int(expected_file_count):
            raise BootstrapError(
                f"KanjiVG file count mismatch: expected {expected_file_count}, "
                f"got {len(entries)}"
            )

        temporary_root = Path(
            tempfile.mkdtemp(prefix=f".{target_root.name}-new-", dir=target_root.parent)
        )
        try:
            for info, relative in entries:
                target = temporary_root.joinpath(*relative.parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(info) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
        except Exception:
            shutil.rmtree(temporary_root, ignore_errors=True)
            raise

    backup_parent = None
    previous_root = None
    if target_root.exists():
        backup_parent = Path(
            tempfile.mkdtemp(prefix=f".{target_root.name}-old-", dir=target_root.parent)
        )
        previous_root = backup_parent / "previous"
        os.replace(target_root, previous_root)
    extracted = 0
    try:
        os.replace(temporary_root, target_root)
        extracted = len(entries)
    except Exception:
        if previous_root is not None and previous_root.exists() and not target_root.exists():
            os.replace(previous_root, target_root)
        raise
    finally:
        if temporary_root.exists():
            shutil.rmtree(temporary_root, ignore_errors=True)
    if backup_parent is not None:
        shutil.rmtree(backup_parent, ignore_errors=True)
    return extracted


def _parser():
    parser = argparse.ArgumentParser(
        description="Install the versioned J-LAS dictionary snapshot into a test database"
    )
    parser.add_argument("--manifest", default=str(_DEFAULT_MANIFEST))
    parser.add_argument("--database", default="jlas_dictionary_test")
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    parser.add_argument("--username")
    parser.add_argument("--mysql-bin")
    parser.add_argument("--asset-target", default=str(_DEFAULT_ASSET_TARGET))
    parser.add_argument(
        "--replace",
        action="store_true",
        help="drop and recreate the selected _test database before restore",
    )
    return parser


def _load_manifest(path):
    manifest_path = Path(path).resolve()
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BootstrapError(f"cannot read manifest: {manifest_path}") from error
    if manifest.get("format_version") != 1:
        raise BootstrapError("unsupported dictionary seed manifest format")
    for section in ("database", "kanjivg"):
        artifact = manifest.get(section)
        if not isinstance(artifact, dict):
            raise BootstrapError(f"manifest is missing {section} artifact metadata")
        for field in ("file", "sha256", "size_bytes"):
            if field not in artifact:
                raise BootstrapError(f"manifest {section} is missing {field}")
    if not isinstance(manifest["database"].get("table_counts"), dict):
        raise BootstrapError("manifest database is missing table_counts")
    for field in ("schema_object_count", "schema_objects_sha256"):
        if field not in manifest["database"]:
            raise BootstrapError(f"manifest database is missing {field}")
    if "file_count" not in manifest["kanjivg"]:
        raise BootstrapError("manifest kanjivg is missing file_count")
    return manifest_path, manifest


def _artifact_path(manifest_path, filename):
    root = manifest_path.parent.resolve()
    candidate = (root / filename).resolve()
    try:
        candidate.relative_to(root)
    except ValueError as error:
        raise BootstrapError("artifact path escapes the manifest directory") from error
    return candidate


def _find_mysql_binary(explicit):
    if explicit:
        candidate = Path(explicit)
        if candidate.is_file():
            return str(candidate.resolve())
        resolved = shutil.which(explicit)
        if resolved:
            return resolved
        raise BootstrapError(f"mysql client not found: {explicit}")
    resolved = shutil.which("mysql")
    if resolved:
        return resolved
    windows_default = Path(
        r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
    )
    if windows_default.is_file():
        return str(windows_default)
    raise BootstrapError("mysql client not found; pass --mysql-bin")


def _connect_server(connect, host, port, username, password):
    return connect(
        host=host,
        port=port,
        user=username,
        password=password,
        charset="utf8mb4",
        autocommit=True,
    )


def _database_inventory(connection, database):
    inventory = {}
    cursor = connection.cursor()
    try:
        cursor.execute(
            "SELECT table_name, table_type FROM information_schema.tables "
            "WHERE table_schema = %s",
            (database,),
        )
        existing = {row[0]: row[1] for row in cursor.fetchall()}
        for table, table_type in existing.items():
            if not re.fullmatch(r"[A-Za-z0-9_]+", table):
                raise BootstrapError(f"unsafe table name in manifest: {table}")
            if table_type == "BASE TABLE":
                cursor.execute(f"SELECT COUNT(*) FROM `{database}`.`{table}`")
                inventory[table] = int(cursor.fetchone()[0])
            elif table_type == "VIEW":
                inventory[table] = None
            else:
                raise BootstrapError(f"unsupported schema object type: {table_type}")
    finally:
        cursor.close()
    return inventory


def _recreate_database(connection, database):
    cursor = connection.cursor()
    try:
        cursor.execute(f"DROP DATABASE IF EXISTS `{database}`")
        cursor.execute(
            f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4 "
            "COLLATE utf8mb4_unicode_ci"
        )
    finally:
        cursor.close()


def _ensure_database(connection, database):
    cursor = connection.cursor()
    try:
        cursor.execute(
            f"CREATE DATABASE IF NOT EXISTS `{database}` CHARACTER SET utf8mb4 "
            "COLLATE utf8mb4_unicode_ci"
        )
    finally:
        cursor.close()


def _restore_sql_gzip(mysql_binary, seed_path, host, port, username, password, database):
    command = [
        mysql_binary,
        f"--host={host}",
        f"--port={port}",
        f"--user={username}",
        f"--database={database}",
        "--default-character-set=utf8mb4",
        "--binary-mode=1",
    ]
    process_environment = os.environ.copy()
    process_environment["MYSQL_PWD"] = password
    result = _run_gzip_command(seed_path, command, process_environment)
    if result.returncode:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        if password:
            detail = detail.replace(password, "[redacted]")
        raise BootstrapError(f"mysql restore failed: {detail[:1000]}")


def _run_gzip_command(seed_path, command, process_environment):
    with tempfile.TemporaryFile() as error_stream:
        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=error_stream,
            env=process_environment,
        )
        try:
            with gzip.open(seed_path, "rb") as sql_stream:
                try:
                    shutil.copyfileobj(sql_stream, process.stdin, length=1024 * 1024)
                except BrokenPipeError:
                    pass
        finally:
            if process.stdin is not None:
                process.stdin.close()
        return_code = process.wait()
        error_stream.seek(0)
        stderr = error_stream.read()
    return subprocess.CompletedProcess(command, return_code, stderr=stderr)


def main(argv=None, *, environ=None, stdout=None, stderr=None):
    environment = os.environ if environ is None else environ
    out = sys.stdout if stdout is None else stdout
    err = sys.stderr if stderr is None else stderr
    args = _parser().parse_args(argv)
    try:
        database = validate_test_database_name(args.database)
    except BootstrapError as error:
        print(f"error: {error}", file=err)
        return 2

    host = args.host or environment.get("DB_HOST", "localhost")
    port = args.port or int(environment.get("DB_PORT", "3306"))
    username = args.username or environment.get("DB_USERNAME")
    password = environment.get("DB_PASSWORD")
    if not username or not password:
        print("error: DB_USERNAME and DB_PASSWORD are required", file=err)
        return 2

    connection = None
    try:
        manifest_path, manifest = _load_manifest(args.manifest)
        database_metadata = manifest["database"]
        kanjivg_metadata = manifest["kanjivg"]
        database_artifact = _artifact_path(
            manifest_path, database_metadata["file"]
        )
        kanjivg_artifact = _artifact_path(
            manifest_path, kanjivg_metadata["file"]
        )
        verify_artifact(
            database_artifact,
            database_metadata["sha256"],
            database_metadata["size_bytes"],
        )
        verify_artifact(
            kanjivg_artifact,
            kanjivg_metadata["sha256"],
            kanjivg_metadata["size_bytes"],
        )
        mysql_binary = _find_mysql_binary(args.mysql_bin)
        expected_counts = {
            table: int(count)
            for table, count in database_metadata["table_counts"].items()
        }
        expected_schema_object_count = int(
            database_metadata["schema_object_count"]
        )
        expected_schema_sha256 = database_metadata["schema_objects_sha256"]

        connection = _connect_server(
            mysql.connector.connect, host, port, username, password
        )
        inventory = _database_inventory(connection, database)
        if args.replace:
            _recreate_database(connection, database)
            state = "EMPTY"
        else:
            state = classify_database(
                inventory,
                expected_counts,
                expected_schema_object_count,
                expected_schema_sha256,
            )
            if state == "EMPTY":
                _ensure_database(connection, database)

        if state == "EMPTY":
            connection.close()
            connection = None
            _restore_sql_gzip(
                mysql_binary,
                database_artifact,
                host,
                port,
                username,
                password,
                database,
            )
            connection = _connect_server(
                mysql.connector.connect, host, port, username, password
            )
            restored = _database_inventory(connection, database)
            if classify_database(
                restored,
                expected_counts,
                expected_schema_object_count,
                expected_schema_sha256,
            ) != "INSTALLED":
                raise BootstrapError("restored database counts do not match manifest")

        expected_files = int(kanjivg_metadata["file_count"])
        extracted = extract_zip_safely(
            kanjivg_artifact, args.asset_target, expected_files
        )
        print(
            json.dumps(
                {
                    "status": "ALREADY_INSTALLED" if state == "INSTALLED" else "INSTALLED",
                    "database": database,
                    "snapshot": manifest.get("snapshot"),
                    "dictionary_tables": len(expected_counts),
                    "kanjivg_files": extracted,
                    "asset_target": str(Path(args.asset_target).resolve()),
                },
                ensure_ascii=False,
            ),
            file=out,
        )
        return 0
    except (BootstrapError, OSError, mysql.connector.Error) as error:
        message = str(error)
        if password:
            message = message.replace(password, "[redacted]")
        print(f"error: {message}", file=err)
        return 1
    finally:
        if connection is not None:
            connection.close()


if __name__ == "__main__":
    raise SystemExit(main())
