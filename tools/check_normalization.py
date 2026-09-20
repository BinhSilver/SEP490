import re
import unicodedata

SEED = r"D:\FALL26_FPT\SEP490\db\seed_data_jlas.sql"

# Katakana (U+30A1..U+30F6) -> Hiragana (shift -0x60)
def kata_to_hira(s):
    out = []
    for ch in s:
        cp = ord(ch)
        if 0x30A1 <= cp <= 0x30F6:
            out.append(chr(cp - 0x60))
        else:
            out.append(ch)
    return "".join(out)

def norm_nfkc_kata(s):
    return kata_to_hira(unicodedata.normalize("NFKC", s))

def norm_nfkc_only(s):
    return unicodedata.normalize("NFKC", s)

# Parse vocabulary_entry rows (line-based). Columns (1-indexed):
# id, expression, normalized_expression, reading, normalized_reading, ...
row_re = re.compile(r"^\((\d+),\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)'")

rows = []
in_vocab = False
with open(SEED, encoding="utf-8") as f:
    for line in f:
        s = line.strip()
        if s.startswith("INSERT INTO vocabulary_entry"):
            in_vocab = True
            continue
        if in_vocab and s.endswith(";"):
            in_vocab = False
            continue
        if not in_vocab:
            continue
        m = row_re.match(s)
        if m:
            rows.append((m.group(2), m.group(3), m.group(4), m.group(5)))

print(f"vocab rows parsed: {len(rows)}")

for name, fn in [("NFKC only", norm_nfkc_only), ("NFKC + katakana->hiragana", norm_nfkc_kata)]:
    bad = []
    for expr, nexpr, reading, nreading in rows:
        if fn(expr) != nexpr or fn(reading) != nreading:
            bad.append((expr, nexpr, reading, nreading))
    print(f"{name}: {len(rows) - len(bad)}/{len(rows)} khớp, {len(bad)} lệch")

# Show sample mismatches for the best candidate
bad = []
for expr, nexpr, reading, nreading in rows:
    if norm_nfkc_kata(expr) != nexpr or norm_nfkc_kata(reading) != nreading:
        bad.append((expr, nexpr, reading, nreading))
print("\n=== Mismatch còn lại với 'NFKC + katakana->hiragana' ===")
for expr, nexpr, reading, nreading in bad[:30]:
    print(f"  expr={expr!r} -> nexpr={nexpr!r} | reading={reading!r} -> nreading={nreading!r}")
