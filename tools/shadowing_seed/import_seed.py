"""Validate and stream local captions plus the combined seed to a fresh MySQL session.

Does not read application credentials, call AI APIs, download video, or publish by
default. --execute is explicit; without it this command only validates inputs.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from validate import CACHE, DATA, ROOT, load_payload, read_json, validate


def json_set(name, value):
    raw = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8").hex()
    return f"SET @{name}=CONVERT(0x{raw} USING utf8mb4);\n"


def sql_input(owner_id, payload, media=(), publish=False):
    if owner_id <= 0:
        raise ValueError("owner-id must reference an existing positive users.id")
    bootstrap = f"SET @shadowing_seed_owner_id={int(owner_id)};\n"
    bootstrap += f"SET @shadowing_seed_publish={int(publish)};\n"
    bootstrap += json_set("shadowing_seed_subtitles_json", payload)
    bootstrap += json_set("shadowing_seed_media_json", list(media))
    # No intermediate file containing full captions is written.
    return (bootstrap + (ROOT / "sql/seeds/seed_data_shadowing.sql").read_text(encoding="utf-8")).encode("utf-8")


def media_input(directory):
    directory = Path(directory).resolve(strict=True) if directory else None
    probe = shutil.which("ffprobe")
    if not probe:
        raise ValueError("ffprobe must be installed to verify supplied local media")
    result = []
    fingerprint_path = DATA / "media_fingerprints.json"
    fingerprints = {r["source_key"]:r for r in read_json(fingerprint_path)} if fingerprint_path.is_file() else {}
    for video in read_json(DATA / "catalog.json")["videos"]:
        source = video["source_key"]
        if directory:
            candidates = [directory / f"{source}.mp4"]
            if video.get("local_video_path"):
                candidates.append(directory / Path(video["local_video_path"]).name)
            path = next((p for p in candidates if p.is_file()), candidates[0])
        elif video.get("source_type") == "LOCAL":
            path = ROOT / video["local_video_path"]
        else:
            path = ROOT / "uploads/video/shadowing-curated" / f"{source}.mp4"
        if not path.is_file():
            continue
        path = path.resolve(strict=True)
        expected_sha = video.get("file_sha256") or fingerprints.get(source,{}).get("sha256")
        if expected_sha:
            with path.open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != expected_sha:
                    raise ValueError(f"{source}: local file identity differs from reviewed media")
        probe_result = subprocess.run([probe, "-v", "error", "-show_entries",
            "format=duration:stream=codec_type", "-of", "json", str(path)],
            capture_output=True, check=True, timeout=30)
        info = json.loads(probe_result.stdout)
        if not {"audio", "video"} <= {s["codec_type"] for s in info.get("streams", [])}:
            raise ValueError(f"{source}: local file must contain audio and video")
        duration = float(info["format"]["duration"])
        expected = video["duration_seconds"]
        if abs(duration - expected) > max(2, expected * .01):
            raise ValueError(f"{source}: local duration differs from source; do not attach unrelated media")
        thumbnail = next((path.parent / f"{source}.{ext}" for ext in ("jpg", "png", "webp")
                          if (path.parent / f"{source}.{ext}").is_file()), None)
        if thumbnail is None and video.get("local_thumbnail_path"):
            candidate = ROOT / video["local_thumbnail_path"]
            if candidate.is_file():
                thumbnail = candidate.resolve()
        expected_thumbnail=fingerprints.get(source,{}).get('thumbnail_sha256')
        if thumbnail and expected_thumbnail and hashlib.sha256(thumbnail.read_bytes()).hexdigest()!=expected_thumbnail:
            raise ValueError(f'{source}: thumbnail differs from the reviewed frame')
        result.append({"source_key": source, "video_path": path.as_posix(),
                       "thumbnail_path": thumbnail.as_posix() if thumbnail else None})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--caption-dir", type=Path, default=CACHE)
    parser.add_argument("--fetch-public-captions", action="store_true", help="Fetch missing public JSON3 captions, without cookies")
    parser.add_argument("--media-root", type=Path, help="Readable by the application; files named <source_key>.mp4")
    parser.add_argument("--owner-id", type=int)
    parser.add_argument("--execute", action="store_true", help="Import after validation, using a NEW connection")
    parser.add_argument("--publish", action="store_true", help="Publish new sources with media/captions and no unresolved local audio review")
    parser.add_argument("--mysql", default=shutil.which("mysql"))
    parser.add_argument("--database")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user")
    parser.add_argument("--login-path", help="Login path configured by mysql_config_editor")
    parser.add_argument("--password-env", help="Name of env variable holding DB password; value never printed")
    args = parser.parse_args()
    if args.fetch_public_captions:
        if args.caption_dir.resolve() != CACHE.resolve():
            parser.error("--fetch-public-captions uses the default local cache directory")
        from collect_sources import collect_video
        for video in read_json(DATA / "catalog.json")["videos"]:
            if video["expected_cue_count"] and video.get("source_type") != "LOCAL":
                collect_video(video["source_key"])
    payload = load_payload(args.caption_dir)
    report = validate(payload, require_enrichment=True)
    media = media_input(args.media_root)
    report["local_media"] = len(media)
    print(json.dumps(report, ensure_ascii=True, indent=2), flush=True)
    if not args.execute:
        print("Validation only: no database or paid API was contacted.")
        return
    if not args.database or not args.owner_id or not args.mysql:
        parser.error("--execute requires --database, --owner-id and --mysql (or mysql on PATH)")
    command = [args.mysql]
    if args.login_path:
        command.append(f"--login-path={args.login_path}")  # MySQL requires defaults options first.
    command += ["--default-character-set=utf8mb4", "--binary-mode", "--batch", "--raw",
                "--protocol=TCP", f"--host={args.host}", f"--port={args.port}", args.database]
    if args.user:
        command.append(f"--user={args.user}")
    env = dict(os.environ)
    if args.password_env:
        if args.password_env not in env:
            parser.error("Named database password environment variable is absent")
        env["MYSQL_PWD"] = env[args.password_env]
    result = subprocess.run(command, input=sql_input(args.owner_id, payload, media, args.publish), env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    sys.stdout.buffer.write(result.stdout)
    if result.returncode:
        sys.stderr.buffer.write(result.stderr)
        raise SystemExit(result.returncode)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, AssertionError, FileNotFoundError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
