"""Read timed Japanese cues without treating rolling ASR windows as new speech."""
from __future__ import annotations
import json
from pathlib import Path
import re
import sys

def clean_caption_text(text):
    text = re.sub(r"[\uE000-\uF8FF\ufffd\u200b\ufeff]", "", text).strip()
    if re.fullmatch(r"[♪♫\s]+|[\[【(（][^\]】)）]*(?:音|声|どよめき|笑い|音楽|拍手)[\]】)）]", text):
        return ""
    text = re.sub(r"[(（][^()（）]{1,32}[)）](?=[^\s()（）])", "", text)
    text = re.sub(r"^[(（][^()（）]{1,32}[)）]\s+(?=.+)", "", text)
    return re.sub(r"ーー+$", "", re.sub(r"[＜＞]", "", text)).strip()


CACHE = Path(__file__).resolve().parents[2] / "data/shadowing/.cache"


def cues(metadata: dict) -> list[dict]:
    rows = []
    for event in metadata.get("captions", {}).get("events", []):
        text = "".join(seg.get("utf8", "") for seg in event.get("segs", []))
        text = clean_caption_text(re.sub(r"\s+", " ", text))
        if not text or re.fullmatch(r"(?:\[[^\]]*\]|【[^】]*】|♪|♫|～|〜|[.。…\s])+", text):
            continue
        start = event.get("tStartMs", 0) / 1000
        duration = event.get("dDurationMs", 0) / 1000
        if rows and event.get("aAppend"):
            rows[-1]["text"] += text
            rows[-1]["end"] = max(rows[-1]["end"], start + duration)
        else:
            rows.append({"start": round(start, 3), "end": round(start + duration, 3), "text": text})
    for index, row in enumerate(rows):
        next_start = rows[index + 1]["start"] if index + 1 < len(rows) else metadata.get("duration", row["end"])
        row["end"] = round(min(row["end"], next_start, metadata.get("duration", row["end"])), 3)
        row["order"] = index + 1
    return [row for row in rows if row["end"] > row["start"]]


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    for video_id in sys.argv[1:]:
        metadata = json.loads((CACHE / f"{video_id}.json").read_text(encoding="utf-8"))
        rows = cues(metadata)
        text = "\n".join(f"{row['order']:03d} {row['start']:8.3f}-{row['end']:8.3f} {row['text']}" for row in rows)
        (CACHE / f"{video_id}.txt").write_text(text + "\n", encoding="utf-8")
        print(f"\n{video_id} | {metadata.get('title')} | {len(rows)} cues\n{text}")


if __name__ == "__main__":
    main()
