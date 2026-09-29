# -*- coding: utf-8 -*-
"""Liet ke day du cac lexeme THIEU (khong co trong OpenJLPT) o cac cau KANJI/VOCABULARY,
kem phan loai ます-form -> tu dien. Dung de tao seed supplement (Phase 4)."""
import re, csv, os, collections, unicodedata

QB = r"D:\FALL26_FPT\SEP490\questionbank.sql"
OJ = r"D:\FALL26_FPT\SEP490\tools\openjlpt-data"

# --- 1. Parse questions ---
lexeme_re = re.compile(r'"lexeme"\s*:\s*"([^"]*)"\s*,\s*"reading"\s*:\s*"([^"]*)"')
select_re = re.compile(r"SELECT t\.id,\s*'([^']*)'\s*,\s*'([^']*)'\s*,")
seedkey_re = re.compile(r'"seed_key"\s*:\s*"([^"]*)"')

questions = []  # (skill, level, lexeme, reading, seed_key)
with open(QB, encoding="utf-8") as f:
    for line in f:
        if '"lexeme"' not in line:
            continue
        m = lexeme_re.search(line)
        if not m:
            continue
        s = select_re.search(line)
        k = seedkey_re.search(line)
        skill = s.group(1) if s else "?"
        level = s.group(2) if s else "?"
        questions.append((skill, level, m.group(1), m.group(2), k.group(1) if k else ""))

kanji_vocab = [q for q in questions if q[0] in ("KANJI", "VOCABULARY")]
print(f"Total KANJI+VOCAB questions: {len(kanji_vocab)}")

# --- 2. OpenJLPT vocab word set ---
word_readings = collections.defaultdict(set)
for lvl in ["n5", "n4", "n3", "n2", "n1"]:
    with open(os.path.join(OJ, f"vocab-{lvl}.csv"), encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            word_readings[row["word"].strip()].add(row["reading"].strip())
print(f"OpenJLPT distinct words: {len(word_readings)}")

# --- 3. Distinct missing lexemes (with level, reading, count, seed_keys) ---
missing = collections.defaultdict(lambda: {"levels": set(), "readings": set(), "count": 0, "keys": []})
for skill, level, lexeme, reading, seed in kanji_vocab:
    if lexeme in word_readings:
        continue
    d = missing[lexeme]
    d["levels"].add(level)
    d["readings"].add(reading)
    d["count"] += 1
    d["keys"].append(seed)

print(f"Distinct missing lexemes: {len(missing)}")

# --- 4. ます-form -> dictionary form ---
def masu_to_dict(w):
    if not w.endswith("ます"):
        return None
    stem = w[:-2]  # bo 'ます'
    if not stem:
        return None
    last = stem[-1]
    row_map = {"い": "う", "き": "く", "ぎ": "ぐ", "し": "す", "ち": "つ",
               "に": "ぬ", "び": "ぶ", "み": "む", "り": "る"}
    if last in row_map:
        return stem[:-1] + row_map[last]
    return None

# --- 5. Print table ---
print("\n=== Danh sach tu thieu (sap xep theo level) ===")
level_order = {"N5": 1, "N4": 2, "N3": 3, "N2": 4, "N1": 5}
rows = []
for lexeme, d in missing.items():
    lvl = min(d["levels"], key=lambda x: level_order.get(x, 9))
    reading = " / ".join(sorted(d["readings"]))
    rows.append((lvl, lexeme, reading, d["count"], d["keys"]))

rows.sort(key=lambda r: (level_order.get(r[0], 9), r[1]))
by_lvl = collections.defaultdict(int)
for lvl, lexeme, reading, cnt, keys in rows:
    by_lvl[lvl] += 1
    masu_dict = masu_to_dict(lexeme)
    tag = ""
    if masu_dict and masu_dict in word_readings:
        tag = f"  [MASU-> {masu_dict} CO TRONG OJ]"
    elif masu_dict:
        tag = f"  [MASU-> {masu_dict} CHUA CO]"
    print(f"{lvl:3s} | {lexeme:<10s} | {reading:<18s} | x{cnt} | {tag}")

print("\n=== Tong ket theo level ===")
for lv in ["N5", "N4", "N3", "N2", "N1"]:
    print(f"{lv}: {by_lvl.get(lv, 0)} tu")
