"""Export the clean N5-N4 vocabulary pool for the community flashcard seed.

Reads the local MySQL dictionary once and writes data/flashcard/vocab_pool.json, so the
SQL build (build_seed.py) stays offline and byte-for-byte deterministic. Two sources:

* JMDICT  - vocabulary_entry usage_scope='BOTH' AND dictionary_status='PUBLISHED' whose
            expression is on the OpenJLPT N5/N4 list (tools/openjlpt-data); level comes
            from OpenJLPT because the imported jlpt_level of this group is mostly N3-N1.
* SEKAI   - usage_scope='TEXTBOOK' AND dictionary_status='PUBLISHED' (Sekai N5/N4 lessons,
            reviewed, 100% with Vietnamese examples). Phrases/conjugations are dropped.

TEXTBOOK/DRAFT (AI-written, unreviewed) is never read.

Usage: python tools/flashcard_seed/extract_pool.py  (env MYSQL_BIN, JLAS_DB_USER,
JLAS_DB_PASSWORD, JLAS_DB_NAME override the local defaults)
"""
from __future__ import annotations

import csv
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "data/flashcard/vocab_pool.json"
OPENJLPT = ROOT / "tools/openjlpt-data"
MYSQL = os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
SEKAI_TEXTBOOKS = {1: "Sekai N5", 2: "Sekai N4"}
# Older demo seeds (jlas_seed_all.sql, flashcard_demo_5_sets.sql, seedsqlflashcard.sql) own their public
# sets with this account; the community seed renames them, fills Hán Việt and adds learner activity.
LEGACY_OWNER = os.environ.get("JLAS_LEGACY_FLASHCARD_OWNER", "demo@jlab.local")

# Conjugated/grammar endings that make an entry a sentence pattern rather than a word.
PHRASE_ENDINGS = ("ても", "でも", "ている", "ていた", "ました", "ません", "ませんか", "ましょう",
                  "ましょうか", "たい", "たら", "ければ", "なくて", "ないで", "てください", "でください",
                  "ように", "そうです", "ようです", "らしい", "だろう", "でしょう", "ますか", "ましたか",
                  "ですか", "ですね", "ですが", "でした", "でしたか", "のに")
# Kana-only te/ta forms (いそいで, まがって, よかった) and question word + particle (どこに, 何を).
KANA_INFLECTED = ("って", "いで", "いて", "んで", "った", "んだ", "かった")
QUESTION_PHRASE = re.compile(r"^(だれ|どこ|どちら|どなた|どれ|何|なに|いつ)(が|に|で|を|は|まで|までに)$")
NOT_HEADWORDS = {"を", "によって", "見に", "たなか"}  # particles, grammar, a textbook character name
# Kanji words whose trailing kana is a te/ta/plain form (食べた, 持って, 暇だ) rather than a headword.
INFLECTED_TAILS = ("て", "で", "た", "だ")
INFLECTED_EXEMPT = {"初めて", "全て", "決して", "大して", "改めて", "近く", "遠く", "多く"}
KANJI = re.compile(r"[一-鿿々]")
NOISE = re.compile(r"[\s　A-Za-z0-9０-９（）()〜~／/・…、。！？!?「」]")


