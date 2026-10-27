"""Turn an editor's reviewed transcript (TSV) into the cue cache the seed reads (<video>.cues.json).

TSV columns, one spoken line per row (header row required):
  start  end  text  translation  review
`start`/`end` in seconds; `text` the Japanese line as decided after cross-checking (compare_asr.py);
`translation` the Vietnamese line written by the editor; `review` = "1" when the line is still uncertain after
the third listen (requires_audio_review: kept for playback, never used for questions).
Furigana is generated with Fugashi plus data/shadowing/reading_overrides.json, in the word-level format the
application's answer matcher reads.
"""
from __future__ import annotations
import argparse
import csv
import json
from pathlib import Path

from enrich_offline import reading
from validate import CACHE, DATA


def _write(path, text):
    """Windows can briefly lock a file another process is scanning (OSError 22); retry instead of failing."""
    import time
    for attempt in range(10):
        try:
            path.write_text(text, encoding="utf-8", newline="\n")
            return
        except OSError:
            if attempt == 9:
                raise
            time.sleep(1)


def build(video_id, tsv_path, local=False):
    import fugashi
    tagger = fugashi.Tagger()
    rows = []
    with open(tsv_path, encoding="utf-8", newline="") as stream:
        for line in csv.DictReader(stream, delimiter="\t"):
            text = line["text"].strip()
            if not text:
                continue
            row = {"start": round(float(line["start"]), 3), "end": round(float(line["end"]), 3), "text": text,
                   "order": len(rows) + 1, "translation": (line["translation"] or "").strip(),
                   "translation_source": "EDITOR", "furigana": reading(text, tagger)}
            if (line.get("review") or "").strip() == "1":
                row["requires_audio_review"] = True
            rows.append(row)
    for previous, row in zip(rows, rows[1:]):
        if row["start"] < previous["end"]:
            raise ValueError(f"{video_id}: cue {row['order']} starts before cue {previous['order']} ends")
        if not row["translation"]:
            raise ValueError(f"{video_id}: cue {row['order']} has no translation")
    target = (DATA / "local" if local else CACHE) / f"{video_id}.cues.json"
    _write(target, json.dumps(rows, ensure_ascii=False, indent=1) + "\n")
    # The cache is ignored by Git; reviewed_cues.json is the tracked copy every machine builds the seed from.
    reviewed_path = DATA / "editorial" / "reviewed_cues.json"
    reviewed = [r for r in json.loads(reviewed_path.read_text(encoding="utf-8")) if r["source_key"] != video_id]
    reviewed += [dict(row, source_key=video_id) for row in rows]
    _write(reviewed_path, json.dumps(reviewed, ensure_ascii=False, indent=2) + "\n")
    return {"video": video_id, "cues": len(rows), "review": sum(1 for r in rows if r.get("requires_audio_review")),
            "output": str(target)}


def refresh_fingerprints():
    """Record the text/timing hash of every cue; validate.py refuses an import when captions drift from it."""
    from validate import fingerprints, load_payload
    path = DATA / "caption_fingerprints.json"
    _write(path, json.dumps(fingerprints(load_payload()), ensure_ascii=False, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("video_id", nargs="?")
    parser.add_argument("tsv", type=Path, nargs="?", help="Default: data/shadowing/editorial/transcripts/<video_id>.tsv")
    parser.add_argument("--refresh-fingerprints", action="store_true",
                        help="After all transcripts of a batch are built: record caption_fingerprints.json")
    args = parser.parse_args()
    if args.video_id:
        tsv = args.tsv or DATA / "editorial" / "transcripts" / f"{args.video_id}.tsv"
        print(json.dumps(build(args.video_id, tsv), ensure_ascii=False))
    if args.refresh_fingerprints:
        refresh_fingerprints()


if __name__ == "__main__":
    main()
