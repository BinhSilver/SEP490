# -*- coding: utf-8 -*-
"""Sinh seed vocabulary_entry + kanji + kanji_reading tu OpenJLPT (CC BY-SA 4.0).

Quy tac chuan hoa (verify voi db/seed_data_jlas.sql):
    normalized = katakana->hiragana( NFKC(text) )
Chi ap dung cho vocabulary_entry.normalized_*; kanji_reading GIU NGUYEN
(ON katakana, KUN hiragana kem dau okurigana '.').

Output:
    db/seed_openjlpt_vocab.sql   (idempotent: chi THEM tu moi, khong ghi de)
    db/seed_openjlpt_kanji.sql   (idempotent: kanji + kanji_reading)
"""
import csv
import os
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))   # tools/
REPO = os.path.dirname(HERE)                         # goc repo
OJ = os.path.join(HERE, "openjlpt-data")             # CSV nguon (commit san)
OUT_VOCAB = os.path.join(REPO, "db", "seed_openjlpt_vocab.sql")
OUT_KANJI = os.path.join(REPO, "db", "seed_openjlpt_kanji.sql")

LEVEL_RANK = {"N5": 1, "N4": 2, "N3": 3, "N2": 4, "N1": 5}
CHUNK = 500  # so dong moi INSERT


def kata_to_hira(s):
    return "".join(chr(ord(c) - 0x60) if 0x30A1 <= ord(c) <= 0x30F6 else c for c in s)


def norm(s):
    return kata_to_hira(unicodedata.normalize("NFKC", s))


def esc(s):
    return s.replace("\\", "\\\\").replace("'", "''")


def strip_bom(s):
    return s.lstrip("\ufeff")


def split_readings(s):
    """'ク; オウ; コウ' -> ['ク','オウ','コウ']; rong -> []"""
    return [p.strip() for p in s.split(";") if p.strip()]


