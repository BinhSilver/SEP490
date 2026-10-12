"""Deterministically build the combined MySQL seed from editable source JSON."""
from __future__ import annotations
from collections import Counter
import json
from pathlib import Path
from editorial_upgrade import PREPARE, QUESTIONS, JOBS

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data/shadowing"
OUTPUT = ROOT / "sql/seeds/seed_data_shadowing.sql"
RUN_OUTPUT = ROOT / "sqlrun/03_datashadowing.sql"


def sql_json(value):
    # Hex is independent of NO_BACKSLASH_ESCAPES and client source encodings.
    raw = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8").hex()
    return f"CONVERT(0x{raw} USING utf8mb4)"


def build():
    catalog = json.loads((DATA / "catalog.json").read_text(encoding="utf-8"))
    questions = json.loads((DATA / "questions.json").read_text(encoding="utf-8"))["questions"]
    anchors = json.loads((DATA / "caption_fingerprints.json").read_text(encoding="utf-8"))
    by_source = {v["source_key"]: v for v in catalog["videos"]}
    for series in catalog["series"]:
        levels_in_list = sorted({by_source[s]["estimated_jlpt"] for s in series["members"]
                                 if by_source[s]["estimated_jlpt"]})
        # A mixed list is labeled with its most demanding estimated member level.
        series["estimated_level"] = levels_in_list[0] if levels_in_list else None
        series["level_source"] = "CREATOR" if levels_in_list else None
    tags = [{"name": s["key"], "code": "SHADOWING_GENRE_" + s["key"].upper()} for s in catalog["series"]]
    levels = sorted({v["estimated_jlpt"] for v in catalog["videos"] if v["estimated_jlpt"]})
    tags += [{"name": lv, "code": "SHADOWING_LEVEL_" + lv} for lv in levels]
    links = [{"source_key": member, "name": s["key"]} for s in catalog["series"] for member in s["members"]]
    for video in catalog["videos"]:
        video["can_publish"] = bool(video["expected_cue_count"])
        cue_directory = DATA / "local" if video.get("source_type") == "LOCAL" else DATA / ".cache"
        cue_path = cue_directory / f"{video['source_key']}.cues.json"
        if cue_path.exists():
            cues = json.loads(cue_path.read_text(encoding="utf-8"))
            video["can_publish"] = video["can_publish"] and not any(c.get("requires_audio_review") for c in cues)
        qs = [q for q in questions if q["source_key"] == video["source_key"]]
        kinds = Counter(q["kind"] for q in qs)
        video["job_levels"] = ",".join(lv for lv in ("EASY", "MEDIUM", "HARD") if any(q["level"] == lv for q in qs))
        video["job_kinds"] = ",".join(sorted(kinds))
        video["job_suggestions"] = ",".join(k for k in sorted(kinds) if k != "GAP")
        video["job_targets"] = ",".join(f"{k}:{kinds[k]}" for k in sorted(kinds))
        video["approved_on"] = video.get("approved_on", catalog["approval"]["approved_on"])
        if video["estimated_jlpt"]:
            links.append({"source_key": video["source_key"], "name": video["estimated_jlpt"]})
    template = (Path(__file__).with_name("seed_template.sql")).read_text(encoding="utf-8")
    local_cues = []
    for video in catalog["videos"]:
        if video.get("source_type") == "LOCAL" and video["expected_cue_count"]:
            rows = json.loads((DATA / "local" / f"{video['source_key']}.cues.json").read_text(encoding="utf-8"))
            local_cues += [dict(row, source_key=video["source_key"]) for row in rows]
    values = {"CATALOG": catalog, "QUESTIONS": questions, "ANCHORS": anchors, "TAGS": tags, "TAG_LINKS": links,
              "LOCAL_CUES": local_cues,
              "REVIEWED_CUES": json.loads((DATA/'editorial/reviewed_cues.json').read_text(encoding='utf-8')),
              "EDITORIAL_BASELINE": json.loads((DATA/'editorial/upgrade_baseline.json').read_text(encoding='utf-8')),
              "EDITORIAL_SOURCES": [v['source_key'] for v in catalog['videos'] if v.get('transcript_scope')]}
    for token, value in values.items():
        template = template.replace("{{" + token + "}}", sql_json(value))
    for token,value in {'EDITORIAL_PREPARE':PREPARE,'EDITORIAL_QUESTIONS':QUESTIONS,'EDITORIAL_JOBS':JOBS}.items():
        template=template.replace('{{'+token+'}}',value)
    counts = {"VIDEO_COUNT":len(catalog["videos"]), "SERIES_COUNT":len(catalog["series"]),
              "MEMBERSHIP_COUNT":sum(len(s["members"]) for s in catalog["series"]),
              "QUESTION_COUNT":len(questions), "CUE_COUNT":len(anchors)}
    for token, value in counts.items():
        template = template.replace("{{" + token + "}}", str(value))
    # Team seed and the local convenience copy must contain identical SQL.
    payload = template.encode("utf-8")
    for output in (OUTPUT, RUN_OUTPUT):
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(payload)
    return OUTPUT


if __name__ == "__main__":
    print(build())
