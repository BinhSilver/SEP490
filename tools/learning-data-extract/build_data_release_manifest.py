"""Build an immutable manifest for data workbooks and source files."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def _resolved_role(path: Path, roles: dict[Path, str]) -> str:
    resolved = path.resolve()
    for candidate, role in roles.items():
        if Path(candidate).resolve() == resolved:
            return role
    return "SOURCE"


def build_manifest(paths: list[Path], roles: dict[Path, str] | None = None) -> dict:
    roles = roles or {}
    files = []
    for path in sorted((Path(value).resolve() for value in paths), key=str):
        if not path.is_file():
            raise FileNotFoundError(f"Source file does not exist: {path}")
        payload = path.read_bytes()
        stat = path.stat()
        files.append({
            "path": str(path),
            "sha256": hashlib.sha256(payload).hexdigest(),
            "size": len(payload),
            "modified_at": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat(),
            "role": _resolved_role(path, roles),
        })
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "files": files,
    }


def write_manifest(manifest: dict, output: Path) -> None:
    output = Path(output)
    if output.exists():
        raise FileExistsError(f"Manifest already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


def collect_files(inputs: list[Path]) -> list[Path]:
    files = []
    for item in inputs:
        item = item.resolve()
        if item.is_dir():
            files.extend(path for path in item.rglob("*") if path.is_file())
        elif item.is_file():
            files.append(item)
        else:
            raise FileNotFoundError(f"Source path does not exist: {item}")
    return files


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    files = collect_files(args.inputs)
    manifest = build_manifest(files)
    write_manifest(manifest, args.output)
    print(json.dumps({"output": str(args.output.resolve()), "files": len(files)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
