# -*- coding: utf-8 -*-
"""Phase 4 + Phase 5 cho [JLAS-W0091].

Sinh:
  db/seed_smart_test_vocab_supplement.sql  — 48 tu thieu (khong phai dong tu ます)
  db/seed_smart_test_retag_vocab.sql       — set vocabulary_entry_id cho 1500 cau

Quy tac resolve target (cau hoi -> vocabulary_entry):
  1. Lexeme la dong tu ます (MASU_MAP)      -> dang tu dien + reading tu dien.
  2. (lexeme, reading) la bien the (VARIANT) -> dang chuan.
  3. Lexeme thuan kana (khong kanji)        -> reading = '' (convention OpenJLPT).
  4. Con lai: dung (lexeme, reading).
Match theo (normalized_expression, normalized_reading) — unique key cua vocabulary_entry.
"""
import re, csv, os, unicodedata, collections

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QB = os.path.join(REPO, "questionbank.sql")
OJ = os.path.join(REPO, "tools", "openjlpt-data")
SEED_EXISTING = os.path.join(REPO, "db", "seed_data_jlas.sql")
OUT_SUPP = os.path.join(REPO, "db", "seed_smart_test_vocab_supplement.sql")
OUT_RETAG = os.path.join(REPO, "db", "seed_smart_test_retag_vocab.sql")

CJK = re.compile(r'[㐀-鿿豈-﫿]')

def kata_to_hira(s):
    return "".join(chr(ord(c) - 0x60) if 0x30A1 <= ord(c) <= 0x30F6 else c for c in s)
def norm(s):
    return kata_to_hira(unicodedata.normalize("NFKC", s))
def esc(s):
    return s.replace("\\", "\\\\").replace("'", "''")
def is_pure_kana(s):
    return not CJK.search(s)

# --- 24 dong tu ます -> (dang tu dien, reading tu dien) ---
MASU_MAP = {
    'もらいます': ('もらう', ''), '休みます': ('休む', 'やすむ'),
    '借ります': ('借りる', 'かりる'), '働きます': ('働く', 'はたらく'),
    '寝ます': ('寝る', 'ねる'), '帰ります': ('帰る', 'かえる'),
    '聞きます': ('聞く', 'きく'), '読みます': ('読む', 'よむ'),
    '買います': ('買う', 'かう'), '貸します': ('貸す', 'かす'),
    '起きます': ('起きる', 'おきる'), '飲みます': ('飲む', 'のむ'),
    '上がります': ('上がる', 'あがる'), '下がります': ('下がる', 'さがる'),
    '増えます': ('増える', 'ふえる'), '始めます': ('始める', 'はじめる'),
    '止めます': ('止める', 'とめる'), '比べます': ('比べる', 'くらべる'),
    '決めます': ('決める', 'きめる'), '減ります': ('減る', 'へる'),
    '終わります': ('終わる', 'おわる'), '続けます': ('続ける', 'つづける'),
    '調べます': ('調べる', 'しらべる'), '選びます': ('選ぶ', 'えらぶ'),
}

# --- bien the reading (dakuten ...) -> dang chuan ---
READING_VARIANT = {
    ('免れる', 'まぬがれる'): ('免れる', 'まぬかれる'),
}

