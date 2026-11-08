"""Import pitch accent (trọng âm) for vocabulary already in the J-LAS dictionary.

Primary source is UniDic (現代書き言葉UniDic, NINJAL), read through MeCab via fugashi;
Kanjium accents.txt fills words UniDic cannot cover as one token (mostly compounds).
A word with no matching source value gets no row: nothing is guessed.

Run with DB_NAME, DB_USERNAME and DB_PASSWORD set. Only vocabulary_pitch_accent is written.
"""

from __future__ import annotations

import argparse
import os
import re
from pathlib import Path

import mysql.connector

from .normalization import normalize_reading, normalize_text


# Vị trí trường trong feature 29 cột mà sys.dic trả về (đã đối chiếu với dữ liệu thật:
# 募集 -> kana ボシュウ, aType 0). feature.def mô tả đặc trưng huấn luyện, không phải thứ tự này.
UNIDIC_FIELD_COUNT = 29
UNIDIC_KANA = 20
UNIDIC_ATYPE = 24

SMALL_KANA = set("ゃゅょぁぃぅぇぉゎ")
ACCENTS = re.compile(r"^\d+(,\d+)*$")
SOURCE_CODES = ("UNIDIC_CWJ", "KANJIUM")


def mora_count(reading: str) -> int:
    """っ, ー và ん là một mora; kana nhỏ ghép với kana đứng trước."""
    return sum(1 for char in normalize_reading(reading) if char not in SMALL_KANA)


def valid_accents(raw: str, reading: str) -> str | None:
    """Giữ các giá trị hợp lệ theo đúng thứ tự nguồn; None khi không còn giá trị nào."""
    if not raw or not ACCENTS.fullmatch(raw.strip()):
        return None
    limit = mora_count(reading)
    values = [value for value in raw.strip().split(",") if int(value) <= limit]
    return ",".join(dict.fromkeys(values)) or None


def load_kanjium(path: Path) -> tuple[dict[tuple[str, str], str], dict[str, int]]:
    """(expression, reading) -> accents. Bỏ qua dòng ghi theo từ loại và cặp trùng mâu thuẫn."""
    counts = dict(lines=0, annotated=0, conflicting=0)
    found: dict[tuple[str, str], str] = {}
    conflicts: set[tuple[str, str]] = set()
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            counts["lines"] += 1
            expression, reading, accents = parts
            if not ACCENTS.fullmatch(accents.strip()):
                counts["annotated"] += 1
                continue
            key = (normalize_text(expression), normalize_reading(reading or expression))
            if key in found and found[key] != accents.strip():
                conflicts.add(key)
            found.setdefault(key, accents.strip())
    for key in conflicts:
        found.pop(key, None)
    counts["conflicting"] = len(conflicts)
    return found, counts


def unidic_accents(tagger, expression: str, reading: str) -> str | None:
    """Chỉ nhận khi UniDic phân tích cả từ thành đúng một token có cùng cách đọc."""
    nodes = list(tagger(expression))
    if len(nodes) != 1 or nodes[0].surface != expression:
        return None
    feature = nodes[0].feature
    if len(feature) != UNIDIC_FIELD_COUNT or normalize_reading(feature[UNIDIC_KANA]) != reading:
        return None
    return valid_accents(feature[UNIDIC_ATYPE], reading)


def select_accents(tagger, kanjium: dict[tuple[str, str], str],
                   expression: str, reading: str) -> tuple[str, str] | None:
    """(accents, source_code) theo thứ tự UniDic rồi Kanjium."""
    if tagger is not None:
        accents = unidic_accents(tagger, expression, reading)
        if accents:
            return accents, "UNIDIC_CWJ"
    raw = kanjium.get((expression, reading))
    accents = valid_accents(raw, reading) if raw else None
    return (accents, "KANJIUM") if accents else None


