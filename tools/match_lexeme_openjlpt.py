import re, csv, os, collections

QB = r"D:\FALL26_FPT\SEP490\sql\seeds\questionbank.sql"
OJ = r"C:\Users\LAPTOP LENOVO\AppData\Local\Temp\openjlpt-check\data\csv"

# --- 1. Parse question lexemes from questionbank.sql ---
questions = []  # (skill, level, lexeme, reading)
lexeme_re = re.compile(r'"lexeme"\s*:\s*"([^"]*)"\s*,\s*"reading"\s*:\s*"([^"]*)"')
select_re = re.compile(r"SELECT t\.id,\s*'([^']*)'\s*,\s*'([^']*)'\s*,")

with open(QB, encoding="utf-8") as f:
    for line in f:
        if '"lexeme"' not in line:
            continue
        m = lexeme_re.search(line)
        if not m:
            continue
        lexeme, reading = m.group(1), m.group(2)
        s = select_re.search(line)
        skill = s.group(1) if s else "?"
        level = s.group(2) if s else "?"
        questions.append((skill, level, lexeme, reading))

print(f"Total lexeme questions parsed: {len(questions)}")
print(f"  by skill: {collections.Counter(q[0] for q in questions)}")
print(f"  by level: {collections.Counter(q[1] for q in questions)}")

# --- 2. Load OpenJLPT vocab ---
word_readings = collections.defaultdict(set)
word_levels = {}          # word -> level (first seen)
exact_pairs = set()       # (word, reading)

for lvl in ["n5", "n4", "n3", "n2", "n1"]:
    p = os.path.join(OJ, f"vocab-{lvl}.csv")
    with open(p, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            w = row["word"].strip()
            r = row["reading"].strip()
            word_readings[w].add(r)
            word_levels.setdefault(w, row["level"])
            exact_pairs.add((w, r))

total_vocab = len(word_readings)
print(f"\nOpenJLPT vocab distinct words: {total_vocab}")

# --- 3. Match ---
tier = collections.Counter()
tier_by_skill = collections.defaultdict(collections.Counter)
tier_by_level = collections.defaultdict(collections.Counter)

for skill, level, lexeme, reading in questions:
    if lexeme in word_readings:
        if reading in word_readings[lexeme]:
            t = "A_exact_word_reading"
        else:
            t = "B_word_only"
    else:
        t = "C_no_match"
    tier[t] += 1
    tier_by_skill[skill][t] += 1
    tier_by_level[level][t] += 1

n = len(questions)
a = tier["A_exact_word_reading"]
b = tier["B_word_only"]
c = tier["C_no_match"]
print(f"\n=== COVERAGE (all {n} lexeme questions) ===")
print(f"A exact (word+reading): {a}  ({a*100/n:.1f}%)")
print(f"B word-only (reading khac/rong): {b}  ({b*100/n:.1f}%)")
print(f"C no match: {c}  ({c*100/n:.1f}%)")
print(f"COVERED (A+B): {a+b}  ({(a+b)*100/n:.1f}%)")

print("\n=== by skill (A+B covered %) ===")
for sk in sorted(tier_by_skill):
    d = tier_by_skill[sk]
    tot = sum(d.values())
    cov = d["A_exact_word_reading"] + d["B_word_only"]
    print(f"{sk:12s}: total={tot:4d}  covered={cov:4d} ({cov*100/tot:.1f}%)  [A={d['A_exact_word_reading']}, B={d['B_word_only']}, C={d['C_no_match']}]")

print("\n=== by level (A+B covered %) ===")
for lv in ["N5", "N4", "N3", "N2", "N1"]:
    if lv not in tier_by_level:
        continue
    d = tier_by_level[lv]
    tot = sum(d.values())
    cov = d["A_exact_word_reading"] + d["B_word_only"]
    print(f"{lv:4s}: total={tot:4d}  covered={cov:4d} ({cov*100/tot:.1f}%)  [A={d['A_exact_word_reading']}, B={d['B_word_only']}, C={d['C_no_match']}]")