def query(sql: str) -> list[dict]:
    command = [MYSQL, "-u" + os.environ.get("JLAS_DB_USER", "root"),
               "-p" + os.environ.get("JLAS_DB_PASSWORD", "123456789"),
               "--default-character-set=utf8mb4", "-N", "-B", "--raw",
               os.environ.get("JLAS_DB_NAME", "jlas"), "-e", sql]
    out = subprocess.run(command, check=True, capture_output=True).stdout.decode("utf-8")
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def openjlpt_levels() -> dict[str, str]:
    levels = {}
    for level in ("n4", "n5"):  # N5 last so a word on both lists keeps the easier level
        with open(OPENJLPT / f"vocab-{level}.csv", encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                levels[row["word"].strip()] = level.upper()
    return levels


def okurigana(expression: str) -> str:
    last = max((i for i, c in enumerate(expression) if KANJI.match(c)), default=-1)
    return expression[last + 1:] if last >= 0 else ""


def clean_word(expression: str, reading: str, i_adjectives: set[str]) -> bool:
    if not expression or not reading or NOISE.search(expression) or NOISE.search(reading):
        return False
    if len(expression) > 9 or any(reading.endswith(end) for end in PHRASE_ENDINGS):
        return False
    if expression in NOT_HEADWORDS or QUESTION_PHRASE.match(expression):
        return False
    if not KANJI.search(expression) and reading.endswith(KANA_INFLECTED):
        return False
    tail = okurigana(expression)
    if tail and expression not in INFLECTED_EXEMPT:
        if tail.endswith(INFLECTED_TAILS):
            return False
        if tail.endswith("く") and expression[:-1] + "い" in i_adjectives:  # 早く <- 早い
            return False
    return True


def clean_meaning(text: str | None) -> str | None:
    if not text:
        return None
    text = re.sub(r"\s+", " ", text).strip().strip(";,")
    return text[:200] or None


def main():
    levels = openjlpt_levels()
    lessons = {}
    for row in query(f"""
        SELECT JSON_OBJECT('id', cv.vocabulary_id, 'textbook', t.id, 'title', l.title)
        FROM textbook t JOIN unit un ON un.textbook_id=t.id JOIN lesson l ON l.unit_id=un.id
        JOIN lesson_section ls ON ls.lesson_id=l.id JOIN content_item ci ON ci.section_id=ls.id
        JOIN content_vocabulary cv ON cv.content_item_id=ci.id
        WHERE t.id IN ({",".join(map(str, SEKAI_TEXTBOOKS))})
        GROUP BY cv.vocabulary_id, t.id, l.id"""):
        number = re.match(r"Bài\s+(\d+)", row["title"])
        if number:
            lessons.setdefault(row["id"], set()).add(
                (SEKAI_TEXTBOOKS[row["textbook"]], int(number.group(1)), row["title"]))

    rows = query("""
        SELECT JSON_OBJECT('id', v.id, 'scope', v.usage_scope, 'expression', v.expression,
               'reading', v.reading, 'meaning', v.meaning, 'level', v.jlpt_level,
               'example_ja', v.example_ja, 'example_vi', v.example_vi,
               'gloss_en', (SELECT GROUP_CONCAT(g.gloss_text ORDER BY s.sense_order, g.id SEPARATOR '; ')
                            FROM vocabulary_sense s JOIN vocabulary_gloss g ON g.sense_id=s.id
                            WHERE s.vocabulary_id=v.id AND g.language_code='en'))
        FROM vocabulary_entry v
        WHERE v.dictionary_status='PUBLISHED'
          AND (v.usage_scope='BOTH' OR (v.usage_scope='TEXTBOOK' AND v.jlpt_level IN ('N5','N4')))
        ORDER BY v.id""")
    i_adjectives = {r["expression"].strip() for r in rows if r["expression"].strip().endswith("い")}
    pool, seen = [], set()
    # JMdict rows first: when both sources carry the same word, the dictionary entry wins.
    for row in sorted(rows, key=lambda r: (r["scope"] != "BOTH", r["id"])):
        expression, reading = row["expression"].strip(), row["reading"].strip()
        if row["scope"] == "BOTH":
            level = levels.get(expression)
            if level is None:
                continue
        else:
            level = row["level"]
        if not clean_word(expression, reading, i_adjectives) or (expression, reading) in seen:
            continue
        meaning = clean_meaning(row["meaning"])
        if meaning is None:
            continue
        example_ja, example_vi = (row.get("example_ja") or "").strip(), (row.get("example_vi") or "").strip()
        seen.add((expression, reading))
        pool.append({
            "key": f"{expression}|{reading}", "source": "JMDICT" if row["scope"] == "BOTH" else "SEKAI",
            "expression": expression, "reading": reading, "meaning": meaning, "level": level,
            "gloss_en": clean_meaning(row.get("gloss_en")),
            "example_ja": example_ja if example_ja and example_vi else None,
            "example_vi": example_vi if example_ja and example_vi else None,
            "lessons": [{"book": b, "lesson": n, "title": t} for b, n, t in sorted(lessons.get(row["id"], ()))],
        })
    kanji = {row["c"]: row["h"] for row in query(
        "SELECT JSON_OBJECT('c', `character`, 'h', han_viet) FROM kanji WHERE han_viet IS NOT NULL AND han_viet<>''")}
    legacy = {}
    for row in query(f"""
        SELECT JSON_OBJECT('title', s.title, 'description', s.description, 'front', c.front, 'han_viet', c.han_viet)
        FROM flashcard_set s JOIN users u ON u.id=s.owner_id JOIN flashcard_card c ON c.set_id=s.id
        WHERE u.email='{LEGACY_OWNER}' AND s.visibility='PUBLIC' AND s.is_draft=0 AND s.deleted_at IS NULL
        ORDER BY s.id, c.position, c.id"""):
        entry = legacy.setdefault(row["title"], {"title": row["title"], "description": row.get("description"), "cards": []})
        entry["cards"].append({"front": row["front"], "han_viet": row.get("han_viet") or None})
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps({
        "version": "flashcard-vocab-pool-v1",
        "sources": {"JMDICT": "vocabulary_entry BOTH/PUBLISHED x OpenJLPT N5-N4 (JMdict, CC BY-SA 4.0)",
                    "SEKAI": "vocabulary_entry TEXTBOOK/PUBLISHED N5-N4 (giáo trình Sekai đã duyệt)"},
        "words": pool, "kanji_han_viet": dict(sorted(kanji.items())),
        "legacy_owner": LEGACY_OWNER, "legacy_sets": list(legacy.values()),
    }, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    by = {}
    for word in pool:
        by[word["source"], word["level"]] = by.get((word["source"], word["level"]), 0) + 1
    print(OUTPUT, len(pool), dict(sorted(by.items())), "legacy sets:", len(legacy))


if __name__ == "__main__":
    main()