def import_pitch_accents(connection, tagger, kanjium: dict[tuple[str, str], str],
                         chunk_size: int = 2000) -> dict[str, int]:
    counts = dict(entries=0, unidic=0, kanjium=0, none=0, removed=0)
    cursor = connection.cursor()
    cursor.execute("SELECT code, id FROM dictionary_source WHERE code IN (%s, %s)", SOURCE_CODES)
    sources = dict(cursor.fetchall())
    missing = [code for code in SOURCE_CODES if code not in sources]
    if missing:
        raise ValueError(f"Apply migration 064 first; missing dictionary_source {missing}")

    last_id = 0
    while True:
        cursor.execute("SELECT id, normalized_expression, normalized_reading FROM vocabulary_entry "
                       "WHERE id > %s ORDER BY id LIMIT %s", (last_id, chunk_size))
        rows = cursor.fetchall()
        if not rows:
            break
        last_id = rows[-1][0]
        upserts, empty = [], []
        for vocabulary_id, expression, reading in rows:
            counts["entries"] += 1
            chosen = select_accents(tagger, kanjium, expression or "", reading or "") if reading else None
            if chosen:
                accents, code = chosen
                counts["unidic" if code == "UNIDIC_CWJ" else "kanjium"] += 1
                upserts.append((vocabulary_id, accents, sources[code]))
            else:
                counts["none"] += 1
                empty.append(vocabulary_id)
        if upserts:
            cursor.executemany("INSERT INTO vocabulary_pitch_accent (vocabulary_id, accents, source_id) "
                               "VALUES (%s, %s, %s) AS new ON DUPLICATE KEY UPDATE "
                               "accents = new.accents, source_id = new.source_id", upserts)
        if empty:
            # Nguồn không còn giá trị cho từ này thì xóa dòng cũ, để bảng luôn khớp với nguồn.
            placeholders = ",".join(["%s"] * len(empty))
            cursor.execute(f"DELETE FROM vocabulary_pitch_accent WHERE vocabulary_id IN ({placeholders})",
                           tuple(empty))
            counts["removed"] += cursor.rowcount
        connection.commit()
        print(f"... {counts['entries']} entries", flush=True)
    cursor.close()
    return counts


def main() -> None:
    parser = argparse.ArgumentParser(description="Import pitch accent from UniDic, falling back to Kanjium")
    parser.add_argument("--unidic-dir", type=Path, default=Path("data/unidic-cwj-202512"))
    parser.add_argument("--kanjium-file", type=Path,
                        default=Path("data/kanjium-master/kanjium-master/data/source_files/raw/accents.txt"))
    parser.add_argument("--chunk-size", type=int, default=2000)
    args = parser.parse_args()
    if args.chunk_size < 1 or not (args.unidic_dir / "sys.dic").is_file() or not args.kanjium_file.is_file():
        parser.error("--unidic-dir must contain sys.dic, --kanjium-file must exist, --chunk-size must be positive")
    for key in ("DB_NAME", "DB_USERNAME", "DB_PASSWORD"):
        if not os.environ.get(key):
            parser.error(f"{key} is required")

    import fugashi  # chỉ cần khi chạy import thật; unit test không phụ thuộc MeCab

    tagger = fugashi.GenericTagger(f'-d "{args.unidic_dir.resolve()}" -r nul')
    kanjium, kanjium_counts = load_kanjium(args.kanjium_file)
    print("kanjium:", kanjium_counts, "usable pairs:", len(kanjium))
    connection = mysql.connector.connect(host=os.environ.get("DB_HOST", "localhost"),
                                         port=int(os.environ.get("DB_PORT", "3306")),
                                         database=os.environ["DB_NAME"],
                                         user=os.environ["DB_USERNAME"],
                                         password=os.environ["DB_PASSWORD"],
                                         charset="utf8mb4", autocommit=False)
    try:
        print(import_pitch_accents(connection, tagger, kanjium, args.chunk_size))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
