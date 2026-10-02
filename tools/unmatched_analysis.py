import re, csv, os, collections

QB = r"D:\FALL26_FPT\SEP490\questionbank.sql"
OJ = r"C:\Users\LAPTOP LENOVO\AppData\Local\Temp\openjlpt-check\data\csv"

questions = []
lexeme_re = re.compile(r'"lexeme"\s*:\s*"([^"]*)"\s*,\s*"reading"\s*:\s*"([^"]*)"')
select_re = re.compile(r"SELECT t\.id,\s*'([^']*)'\s*,\s*'([^']*)'\s*,")
with open(QB, encoding="utf-8") as f:
    for line in f:
        if '"lexeme"' not in line:
            continue
        m = lexeme_re.search(line)
        if not m:
            continue
        s = select_re.search(line)
        questions.append((s.group(1) if s else "?", s.group(2) if s else "?", m.group(1), m.group(2)))

word_readings = collections.defaultdict(set)
for lvl in ["n5", "n4", "n3", "n2", "n1"]:
    with open(os.path.join(OJ, f"vocab-{lvl}.csv"), encoding="utf-8") as f:
        for row in csv.DictReader(f):
            word_readings[row["word"].strip()].add(row["reading"].strip())

KANJI_RANGE = re.compile(r'[\u4e00-\u9fff]')
unmatched = []
for skill, level, lexeme, reading in questions:
    if lexeme in word_readings:
        continue
    unmatched.append((skill, level, lexeme, reading))

print(f"Unmatched total: {len(unmatched)}\n")

def classify(lexeme, reading):
    has_kanji = bool(KANJI_RANGE.search(lexeme))
    has_space = ' ' in lexeme or '　' in lexeme
    # particles / multi-token markers
    multi = has_space or lexeme in ('お世話になる',) or len(lexeme) > 6
    if not has_kanji:
        return "kana_only"
    if has_space:
        return "multi_word"
    if len(lexeme) > 6:
        return "long_compound"
    return "kanji_word"

cls = collections.Counter()
cls_by_level = collections.defaultdict(collections.Counter)
for skill, level, lexeme, reading in unmatched:
    c = classify(lexeme, reading)
    cls[c] += 1
    cls_by_level[level][c] += 1

print("=== Classification of unmatched ===")
for c, n in cls.most_common():
    print(f"{c:14s}: {n}")
print()
print("=== by level ===")
for lv in ["N5", "N4", "N3", "N2", "N1"]:
    d = cls_by_level[lv]
    print(f"{lv}: {dict(d)}")

print("\n=== Samples (up to 15 per level) ===")
by_level = collections.defaultdict(list)
for skill, level, lexeme, reading in unmatched:
    by_level[level].append((skill, lexeme, reading))
for lv in ["N5", "N4", "N3", "N2", "N1"]:
    print(f"\n--- {lv} ---")
    for skill, lexeme, reading in by_level[lv][:15]:
        print(f"  [{skill}] {lexeme}  ({reading})")