# --- 48 tu them moi: (expression, reading, meaning, level).
#     Tu thuan kana dat reading = '' (convention OpenJLPT). ---
SUPPLEMENT = [
    ('すぐ', '', 'immediately; right away', 'N5'),
    ('ときどき', '', 'sometimes', 'N5'),
    ('にぎやか', '', 'lively; bustling', 'N5'),
    ('三人', 'さんにん', 'three people', 'N5'),
    ('会社員', 'かいしゃいん', 'office worker; company employee', 'N5'),
    ('千円', 'せんえん', '1000 yen', 'N5'),
    ('大雨', 'おおあめ', 'heavy rain', 'N5'),
    ('日本語', 'にほんご', 'Japanese language', 'N5'),
    ('東京', 'とうきょう', 'Tokyo', 'N5'),
    ('母', 'はは', 'mother (one\'s own)', 'N5'),
    ('父', 'ちち', 'father (one\'s own)', 'N5'),
    ('百円', 'ひゃくえん', '100 yen', 'N5'),
    ('西口', 'にしぐち', 'west exit; west entrance', 'N5'),
    ('主人', 'しゅじん', 'husband; master; owner', 'N4'),
    ('夕食', 'ゆうしょく', 'dinner; evening meal', 'N4'),
    ('朝食', 'ちょうしょく', 'breakfast', 'N4'),
    ('駅員', 'えきいん', 'station staff; station attendant', 'N4'),
    ('ごみ袋', 'ごみぶくろ', 'garbage bag', 'N3'),
    ('体重', 'たいじゅう', 'body weight', 'N3'),
    ('帰国', 'きこく', 'return to one\'s country', 'N3'),
    ('帰省', 'きせい', 'homecoming; returning home', 'N3'),
    ('残業', 'ざんぎょう', 'overtime work', 'N3'),
    ('求人', 'きゅうじん', 'job opening; recruitment', 'N3'),
    ('翌日', 'よくじつ', 'the next day', 'N3'),
    ('職場', 'しょくば', 'workplace', 'N3'),
    ('庭園', 'ていえん', 'garden; park', 'N2'),
    ('振込', 'ふりこみ', 'bank transfer; deposit', 'N2'),
    ('賃貸', 'ちんたい', 'rental; lease', 'N2'),
    ('巻', 'かん', 'volume (of a book/series); roll', 'N2'),
    ('ため息', 'ためいき', 'sigh', 'N1'),
    ('使い捨て', 'つかいすて', 'disposable', 'N1'),
    ('懸念', 'けねん', 'concern; worry', 'N1'),
    ('折衝', 'せっしょう', 'negotiation', 'N1'),
    ('抜擢', 'ばってき', 'selection; appointment', 'N1'),
    ('挿入', 'そうにゅう', 'insertion', 'N1'),
    ('晩年', 'ばんねん', 'one\'s later years', 'N1'),
    ('示唆', 'しさ', 'suggestion; implication', 'N1'),
    ('緻密', 'ちみつ', 'minute; detailed; close', 'N1'),
    ('脆弱', 'ぜいじゃく', 'fragile; vulnerable', 'N1'),
    ('講じる', 'こうじる', 'to take (measures)', 'N1'),
    ('踏襲', 'とうしゅう', 'following; adherence to precedent', 'N1'),
    ('躊躇', 'ちゅうちょ', 'hesitation', 'N1'),
    ('遂行', 'すいこう', 'execution; accomplishment', 'N1'),
    ('遮断', 'しゃだん', 'blocking; isolation; cutoff', 'N1'),
    ('配属', 'はいぞく', 'assignment; posting', 'N1'),
    ('鑑みる', 'かんがみる', 'to learn from; in light of', 'N1'),
    ('陥る', 'おちいる', 'to fall into (a state)', 'N1'),
    ('顕著', 'けんちょ', 'remarkable; prominent; conspicuous', 'N1'),
]

assert len(MASU_MAP) == 24, len(MASU_MAP)
assert len(SUPPLEMENT) == 48, len(SUPPLEMENT)

def resolve_target(lexeme, reading):
    if lexeme in MASU_MAP:
        return MASU_MAP[lexeme]
    if (lexeme, reading) in READING_VARIANT:
        return READING_VARIANT[(lexeme, reading)]
    if is_pure_kana(lexeme):
        return (lexeme, '')
    return (lexeme, reading)

# ============================================================================
# 1) Parse questionbank.sql
# ============================================================================
lexeme_re = re.compile(r'"lexeme"\s*:\s*"([^"]*)"\s*,\s*"reading"\s*:\s*"([^"]*)"')
select_re = re.compile(r"SELECT t\.id,\s*'([^']*)'\s*,\s*'([^']*)'\s*,")
seedkey_re = re.compile(r'"seed_key"\s*:\s*"([^"]*)"')

questions = []
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

kv = [q for q in questions if q[0] in ("KANJI", "VOCABULARY")]
print(f"KANJI+VOCAB questions: {len(kv)}")

