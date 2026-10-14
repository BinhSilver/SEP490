"""Collect public YouTube metadata/captions for the curated Shadowing seed.

Raw extraction responses stay in a local cache (not the distributable SQL).
No cookies, account login, or application credentials are used.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import sys

import requests
import yt_dlp

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / "data/shadowing/.cache"


class QuietLog:
    def debug(self, message):
        pass

    def warning(self, message):
        pass

    def error(self, message):
        pass


def extract(url: str, *, flat=False, limit=40):
    options = {
        "quiet": True, "no_warnings": True, "logger": QuietLog(),
        "skip_download": True, "socket_timeout": 20, "retries": 1,
        "extractor_retries": 1, "js_runtimes": {"node": {}},
        "extract_flat": "in_playlist" if flat else False,
        "playlistend": limit, "noplaylist": not flat,
    }
    with yt_dlp.YoutubeDL(options) as ydl:
        return ydl.extract_info(url, download=False)


def collect_video(video_id: str):
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / f"{video_id}.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    url = f"https://www.youtube.com/watch?v={video_id}"
    result = {"id": video_id, "url": url}
    try:
        meta = extract(url)
        for field in ("title", "duration", "channel", "channel_id", "channel_url",
                      "upload_date", "availability", "language", "thumbnail",
                      "license", "age_limit", "live_status"):
            result[field] = meta.get(field)
        tracks = meta.get("subtitles", {})
        auto = meta.get("automatic_captions", {})
        result["caption_languages"] = list(tracks)
        result["automatic_languages"] = [k for k in auto if k.startswith("ja")]
        selected = None
        for source, mapping in (("CREATOR", tracks), ("AUTO", auto)):
            for language in ("ja", "ja-orig"):
                if language not in mapping:
                    continue
                selected = next((t for t in mapping[language] if t["ext"] == "json3"), None)
                if selected:
                    result["caption_source"] = source
                    result["caption_language"] = language
                    break
            if selected:
                break
        if selected:
            response = requests.get(selected["url"], timeout=30)
            response.raise_for_status()
            result["captions"] = response.json()
        else:
            result["caption_error"] = "No Japanese caption track exposed by source"
        result["checked_on"] = datetime.now(timezone(timedelta(hours=7))).date().isoformat()
    except Exception as error:
        result["error"] = re.sub(r"\x1b\[[0-9;]*m", "", str(error))[:600]
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("urls", nargs="*")
    parser.add_argument("--flat", action="store_true")
    parser.add_argument("--limit", type=int, default=40)
    args = parser.parse_args()
    if args.flat:
        for url in args.urls:
            name = re.sub(r"[^A-Za-z0-9_-]", "_", url)[-100:]
            CACHE.mkdir(parents=True, exist_ok=True)
            try:
                meta = extract(url, flat=True, limit=args.limit)
                rows = [{k: row.get(k) for k in ("id", "title", "duration", "url", "channel")}
                        for row in meta.get("entries", []) if row]
                result = {"url": url, "title": meta.get("title"), "entries": rows}
                (CACHE / f"list_{name}.json").write_text(
                    json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                print(json.dumps(result, ensure_ascii=False), flush=True)
            except Exception as error:
                print(json.dumps({"url": url, "error": str(error)}, ensure_ascii=False), flush=True)
        return
    video_ids = []
    for value in args.urls:
        match = re.search(r"(?:v=|youtu.be/|shorts/)([\w-]{11})", value)
        video_ids.append(match[1] if match else value)
    with ThreadPoolExecutor(max_workers=4) as executor:
        for result in executor.map(collect_video, dict.fromkeys(video_ids)):
            print(json.dumps({k: v for k, v in result.items() if k not in ("captions", "automatic_languages")}
                             | {"caption_events": len(result.get("captions", {}).get("events", []))},
                             ensure_ascii=False), flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