# ---------------------------------------------------------------------------
# 1) Load vocab (dedup theo (word, reading), giu level thap nhat)
# ---------------------------------------------------------------------------
vocab = {}  # (word, reading) -> (rank, level, meaning)
for lvl in ["n5", "n4", "n3", "n2", "n1"]:
    path = os.path.join(OJ, f"vocab-{lvl}.csv")
    with open(path, encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            word = strip_bom(row["word"]).strip()
            reading = strip_bom(row["reading"]).strip()
            meaning = strip_bom(row["meanings"]).strip()
            level = strip_bom(row["level"]).strip()
            key = (word, reading)
            rank = LEVEL_RANK.get(level, 9)
            if key not in vocab or rank < vocab[key][0]:
                vocab[key] = (rank, level, meaning)

print(f"vocab distinct (word, reading): {len(vocab)}")

# ---------------------------------------------------------------------------
# 1b) Loai nhung tu da co trong catalog (de khong sinh dong trung). Seed van
#     idempotent nho ON DUPLICATE KEY UPDATE id=id, nhung loc truoc de file gon.
#     KHONG loc o day (giu de DB quyet dinh) — chi dem so tu moi tuong doi.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# 2) Sinh seed vocab
# ---------------------------------------------------------------------------
attribution = """-- ============================================================================
-- [JLAS-W0091] seed_openjlpt_vocab.sql — Tu vung N5–N1 (nguon OpenJLPT)
--
-- Idempotent: chay lai khong trung, khong ghi de du lieu catalog dang co
-- (ON DUPLICATE KEY UPDATE id = id = no-op). Nghia giu TIENG ANH theo quyet dinh
-- D2; dich Viet giai doan sau. usage_scope = 'DICTIONARY'.
--
-- LICENSE / ATTRIBUTION (CC BY-SA 4.0):
--   Du lieu nay la derived work cua OpenJLPT (https://github.com/evanclan/OpenJLPT),
--   duoc tong hop tu: JMdict/EDICT (EDRDG, CC BY-SA 4.0) — reading & gloss;
--   Jonathan Waller's JLPT lists (tanos.co.uk, CC BY) — gan level N5–N1;
--   KANJIDIC2 (EDRDG, CC BY-SA 4.0) — (khong dung cho vocab).
--   Phai giu attribution nay + license CC BY-SA 4.0 khi tai su dung/phan phoi.
-- ============================================================================
SET NAMES utf8mb4;
"""

rows = []
for (word, reading), (rank, level, meaning) in sorted(vocab.items(), key=lambda kv: (kv[1][0], kv[0][0], kv[0][1])):
    ne = norm(word)
    nr = norm(reading)
    rows.append(f"  ('{esc(word)}', '{esc(ne)}', '{esc(reading)}', '{esc(nr)}', '{esc(meaning)}', '{level}', 'DICTIONARY', 'PUBLISHED')")

with open(OUT_VOCAB, "w", encoding="utf-8") as f:
    f.write(attribution)
    f.write("\n")
    col = "INSERT INTO vocabulary_entry\n  (expression, normalized_expression, reading, normalized_reading, meaning, jlpt_level, usage_scope, dictionary_status)\nVALUES\n"
    for i in range(0, len(rows), CHUNK):
        f.write(col)
        f.write(",\n".join(rows[i:i + CHUNK]))
        f.write("\nON DUPLICATE KEY UPDATE id = id;\n\n")
print(f"wrote {OUT_VOCAB} ({len(rows)} rows)")

# ---------------------------------------------------------------------------
# 3) Load kanji (dedup theo character, giu level thap nhat)
# ---------------------------------------------------------------------------
kanji = {}  # character -> (rank, level, strokes, meanings, onyomi[], kunyomi[])
for lvl in ["n5", "n4", "n3", "n2", "n1"]:
    path = os.path.join(OJ, f"kanji-{lvl}.csv")
    with open(path, encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            ch = strip_bom(row["character"]).strip()
            level = strip_bom(row["level"]).strip()
            rank = LEVEL_RANK.get(level, 9)
            strokes = strip_bom(row["strokes"]).strip()
            meanings = strip_bom(row["meanings"]).strip()
            onyomi = split_readings(strip_bom(row["onyomi"]))
            kunyomi = split_readings(strip_bom(row["kunyomi"]))
            if ch not in kanji or rank < kanji[ch][0]:
                kanji[ch] = (rank, level, strokes, meanings, onyomi, kunyomi)

print(f"kanji distinct: {len(kanji)}")

# ---------------------------------------------------------------------------
# 4) Sinh seed kanji + kanji_reading
# ---------------------------------------------------------------------------
kanji_attr = """-- ============================================================================
-- [JLAS-W0091] seed_openjlpt_kanji.sql — Kanji N5–N1 (nguon OpenJLPT)
--
-- Idempotent: chay lai khong trung, khong ghi de kanji dang co.
-- radical_id / han_viet / stroke_svg_uri = NULL (OpenJLPT khong co); nghia giu
-- TIENG ANH (D2), dich Viet + Han Viet giai doan sau.
-- kanji_reading: ON giu katakana, KUN giu hiragana kem dau okurigana '.';
-- is_common = 1 cho cach doc DAU TIEN cua moi loai (heuristic, chua co nguon chinh xac).
--
-- LICENSE / ATTRIBUTION (CC BY-SA 4.0):
--   Derived work cua OpenJLPT, tong hop tu KANJIDIC2 (EDRDG, CC BY-SA 4.0) —
--   readings/meanings/strokes — va Jonathan Waller's JLPT lists (CC BY) — level.
--   Phai giu attribution nay + license CC BY-SA 4.0 khi tai su dung/phan phoi.
-- ============================================================================
SET NAMES utf8mb4;
"""

kanji_rows = []
reading_rows = []  # (ch, type, text, common)
for ch, (rank, level, strokes, meanings, onyomi, kunyomi) in sorted(kanji.items(), key=lambda kv: (kv[1][0], kv[0])):
    uni = format(ord(ch), "04X")
    sc = strokes if strokes != "" else "NULL"
    kanji_rows.append(f"  ('{esc(ch)}', '{uni}', {sc}, '{esc(meanings)}', '{level}')")
    for i, r in enumerate(onyomi):
        reading_rows.append((ch, "ON", r, 1 if i == 0 else 0))
    for i, r in enumerate(kunyomi):
        reading_rows.append((ch, "KUN", r, 1 if i == 0 else 0))

with open(OUT_KANJI, "w", encoding="utf-8") as f:
    f.write(kanji_attr)
    f.write("\n")
    kcol = "INSERT INTO kanji\n  (`character`, unicode_code, stroke_count, meaning_vi, jlpt_level)\nVALUES\n"
    for i in range(0, len(kanji_rows), CHUNK):
        f.write(kcol)
        f.write(",\n".join(kanji_rows[i:i + CHUNK]))
        f.write("\nON DUPLICATE KEY UPDATE id = id;\n\n")

    # kanji_reading (JOIN kanji theo character de lay id auto)
    f.write("-- ---------------------------------------------------------------------------\n")
    f.write("-- kanji_reading: JOIN kanji.character -> id; idempotent theo (kanji_id, type, text)\n")
    f.write("-- ---------------------------------------------------------------------------\n")
    rcol = "INSERT INTO kanji_reading (kanji_id, reading_type, reading_text, is_common)\nSELECT k.id, r.reading_type, r.reading_text, r.is_common\nFROM (\n"
    for i in range(0, len(reading_rows), 1200):
        chunk = reading_rows[i:i + 1200]
        f.write(rcol)
        f.write("\nUNION ALL\n".join(
            f"  SELECT '{esc(ch)}' AS ch, '{typ}' AS reading_type, '{esc(txt)}' AS reading_text, {cmn} AS is_common"
            for ch, typ, txt, cmn in chunk
        ))
        f.write("\n) r\nJOIN kanji k ON k.`character` = r.ch\nON DUPLICATE KEY UPDATE kanji_id = kanji_id;\n\n")

print(f"wrote {OUT_KANJI} ({len(kanji_rows)} kanji, {len(reading_rows)} readings)")