# ============================================================================
# 2) Target vocab set = OpenJLPT + supplement (+ TEXTBOOK de report chinh xac)
# ============================================================================
target_pairs = set()
oj_pairs = set()
for lvl in ["n5", "n4", "n3", "n2", "n1"]:
    with open(os.path.join(OJ, f"vocab-{lvl}.csv"), encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            p = (norm(row["word"].strip()), norm(row["reading"].strip()))
            oj_pairs.add(p)
            target_pairs.add(p)
for e, r, _, _ in SUPPLEMENT:
    target_pairs.add((norm(e), norm(r)))

textbook = set()
in_vocab = False
with open(SEED_EXISTING, encoding="utf-8") as f:
    for line in f:
        st = line.strip()
        if st.startswith("INSERT INTO vocabulary_entry"):
            in_vocab = True; continue
        if in_vocab:
            if st.endswith(";"):
                in_vocab = False
            m = re.match(r"^\(\d+,\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)'", st)
            if m:
                textbook.add((m.group(2), m.group(4)))
print(f"OpenJLPT pairs: {len(oj_pairs)}, supplement: {len(SUPPLEMENT)}, TEXTBOOK: {len(textbook)}")

# ============================================================================
# 3) Match report
# ============================================================================
res = collections.Counter()
unmatched_details = []
for skill, level, lexeme, reading, seed in kv:
    expr, rd = resolve_target(lexeme, reading)
    key = (norm(expr), norm(rd))
    if key in target_pairs:
        res["matched"] += 1
    elif key in textbook:
        res["matched_textbook"] += 1
    else:
        res["unmatched"] += 1
        unmatched_details.append((level, lexeme, reading, seed))
print(f"\n=== Retag report ===")
print(f"matched (OJ+supp): {res['matched']}")
print(f"matched (TEXTBOOK-only): {res['matched_textbook']}")
print(f"UNMATCHED: {res['unmatched']}")
for lvl, lex, rd, seed in unmatched_details:
    print(f"  ! {lvl} {lex} / {rd}  ({seed})")

# ============================================================================
# 4) Sinh supplement SQL
# ============================================================================
supp_header = """-- ============================================================================
-- [JLAS-W0091] seed_smart_test_vocab_supplement.sql — 48 tu thieu ngoai OpenJLPT
--
-- Bo sung cac tu KANJI/VOCABULARY cua Smart Test khong co trong OpenJLPT (tu ghep,
-- tu N1, danh tu chuyen dung). Dong tu dang ます da duoc map ve dang tu dien (xem
-- seed_smart_test_retag_vocab.sql) nen KHONG them o day. Tu thuan kana dat
-- reading = '' (convention OpenJLPT).
--
-- Idempotent: ON DUPLICATE KEY UPDATE id = id (no-op) — khong ghi de tu dang co
-- (ke ca TEXTBOOK). Nghia giu TIENG ANH (D2). usage_scope = 'DICTIONARY'.
-- ============================================================================
SET NAMES utf8mb4;

INSERT INTO vocabulary_entry
  (expression, normalized_expression, reading, normalized_reading, meaning, jlpt_level, usage_scope, dictionary_status)
VALUES
"""
rows = []
for e, r, meaning, lvl in SUPPLEMENT:
    rows.append(f"  ('{esc(e)}', '{esc(norm(e))}', '{esc(r)}', '{esc(norm(r))}', '{esc(meaning)}', '{lvl}', 'DICTIONARY', 'PUBLISHED')")
with open(OUT_SUPP, "w", encoding="utf-8") as f:
    f.write(supp_header)
    f.write(",\n".join(rows))
    f.write("\nON DUPLICATE KEY UPDATE id = id;\n")
print(f"\nwrote {OUT_SUPP} ({len(rows)} rows)")

# ============================================================================
# 5) Sinh retag SQL (derived table: seed_key -> norm_expr, norm_reading)
# ============================================================================
retag_header = """-- ============================================================================
-- [JLAS-W0091] seed_smart_test_retag_vocab.sql — gan vocabulary_entry_id cho cau KANJI/VOCABULARY
--
-- Chay SAU: jlas_schema_upgrade_JLAS-W0091.sql + seed_openjlpt_vocab.sql
--           + seed_smart_test_vocab_supplement.sql (va questionbank.sql da seed).
-- Dong tu ます -> dang tu dien; tu thuan kana -> reading ''. Idempotent.
-- ============================================================================
SET NAMES utf8mb4;
SET SESSION SQL_SAFE_UPDATES = 0;

"""
mapping = []
for skill, level, lexeme, reading, seed in kv:
    expr, rd = resolve_target(lexeme, reading)
    mapping.append((seed, norm(expr), norm(rd)))

with open(OUT_RETAG, "w", encoding="utf-8") as f:
    f.write(retag_header)
    CHUNK = 500
    for i in range(0, len(mapping), CHUNK):
        chunk = mapping[i:i + CHUNK]
        f.write("UPDATE assessment_question aq\n")
        f.write("JOIN (\n")
        f.write("\nUNION ALL\n".join(
            f"  SELECT '{esc(sk)}' AS sk, '{esc(ne)}' AS ne, '{esc(nr)}' AS nr"
            for sk, ne, nr in chunk
        ))
        f.write("\n) m ON JSON_UNQUOTE(JSON_EXTRACT(aq.context_json, '$.seed_key')) = m.sk\n")
        f.write("JOIN vocabulary_entry ve ON ve.normalized_expression = m.ne AND ve.normalized_reading = m.nr\n")
        f.write("SET aq.vocabulary_entry_id = ve.id\n")
        f.write("WHERE aq.skill IN ('KANJI', 'VOCABULARY');\n\n")
print(f"wrote {OUT_RETAG} ({len(mapping)} seed_key mappings)")
