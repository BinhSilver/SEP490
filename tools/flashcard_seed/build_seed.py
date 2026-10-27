"""Deterministically build the Flashcard community MySQL seed from editable source JSON.

Run: python tools/flashcard_seed/build_seed.py   (re-export the pool first with extract_pool.py
only when the dictionary itself changed; the build never touches the database)
"""
from __future__ import annotations

import json
from pathlib import Path

from community import DATA, build_community

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = Path(__file__).with_name("community_template.sql")
OUTPUT = ROOT / "sql/seeds/seed_data_flashcard_community.sql"
RUN_OUTPUT = ROOT / "sqlrun/10_flashcard_community.sql"
REPORT = DATA / "community_report.json"


def without_nulls(value):
    # JSON_TABLE maps a missing key to SQL NULL (default ON EMPTY), so nulls are simply omitted.
    if isinstance(value, dict):
        return {k: without_nulls(v) for k, v in value.items() if v is not None}
    if isinstance(value, list):
        return [without_nulls(v) for v in value]
    return value


def sql_json(value):
    # Hex is independent of NO_BACKSLASH_ESCAPES and client source encodings.
    raw = json.dumps(without_nulls(value), ensure_ascii=False, separators=(",", ":")).encode("utf-8").hex()
    return f"CONVERT(0x{raw} USING utf8mb4)"


def build():
    data = build_community()
    payload = {k: data[k] for k in ("version", "learners", "sets", "cards", "legacy_owner", "legacy_sets",
                                 "legacy_titles", "legacy_descriptions", "legacy_han_viet",
                                 "states", "sessions", "comments")}
    counts = {"SET_COUNT": len(data["sets"]), "LEGACY_SET_COUNT": len(data["legacy_sets"]),
              "LEGACY_HAN_VIET_COUNT": len(data["legacy_han_viet"]),
              "CARD_COUNT": len(data["cards"]), "LEARNER_COUNT": len(data["learners"]),
              "VOTE_COUNT": sum(s["rating"] is not None for s in data["states"]),
              "FAVORITE_COUNT": sum(s["favorite"] for s in data["states"]),
              "SESSION_COUNT": len(data["sessions"]), "COMMENT_COUNT": len(data["comments"])}
    sql = TEMPLATE.read_text(encoding="utf-8").replace("{{PAYLOAD}}", sql_json(payload))
    for token, count in counts.items():
        sql = sql.replace("{{" + token + "}}", str(count))
    # Team seed and the local convenience copy must contain identical SQL.
    for output in (OUTPUT, RUN_OUTPUT):
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(sql.encode("utf-8"))
    REPORT.write_text(json.dumps({"version": data["version"], "provenance": data["provenance"],
                                  "totals": counts, "sets": data["summary"]},
                                 ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return counts


if __name__ == "__main__":
    print(json.dumps(build(), indent=2))
    for output in (OUTPUT, RUN_OUTPUT, REPORT):
        print(output)
