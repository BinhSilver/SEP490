"""Import only JMdict antonyms into an already imported J-LAS dictionary.

Run with DB_NAME, DB_USERNAME and DB_PASSWORD set. This step never changes the
vocabulary rows or the full dictionary import batch state.
"""

from __future__ import annotations

import argparse
import os
import re
from dataclasses import dataclass
from pathlib import Path

import mysql.connector
from lxml import etree
from .normalization import normalize_reading, normalize_text


KANA = re.compile(r"^[\u3040-\u30ff\u31f0-\u31ff\u30fc]+$")


@dataclass(frozen=True)
class Antonym:
    text: str
    reading: str | None
    sense: int | None


def parse_antonym(raw: str) -> Antonym:
    parts = [part.strip() for part in raw.split("\u30fb")]
    if not parts or not parts[0] or any(not part for part in parts):
        raise ValueError("Empty JMdict antonym")
    sense = int(parts.pop()) if parts[-1].isdigit() else None
    if len(parts) > 2 or (len(parts) == 2 and not KANA.fullmatch(parts[1])):
        raise ValueError(f"Unsupported JMdict antonym format: {raw}")
    reading = parts[1] if len(parts) == 2 else (parts[0] if KANA.fullmatch(parts[0]) else None)
    return Antonym(parts[0], reading, sense)


def iter_antonyms(path: Path):
    context = etree.iterparse(str(path), events=("end",), tag="entry", load_dtd=True,
                              resolve_entities=False, no_network=True, huge_tree=True)
    for _, entry in context:
        external_id = (entry.findtext("ent_seq") or "").strip()
        written = [normalize_text(value) for value in entry.xpath("k_ele/keb/text()")]
        readings = [normalize_reading(value) for value in entry.xpath("r_ele/reb/text()")]
        expressions = written or [normalize_text(value) for value in entry.xpath("r_ele/reb/text()")]
        raw = ["".join(ant.itertext()).strip() for ant in entry.findall("sense/ant")]
        if raw:
            yield external_id, expressions, readings, raw
        entry.clear()
        parent = entry.getparent()
        while parent is not None and entry.getprevious() is not None:
            del parent[0]


def upsert_antonym(cursor, vocabulary_id: int, source_id: int, antonym: Antonym) -> str:
    cursor.execute("SELECT id, antonym_sense, source_id FROM vocabulary_antonym "
                   "WHERE vocabulary_id = %s AND antonym_text = %s "
                   "AND antonym_reading <=> %s LIMIT 1",
                   (vocabulary_id, antonym.text, antonym.reading))
    existing = cursor.fetchone()
    if existing:
        if existing[1] == antonym.sense and existing[2] == source_id:
            return "unchanged"
        cursor.execute("UPDATE vocabulary_antonym SET antonym_sense = %s, source_id = %s WHERE id = %s",
                       (antonym.sense, source_id, existing[0]))
        return "updated"
    cursor.execute("INSERT INTO vocabulary_antonym "
                   "(vocabulary_id, antonym_text, antonym_reading, antonym_sense, source_id) "
                   "VALUES (%s, %s, %s, %s, %s)",
                   (vocabulary_id, antonym.text, antonym.reading, antonym.sense, source_id))
    return "inserted"


def import_antonyms(connection, path: Path, chunk_size: int = 500) -> dict[str, int]:
    counts = dict(entries_with_ant=0, mapped=0, ambiguous=0, unmapped=0,
                  antonyms=0, inserted=0, updated=0, unchanged=0, invalid=0)
    cursor = connection.cursor()
    cursor.execute("SELECT id FROM dictionary_source WHERE code = 'JMDICT'")
    source = cursor.fetchone()
    if not source:
        raise ValueError("JMDICT source is required")
    source_id = source[0]
    for external_id, expressions, readings, raw_antonyms in iter_antonyms(path):
        counts["entries_with_ant"] += 1
        if not expressions or not readings:
            counts["unmapped"] += 1
            continue
        expressions = list(dict.fromkeys(expressions))
        readings = list(dict.fromkeys(readings))
        expression_placeholders = ",".join(["%s"] * len(expressions))
        reading_placeholders = ",".join(["%s"] * len(readings))
        cursor.execute("SELECT id FROM vocabulary_entry WHERE normalized_expression IN ("
                       + expression_placeholders + ") AND normalized_reading IN ("
                       + reading_placeholders + ")",
                       tuple(expressions + readings))
        matches = cursor.fetchall()
        if len(matches) != 1:
            counts["ambiguous" if matches else "unmapped"] += 1
            continue
        counts["mapped"] += 1
        vocabulary_id = matches[0][0]
        seen = set()
        for raw in raw_antonyms:
            try:
                antonym = parse_antonym(raw)
            except ValueError:
                counts["invalid"] += 1
                continue
            key = (antonym.text, antonym.reading)
            if key in seen:
                continue
            seen.add(key)
            counts["antonyms"] += 1
            counts[upsert_antonym(cursor, vocabulary_id, source_id, antonym)] += 1
        if counts["entries_with_ant"] % chunk_size == 0:
            connection.commit()
    connection.commit()
    cursor.close()
    return counts


def main() -> None:
    parser = argparse.ArgumentParser(description="Import only JMdict antonyms")
    parser.add_argument("--file", type=Path, default=Path("data/JMdict.xml"))
    parser.add_argument("--chunk-size", type=int, default=500)
    args = parser.parse_args()
    if args.chunk_size < 1 or not args.file.is_file():
        parser.error("--file must exist and --chunk-size must be positive")
    for key in ("DB_NAME", "DB_USERNAME", "DB_PASSWORD"):
        if not os.environ.get(key):
            parser.error(f"{key} is required")
    connection = mysql.connector.connect(host=os.environ.get("DB_HOST", "localhost"),
                                         port=int(os.environ.get("DB_PORT", "3306")),
                                         database=os.environ["DB_NAME"],
                                         user=os.environ["DB_USERNAME"],
                                         password=os.environ["DB_PASSWORD"],
                                         charset="utf8mb4", autocommit=False)
    try:
        print(import_antonyms(connection, args.file, args.chunk_size))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
