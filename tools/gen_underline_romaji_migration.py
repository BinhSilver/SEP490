#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Sinh sql/migrations/085_question_underline_and_vocab_romaji.sql + 3 file CSV duyet tay trong sql/audits/.

Hai viec, chi sua DU LIEU (khong doi schema):
  1) Gach chan tu can hoi trong cau trac nghiem: chen <u>...</u> vao assessment_question.question_text.
     Thu tu uu tien:
       a. Lay tu nguon crawl goc (raw/<cap>/<muc>/<bai>.json, truong html_clean): cau goc co <u> -> lay dung vi tri.
       b. Quy tac, CHI cho dang "chon cach doc" (moi phuong an deu la kana, de co chu Han, khong co cho trong):
          - [chuoi Han lien nhau] + [kana theo sau] ma phan kana la hau to cua dap an (続けて <-> つづけて);
            phan chu Han phai doc duoc bang cach doc tung chu (bang kanji_reading).
          - tu khong okurigana: chuoi Han co cach doc trong tu dien (vocabulary_entry) = dap an (先生 <-> せんせい).
          - chi gan khi co DUNG 1 vi tri; 0 hoac >= 2 vi tri -> danh sach duyet tay.
       c. Dang khac (chon chu Han cho tu kana...): khong tu gan, dua vao danh sach duyet tay.
  2) Romaji cho tu vung cua bai hoc (vocabulary_entry noi vao bai qua content_vocabulary) dang rong:
     chuyen tu cach doc kana theo Hepburn dung style du lieu hien co (pykakasi: gakkou, shinbun, koohii, khong macron).
     Cum/cau (co tro tu, dau cau, ky tu ngoai kana) khong tu dien -> danh sach duyet tay.
  Kem theo: danh sach tu cua bai khong co cau vi du (khong tu sinh vi du).

Migration chay lai bao nhieu lan cung duoc, tren DB nao cung dung: cau hoi UPDATE theo id (id co dinh theo dai seed);
tu vung tim theo khoa UNIQUE (normalized_expression, normalized_reading), KHONG theo id (seed Minna/vnjpclub chen
vocabulary_entry khong ghi id nen id tu vung moi DB mot khac). Chi ghi khi gia tri hien tai dung bang ban goc
(question_text chua co <u>, reading_romaji con rong, cach doc dung bang luc sinh). Chuoi tieng Nhat ghi bang hex (X'..')
nen khong phu thuoc che do escape cua MySQL.

Chi dung thu vien chuan. Doc DB qua mysql CLI (mat khau qua bien moi truong MYSQL_PWD, khong ghi ra lenh):
    set MYSQL_PWD=...            (PowerShell: $env:MYSQL_PWD = '...')
    python tools/gen_underline_romaji_migration.py --check-romaji   # chi do ti le khop ham romaji
    python tools/gen_underline_romaji_migration.py                  # sinh 085 + CSV (khong ghi de 085 da co)
    python tools/gen_underline_romaji_migration.py --reading-fix    # sinh 086: sua cach doc chua chu Han
        (a: cung mat chu o muc noi dung khac/flashcard, moi cach doc giong nhau; b: JMdict khop chinh xac, 1 cach doc;
         con lai -> sql/audits/reading_review.csv) + romaji cho tu vua sua; cap nhat romaji_review.csv
Tuy chon: --mysql <duong dan mysql.exe>, --db jlas, --user root, --raw-dir <thu muc raw crawl> (CHI DOC).
Sinh lai bao nhieu lan cung ra cung mot noi dung (sap theo id).
"""
import argparse
import csv
import html.parser
import json
import os
import re
import subprocess
import sys
import unicodedata
from collections import defaultdict

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIGRATION = os.path.join(REPO, 'sql', 'migrations', '085_question_underline_and_vocab_romaji.sql')
AUDITS = os.path.join(REPO, 'sql', 'audits')
DEFAULT_MYSQL = r'C:\Program Files\MySQL\MySQL Server 26.7\bin\mysql.exe'
DEFAULT_RAW = r'D:\project\tool\tool\output\raw'

# ---------------------------------------------------------------------------------------------------------------
# Doc DB
# ---------------------------------------------------------------------------------------------------------------

def query(args, sql):
    """Moi dong ket qua la 1 JSON (SELECT JSON_OBJECT(...)) -> list dict."""
    cmd = [args.mysql, '-u', args.user, '--default-character-set=utf8mb4', '--batch', '--raw',
           '--skip-column-names', args.db, '-e', sql]
    out = subprocess.run(cmd, capture_output=True, check=True).stdout.decode('utf-8')
    return [json.loads(line) for line in out.splitlines() if line.strip()]


# ---------------------------------------------------------------------------------------------------------------
# Kana / romaji
# ---------------------------------------------------------------------------------------------------------------

def kata_to_hira(text):
    return ''.join(chr(ord(c) - 0x60) if 0x30A1 <= ord(c) <= 0x30F6 or 0x30FD <= ord(c) <= 0x30FE else c
                   for c in text)


def is_kana_char(c):
    o = ord(c)
    return 0x3041 <= o <= 0x3096 or 0x309D <= o <= 0x309E or 0x30A1 <= o <= 0x30FA or 0x30FC <= o <= 0x30FE


def is_kana(text):
    return bool(text) and all(is_kana_char(c) for c in text)


def is_kanji_char(c):
    o = ord(c)
    return 0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF or 0xF900 <= o <= 0xFAFF or c in '々〆ヶ'


ROMA = {}
for row, cons in (('あいうえお', ['a', 'i', 'u', 'e', 'o']),
                  ('かきくけこ', ['ka', 'ki', 'ku', 'ke', 'ko']), ('がぎぐげご', ['ga', 'gi', 'gu', 'ge', 'go']),
                  ('さしすせそ', ['sa', 'shi', 'su', 'se', 'so']), ('ざじずぜぞ', ['za', 'ji', 'zu', 'ze', 'zo']),
                  ('たちつてと', ['ta', 'chi', 'tsu', 'te', 'to']), ('だぢづでど', ['da', 'ji', 'zu', 'de', 'do']),
                  ('なにぬねの', ['na', 'ni', 'nu', 'ne', 'no']),
                  ('はひふへほ', ['ha', 'hi', 'fu', 'he', 'ho']), ('ばびぶべぼ', ['ba', 'bi', 'bu', 'be', 'bo']),
                  ('ぱぴぷぺぽ', ['pa', 'pi', 'pu', 'pe', 'po']),
                  ('まみむめも', ['ma', 'mi', 'mu', 'me', 'mo']),
                  ('やゆよ', ['ya', 'yu', 'yo']), ('らりるれろ', ['ra', 'ri', 'ru', 're', 'ro']),
                  ('わゐゑを', ['wa', 'i', 'e', 'wo']),
                  ('ぁぃぅぇぉ', ['a', 'i', 'u', 'e', 'o']), ('ゃゅょゎ', ['ya', 'yu', 'yo', 'wa']),
                  ('ゔ', ['vu']), ('ゕゖ', ['ka', 'ke'])):
    for k, r in zip(row, cons):
        ROMA[k] = r
# Am ghep (yoon) va am ngoai lai ma pykakasi ghep thanh mot am tiet.
YOON = {'ゃ': 'a', 'ゅ': 'u', 'ょ': 'o'}
for base, head in (('き', 'ky'), ('ぎ', 'gy'), ('し', 'sh'), ('じ', 'j'), ('ち', 'ch'), ('ぢ', 'j'), ('に', 'ny'),
                   ('ひ', 'hy'), ('び', 'by'), ('ぴ', 'py'), ('み', 'my'), ('り', 'ry')):
    for small, vowel in YOON.items():
        ROMA[base + small] = head + vowel
# Chi cac cap pykakasi ghep (doi chieu du lieu): うぃ/うぇ/しぇ/じぇ/てぃ... KHONG ghep (uindou, poshietto, teisshu).
ROMA.update({'ちぇ': 'che', 'ふぁ': 'fa', 'ふぃ': 'fi', 'ふぇ': 'fe', 'ふぉ': 'fo',
             'でぃ': 'di', 'ゔぁ': 'va', 'ゔぃ': 'vi', 'ゔぇ': 've', 'ゔぉ': 'vo'})


def to_romaji(reading):
    """Cach doc kana -> romaji Hepburn (style du lieu hien co). None neu co ky tu khong chuyen duoc."""
    s = kata_to_hira(unicodedata.normalize('NFKC', reading))
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == 'っ':
            nxt = to_romaji(s[i + 1:i + 3]) if i + 1 < len(s) else None
            if nxt and nxt[0] not in 'aiueonm' and s[i + 1] != 'っ':
                out.append('t' if nxt.startswith('ch') else nxt[0])
            else:
                out.append('tsu')
            i += 1
            continue
        if c == 'ー':
            last = ''.join(out)
            vowel = next((ch for ch in reversed(last) if ch in 'aiueo'), None)
            if vowel is None:
                return None
            out.append(vowel)
            i += 1
            continue
        if c in 'ゝゞ':
            prev = s[i - 1] if i else None
            if prev is None or prev not in ROMA:
                return None
            base = ROMA[prev]
            out.append(base)
            i += 1
            continue
        pair = s[i:i + 2]
        if len(pair) == 2 and pair in ROMA:
            out.append(ROMA[pair])
            i += 2
            continue
        if c == 'ん':
            # n' truoc nguyen am (ban'ichi, den'on). Truoc y du lieu hien co de 'n' tron (kanyou, konyakusha).
            nxt = s[i + 1:i + 2]
            out.append("n'" if nxt and nxt in 'あいうえおぁぃぅぇぉ' else 'n')
            i += 1
            continue
        if c not in ROMA:
            return None
        out.append(ROMA[c])
        i += 1
    return ''.join(out)


# ---------------------------------------------------------------------------------------------------------------
# HTML nguon -> chuoi phang + cac khoang gach chan
# ---------------------------------------------------------------------------------------------------------------

BLOCK_TAGS = {'div', 'p', 'br', 'tr', 'td', 'th', 'li', 'ul', 'ol', 'table', 'form', 'h1', 'h2', 'h3', 'h4', 'h5',
              'h6', 'input', 'hr', 'section', 'article', 'dt', 'dd', 'blockquote'}
SKIP_TAGS = {'rt', 'rp', 'script', 'style', 'noscript'}


class PlainWithUnderline(html.parser.HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.length = 0
        self.skip = 0
        self.open_u = []
        self.spans = []

    def _put(self, text):
        self.parts.append(text)
        self.length += len(text)

    def handle_starttag(self, tag, attrs):
        if tag in SKIP_TAGS:
            self.skip += 1
        elif tag == 'u':
            self.open_u.append(self.length)
        elif tag in BLOCK_TAGS:
            self._put('\n')

    def handle_startendtag(self, tag, attrs):
        if tag in BLOCK_TAGS:
            self._put('\n')

    def handle_endtag(self, tag):
        if tag in SKIP_TAGS:
            self.skip = max(0, self.skip - 1)
        elif tag == 'u':
            if self.open_u:
                start = self.open_u.pop()
                if self.length > start:
                    self.spans.append((start, self.length))
        elif tag in BLOCK_TAGS:
            self._put('\n')

    def handle_data(self, data):
        if not self.skip:
            self._put(data)


def keyed(text):
    """NFKC tung ky tu + bo khoang trang -> (chuoi khoa, vi tri goc cua tung ky tu khoa)."""
    chars, pos = [], []
    for i, c in enumerate(text):
        n = unicodedata.normalize('NFKC', c)
        for ch in n:
            if ch.isspace():
                continue
            chars.append(ch)
            pos.append(i)
    return ''.join(chars), pos


class SourcePage:
    def __init__(self, html_text):
        p = PlainWithUnderline()
        p.feed(html_text)
        p.close()
        self.plain = ''.join(p.parts)
        self.key, self.pos = keyed(self.plain)
        # vi tri goc -> vi tri khoa: danh dau ky tu khoa nao nam trong khoang <u>
        self.underlined = [False] * len(self.key)
        if p.spans:
            marks = [False] * (len(self.plain) + 1)
            for a, b in p.spans:
                for x in range(a, b):
                    marks[x] = True
            self.underlined = [marks[x] for x in self.pos]
        self.has_u = bool(p.spans)


def source_spans(page, text):
    """Khoang gach chan (theo vi tri trong text) lay tu trang nguon; None neu nguon khong co; 'AMBIGUOUS' neu mau thuan."""
    key, pos = keyed(text)
    if not key or not page.has_u:
        return None
    found = set()
    start = page.key.find(key)
    while start != -1:
        flags = tuple(page.underlined[start:start + len(key)])
        if any(flags):
            found.add(flags)
        start = page.key.find(key, start + 1)
    if not found:
        return None
    if len(found) > 1:
        return 'AMBIGUOUS'
    flags = found.pop()
    spans, i = [], 0
    while i < len(flags):
        if flags[i]:
            j = i
            while j + 1 < len(flags) and flags[j + 1]:
                j += 1
            spans.append((pos[i], pos[j] + 1))
            i = j + 1
        else:
            i += 1
    return spans


def apply_spans(text, spans):
    out, last = [], 0
    for a, b in sorted(spans):
        out.append(text[last:a])
        out.append('<u>' + text[a:b] + '</u>')
        last = b
    out.append(text[last:])
    return ''.join(out)


# ---------------------------------------------------------------------------------------------------------------
# Quy tac cho dang "chon cach doc"
# ---------------------------------------------------------------------------------------------------------------

BLANK_MARK = re.compile(r'[（(]\s*[）)]|＿|_{2,}|★|□|\[\s*\]|［[\s　]*］|…{2,}')
LATIN = re.compile(r'[A-Za-zÀ-ỹ]')
DAKUTEN = dict(zip('かきくけこさしすせそたちつてとはひふへほ', 'がぎぐげござじずぜぞだぢづでどばびぶべぼ'))
HANDAKUTEN = dict(zip('はひふへほ', 'ぱぴぷぺぽ'))


def reading_variants(readings, first, last):
    """Bien am thuong gap khi chu dung trong tu: rendaku (am dau), xuc am っ (am cuoi)."""
    out = set()
    for r in readings:
        if not r:
            continue
        forms = {r}
        if not first and r[0] in DAKUTEN:
            forms.add(DAKUTEN[r[0]] + r[1:])
        if not first and r[0] in HANDAKUTEN:
            forms.add(HANDAKUTEN[r[0]] + r[1:])
        if not last:
            for f in list(forms):
                if len(f) > 1 and f[-1] in 'つくちき':
                    forms.add(f[:-1] + 'っ')
        out |= forms
    return out


def segmentable(chars, reading, kanji_readings):
    """Doc chuoi chu Han `chars` thanh dung `reading` bang cach doc tung chu (ON/KUN/NANORI)."""
    n = len(chars)
    memo = {}

    def go(ci, ri):
        if ci == n:
            return ri == len(reading)
        k = (ci, ri)
        if k in memo:
            return memo[k]
        c = chars[ci]
        base = kanji_readings.get(c, set())
        if c == '々' and ci > 0:
            base = kanji_readings.get(chars[ci - 1], set())
        ok = False
        for r in reading_variants(base, ci == 0, ci == n - 1):
            if reading.startswith(r, ri) and go(ci + 1, ri + len(r)):
                ok = True
                break
        memo[k] = ok
        return ok

    return go(0, 0)


def rule_candidates(text, answer, kanji_readings, dict_readings):
    """Cac vi tri (start, end) trong text co the la tu can doc; dap an da o dang hiragana."""
    cands = set()
    for m in re.finditer(r'[一-鿿㐀-䶿豈-﫿々〆ヶ]+', text):
        rs, re_ = m.start(), m.end()
        # a) chu Han + okurigana: phan kana theo sau la hau to cua dap an
        follow = ''
        j = re_
        while j < len(text) and is_kana_char(text[j]):
            follow += kata_to_hira(text[j])
            j += 1
        for k in range(min(len(answer) - 1, len(follow)), 0, -1):
            if not answer.endswith(follow[:k]):
                continue
            stem = answer[:-k]
            # Phan chu Han ngan nhat (tinh tu cuoi chuoi) doc ra dung phan con lai cua dap an: 毎日続 -> 続.
            hit = next(((i, re_ + k) for i in range(re_ - 1, rs - 1, -1)
                        if segmentable(text[i:re_], stem, kanji_readings)), None)
            if hit:
                cands.add(hit)
                break
        # b) khong okurigana: chuoi Han co cach doc trong tu dien (hoac 1 chu co cach doc) = dap an
        for i in range(rs, re_):
            for e in range(i + 1, re_ + 1):
                sub = text[i:e]
                if answer in dict_readings.get(sub, ()):
                    cands.add((i, e))
                elif e - i == 1 and answer in kanji_readings.get(sub, ()):
                    cands.add((i, e))
    # Gop cac vi tri long nhau (続 trong 続けて) ve vi tri dai nhat; con lai >= 2 vi tri roi nhau la mo ho.
    spans = sorted(cands, key=lambda s: (s[0], -(s[1] - s[0])))
    merged = []
    for s in spans:
        if merged and s[0] < merged[-1][1]:
            last = merged[-1]
            if s[0] >= last[0] and s[1] <= last[1]:
                continue
            if last[0] >= s[0] and last[1] <= s[1]:
                merged[-1] = s
                continue
            merged.append(s)
        else:
            merged.append(s)
    return merged


def kana_reading_in_text(text, answer, kanji_readings, dict_readings):
    """Dang chon chu Han: trong de co cum kana doc ra dung dap an (げんりょう <-> 減量, よびます <-> 呼びます)."""
    ans = unicodedata.normalize('NFKC', answer).strip()
    m = re.match(r'^([一-鿿㐀-䶿豈-﫿々〆ヶ]+)([぀-ゟ]*)$', ans)
    if not m:
        return False
    kanji_part, tail = m.group(1), m.group(2)
    hira = kata_to_hira(text)
    for run in re.findall(r'[぀-ゟー]+', hira):
        for start in range(len(run)):
            for end in range(start + len(tail) + 1, len(run) + 1):
                piece = run[start:end]
                if tail and not piece.endswith(tail):
                    continue
                stem = piece[:len(piece) - len(tail)] if tail else piece
                if stem in dict_readings.get(kanji_part, ()) or segmentable(kanji_part, stem, kanji_readings):
                    return True
    return False


# ---------------------------------------------------------------------------------------------------------------
# Tu vung: phan loai cum/cau
# ---------------------------------------------------------------------------------------------------------------

# Tro tu giua hai tu: sau danh tu >= 2 chu Han/katakana (日本にいます) hoac truoc mot tu Han/katakana moi
# (湿度が高い, 彼は医者). Okurigana cua dong tu (召し上がる, 間もなく, 着がえる) khong bi tinh la cum.
PHRASE_PARTICLE = re.compile(r'[一-鿿々ァ-ヺー]{2}[はがをへにでとも].|[一-鿿々ァ-ヺー][はがをへにでとも][一-鿿々ァ-ヺ]')
# Một chữ Hán + は + hiragana trong một cụm dài (cách đọc >= 7 kana): trợ từ は trong câu (彼はたった今帰った,
# 家はどちらですか, 実はお金がない). Từ ngắn như 絵はがき (は thuộc từ: 葉書) không bị tính.
PARTICLE_HA = re.compile(r'[一-鿿々]は[぀-ゖ]')
PARTICLE_HA_MIN_READING = 7
# ー ngay sau chữ Hán là gạch nối hai từ (常勤ー非常勤); sau katakana là trường âm (コピー機, スキー場) thì giữ.
DASH_BETWEEN_WORDS = re.compile(r'[一-鿿々]ー')
# Cach doc dang "kana（kana）" (げんき（な）, しんけん（な）): chuyen tung phan, giu ngoac.
KANA_PAREN = re.compile(r'^([぀-ヿー]+)[（(]([぀-ヿー]+)[）)]$')


def vocab_romaji(reading):
    """Romaji cho tu vung: kana thuan, hoac 'kana（kana）' -> 'genki (na)'. None neu khong chuyen duoc."""
    rd = (reading or '').strip()
    m = KANA_PAREN.match(rd)
    if m:
        a, b = to_romaji(m.group(1)), to_romaji(m.group(2))
        return '%s (%s)' % (a, b) if a and b else None
    return to_romaji(rd)


def romaji_skip_reason(expression, reading):
    expr = (expression or '').strip()
    rd = (reading or '').strip()
    if not rd:
        return 'không có cách đọc'
    if KANA_PAREN.match(rd) and not PHRASE_PARTICLE.search(expr) and vocab_romaji(rd):
        return None
    if not is_kana(rd):
        if any(is_kanji_char(c) for c in rd):
            return 'cách đọc chứa chữ Hán (dữ liệu cách đọc sai)'
        return 'cách đọc có ký tự ngoài kana (dấu câu, ngoặc, khoảng trắng…)'
    if 'を' in rd or 'ヲ' in rd:
        return 'cụm/câu có trợ từ を'
    if re.search(r'[\s、。！？!?「」『』（）()～~…・/／,]', expr):
        return 'cụm/câu (có dấu câu hoặc khoảng trắng)'
    if PHRASE_PARTICLE.search(expr) or (PARTICLE_HA.search(expr) and len(rd) >= PARTICLE_HA_MIN_READING):
        return 'cụm/câu (có trợ từ は/が/へ/に/で/と/も giữa từ), cần tách từ'
    # Kết thúc bằng は/へ viết hiragana: trợ từ (今日は konnichiwa, 実は jitsuwa, 又は matawa), kể cả khi trước đó là chữ Hán.
    if expr and expr[-1] in 'はへ':
        return 'kết thúc bằng は/へ (có thể đọc wa/e)'
    if rd[-1] in 'っッ':
        return 'kết thúc bằng っ (thán từ, vd あっ / えっ)'
    if DASH_BETWEEN_WORDS.search(expr):
        return 'dấu ー nối hai từ (không phải trường âm)'
    if len(rd) > 12:
        return 'cụm dài, cần tách từ'
    if to_romaji(rd) is None:
        return 'cách đọc có ký tự không chuyển được'
    return None


# ---------------------------------------------------------------------------------------------------------------
# SQL
# ---------------------------------------------------------------------------------------------------------------

def hexstr(text):
    return "CONVERT(X'" + text.encode('utf-8').hex().upper() + "' USING utf8mb4)"


def comment(text):
    return re.sub(r'\s+', ' ', text).replace('*/', '* /')[:160]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--mysql', default=os.environ.get('JLAS_MYSQL', DEFAULT_MYSQL))
    ap.add_argument('--db', default='jlas')
    ap.add_argument('--user', default='root')
    ap.add_argument('--raw-dir', default=DEFAULT_RAW)
    ap.add_argument('--check-romaji', action='store_true')
    ap.add_argument('--reading-fix', action='store_true',
                    help='sinh 086: sua cach doc dang chua chu Han + romaji cho tu vua sua; cap nhat reading_review.csv '
                         'va romaji_review.csv (tinh theo trang thai sau 086)')
    ap.add_argument('--merge-dupes', action='store_true',
                    help='sinh 088: gop muc tu trung (cach doc chua chu Han) vao muc dung + merge_review.csv')
    ap.add_argument('--audit', action='store_true',
                    help='chi tinh lai reading_review.csv + romaji_review.csv theo DB hien tai (khong sinh migration)')
    ap.add_argument('--force', action='store_true', help='cho phep ghi de migration da co (da chay tren DB thi KHONG dung)')
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    if args.merge_dupes:
        merge_dupes(args)
        return
    if args.audit:
        reading_fix(args, write_migration=False)
        return
    if args.reading_fix:
        reading_fix(args)
        return
    if os.path.exists(MIGRATION) and not args.check_romaji and not args.force:
        sys.exit('%s da ton tai (va da chay tren DB) - khong ghi de. Dung --reading-fix, hoac --force neu that su can.'
                 % os.path.relpath(MIGRATION, REPO))

    # ---------------- Kiem tra ham romaji tren du lieu da co romaji ----------------
    known = query(args, "SELECT JSON_OBJECT('r', reading, 'm', reading_romaji) FROM vocabulary_entry "
                        "WHERE reading_romaji IS NOT NULL AND reading_romaji <> ''")
    total = ok = 0
    diffs = defaultdict(int)
    samples = defaultdict(list)
    for row in known:
        r = (row['r'] or '').strip()
        if not is_kana(r):
            continue
        total += 1
        got = to_romaji(r)
        if got == row['m']:
            ok += 1
        else:
            key = 'none' if got is None else 'diff'
            diffs[key] += 1
            if len(samples[key]) < 40:
                samples[key].append((r, row['m'], got))
    rate = ok / total * 100 if total else 0.0
    print('[romaji] so khop tren %d muc da co romaji (cach doc thuan kana): %d (%.2f%%)' % (total, ok, rate))
    if args.check_romaji:
        for key, rows in samples.items():
            print('  --', key, diffs[key])
            for s in rows:
                print('    ', s)
        return

    # ---------------- Du lieu ----------------
    print('[db] doc cau hoi...')
    questions = query(args, """
        SELECT JSON_OBJECT('id', id, 'sc', source_code, 't', question_text,
                           'o', JSON_ARRAY(option_1, option_2, option_3, option_4),
                           'c', correct_option, 'ctx', context_json, 'title', title,
                           'pu', COALESCE(passage_html LIKE '%<u>%', 0))
        FROM assessment_question ORDER BY id""")
    placement = {}
    for row in query(args, """
        SELECT JSON_OBJECT('q', qq.question_id, 'tb', t.title, 'ls', l.title, 'k', CONCAT(LPAD(t.id, 12, '0'), LPAD(l.id, 12, '0')))
        FROM assessment_quiz_question qq JOIN assessment_quiz z ON z.id = qq.quiz_id
        JOIN lesson l ON l.id = z.lesson_id JOIN unit u ON u.id = l.unit_id JOIN textbook t ON t.id = u.textbook_id"""):
        cur = placement.get(row['q'])
        if cur is None or row['k'] < cur['k']:
            placement[row['q']] = row
    print('[db] doc cach doc kanji + tu dien...')
    kanji_readings = defaultdict(set)
    for row in query(args, "SELECT JSON_OBJECT('c', k.`character`, 'r', r.reading_text) FROM kanji k "
                           "JOIN kanji_reading r ON r.kanji_id = k.id"):
        r = kata_to_hira(row['r'] or '').replace('-', '')
        kanji_readings[row['c']].add(r.split('.')[0])
        kanji_readings[row['c']].add(r.replace('.', ''))
    dict_readings = defaultdict(set)
    for row in query(args, "SELECT JSON_OBJECT('e', expression, 'r', reading) FROM vocabulary_entry "
                           "WHERE dictionary_status <> 'HIDDEN' AND reading IS NOT NULL"):
        e = (row['e'] or '').strip()
        if e and all(is_kanji_char(c) for c in e):
            dict_readings[e].add(kata_to_hira(unicodedata.normalize('NFKC', row['r'])).strip())

    # ---------------- Trang nguon (chi doc) ----------------
    raw_index = {}
    if os.path.isdir(args.raw_dir):
        for root, _, files in os.walk(args.raw_dir):
            for f in files:
                if f.endswith('.json'):
                    cat = os.path.basename(root)
                    raw_index[(cat, f[:-5])] = os.path.join(root, f)
    page_cache = {}

    def page_for(url):
        if not url:
            return None
        if url in page_cache:
            return page_cache[url]
        m = re.match(r'https?://[^/]+/(?:.*/)?([^/]+)/([^/]+?)(?:\.html)?/?$', url)
        page = None
        path = raw_index.get((m.group(1), m.group(2))) if m else None
        if path:
            with open(path, encoding='utf-8') as fh:
                rec = json.load(fh)
            if rec.get('meta', {}).get('url') == url and rec.get('html_clean'):
                page = SourcePage(rec['html_clean'])
        page_cache[url] = page
        return page

    # ---------------- Gach chan ----------------
    q_updates, q_review = [], []
    stats = defaultdict(int)
    for q in questions:
        text = q['t'] or ''
        ctx = q['ctx'] if isinstance(q['ctx'], dict) else {}
        # Da co marker: <u> trong de, ［…］ (Tanki), hoac <u> nam o doan van passage_html (de chi la loi dan 下線部の…).
        if '<u>' in text.lower() or re.search(r'［[^］＿_／/、\s　]{1,12}］', text) or q['pu']:
            stats['da_co_marker'] += 1
            continue
        options = [o.get('text') or '' for o in (ctx.get('options') or []) if isinstance(o, dict)] \
            or [o or '' for o in q['o'] if o]
        correct = [i + 1 for i, o in enumerate(ctx.get('options') or []) if isinstance(o, dict) and o.get('is_correct')]
        if not correct:
            single = q['c'] if q['c'] is not None else ctx.get('answer')
            correct = [single] if isinstance(single, int) else []
        answer = options[correct[0] - 1].strip() if len(correct) == 1 and 0 < correct[0] <= len(options) else None
        place = placement.get(q['id'], {})

        def review(reason):
            q_review.append([q['id'], place.get('tb', ''), place.get('ls', ''), text, answer or '', reason])

        url = ctx.get('source_url') or ctx.get('url')
        page = page_for(url)
        spans = source_spans(page, text) if page else None
        if spans == 'AMBIGUOUS':
            stats['nguon_mau_thuan'] += 1
            review('nguồn có nhiều chỗ gạch chân khác nhau cho cùng câu')
            continue
        if spans:
            stats['gan_tu_nguon'] += 1
            q_updates.append((q['id'], text, apply_spans(text, spans), 'nguồn', q['sc']))
            continue

        has_kanji = any(is_kanji_char(c) for c in text)
        compact = [re.sub(r'[\s　]', '', o) for o in options]
        opts_kana = bool(options) and all(is_kana(o) for o in compact)
        # Chon chu Han: phuong an la TU ngan (khong phai cau tra loi doc/nghe hieu) va deu co chu Han.
        opts_kanji = bool(options) and all(len(o) <= 8 and any(is_kanji_char(c) for c in o) for o in compact)
        # Phuong an 1 ky tu (ふ/こ/く/し) hoac nhan ア/イ/ウ/エ cua bai nghe khong phai dang chon cach doc.
        if LATIN.search(text) or not text.strip() or all(len(o) <= 1 or set(o) <= set('アイウエオ') for o in compact):
            stats['ngoai_pham_vi'] += 1
            continue
        if BLANK_MARK.search(text):
            stats['ngoai_pham_vi'] += 1   # cau dien vao cho trong: khong co tu gach chan
            continue
        if opts_kana and has_kanji:
            if not answer:
                stats['duyet_tay'] += 1
                review('dạng chọn cách đọc nhưng không xác định được đáp án đúng')
                continue
            ans = kata_to_hira(re.sub(r'[\s　]', '', unicodedata.normalize('NFKC', answer)))
            cands = rule_candidates(text, ans, kanji_readings, dict_readings)
            if len(cands) == 1:
                stats['gan_theo_quy_tac'] += 1
                q_updates.append((q['id'], text, apply_spans(text, cands), 'quy tắc', q['sc']))
            else:
                stats['duyet_tay'] += 1
                review('dạng chọn cách đọc: ' + ('không tìm được vị trí khớp đáp án' if not cands
                                                  else 'có %d vị trí có thể khớp' % len(cands)))
            continue
        if opts_kanji and answer and kana_reading_in_text(text, answer, kanji_readings, dict_readings):
            stats['duyet_tay'] += 1
            review('dạng chọn chữ Hán cho từ kana: không tự gắn')
            continue
        stats['ngoai_pham_vi'] += 1

    # ---------------- Romaji + vi du ----------------
    vocab_rows = load_lesson_vocab(args)
    first_place = {}
    missing_examples = []
    missing_by_tb = defaultdict(set)
    for row in sorted(vocab_rows, key=lambda r: (r['tid'], r['lo'], r['id'])):
        first_place.setdefault(row['id'], row)
        if not row['ex'] and not row['lex']:
            missing_examples.append([row['id'], row['tb'], row['ls'], row['e']])
            missing_by_tb[row['tb']].add(row['id'])
    r_updates, r_review = romaji_plan(first_place, {})

    # ---------------- Ghi file ----------------
    write_csv('underline_review.csv', ['id', 'giao_trinh', 'bai', 'cau_hoi', 'dap_an_dung', 'ly_do'],
              sorted(q_review, key=lambda r: r[0]))
    write_csv('romaji_review.csv', ['id', 'giao_trinh', 'bai', 'tu', 'cach_doc', 'ly_do'], r_review)
    write_csv('missing_examples.csv', ['id', 'giao_trinh', 'bai', 'tu'], missing_examples)
    write_085_migration(q_updates, stats, r_updates)

    # ---------------- Bao cao ----------------
    print('[gach chan] da co marker: %d | gan tu nguon: %d | gan theo quy tac: %d | duyet tay: %d '
          '(trong do nguon mau thuan: %d) | ngoai pham vi: %d'
          % (stats['da_co_marker'], stats['gan_tu_nguon'], stats['gan_theo_quy_tac'],
             stats['duyet_tay'] + stats['nguon_mau_thuan'], stats['nguon_mau_thuan'], stats['ngoai_pham_vi']))
    print('[romaji] ti le khop: %.2f%% | da dien: %d | duyet tay: %d' % (rate, len(r_updates), len(r_review)))
    print('[vi du] thieu vi du theo giao trinh (so tu khac nhau):')
    for tb in sorted(missing_by_tb, key=lambda k: -len(missing_by_tb[k])):
        print('   %6d  %s' % (len(missing_by_tb[tb]), tb))
    print('-> %s' % os.path.relpath(MIGRATION, REPO))


def write_csv(name, header, rows):
    os.makedirs(AUDITS, exist_ok=True)
    with open(os.path.join(AUDITS, name), 'w', encoding='utf-8-sig', newline='') as fh:
        w = csv.writer(fh)
        w.writerow(header)
        w.writerows(rows)


def load_lesson_vocab(args):
    """Tu vung noi vao bai hoc (content_vocabulary), moi dong = 1 cap (tu, bai)."""
    print('[db] doc tu vung cua bai hoc...')
    return query(args, """
        SELECT JSON_OBJECT('id', v.id, 'e', v.expression, 'r', v.reading, 'm', v.reading_romaji,
                           'ne', v.normalized_expression, 'nr', v.normalized_reading,
                           'ex', (v.example_ja IS NOT NULL AND TRIM(v.example_ja) <> ''),
                           'lex', EXISTS (SELECT 1 FROM content_vocabulary cv2 JOIN content_item ci2 ON ci2.id = cv2.content_item_id
                                          JOIN lesson_section s2 ON s2.id = ci2.section_id
                                          WHERE s2.lesson_id = x.lesson_id AND cv2.vocabulary_id = v.id
                                            AND ci2.content_type = 'GRAMMAR' AND ci2.content IS NOT NULL AND TRIM(ci2.content) <> ''),
                           'tid', x.tid, 'tb', x.tb, 'lid', x.lesson_id, 'ls', x.ls, 'lo', x.lo)
        FROM (SELECT DISTINCT cv.vocabulary_id vid, l.id lesson_id, l.title ls, t.id tid, t.title tb,
                     CONCAT(LPAD(u.order_index, 6, '0'), LPAD(l.order_index, 6, '0'), LPAD(l.id, 12, '0')) lo
              FROM content_vocabulary cv JOIN content_item ci ON ci.id = cv.content_item_id
              JOIN lesson_section s ON s.id = ci.section_id JOIN lesson l ON l.id = s.lesson_id
              JOIN unit u ON u.id = l.unit_id JOIN textbook t ON t.id = u.textbook_id) x
        JOIN vocabulary_entry v ON v.id = x.vid""")


def romaji_plan(first_place, reading_override):
    """(cap nhat, duyet tay) cho tu con rong romaji; reading_override: id -> cach doc moi (086).
    Moi cap nhat = (id, cach doc, romaji, normalized_expression, normalized_reading cua cach doc do)."""
    r_updates, r_review = [], []
    for vid in sorted(first_place):
        row = first_place[vid]
        if row['m'] and row['m'].strip():
            continue
        reading = reading_override.get(vid, row['r'])
        reason = romaji_skip_reason(row['e'], reading)
        if reason:
            r_review.append([vid, row['tb'], row['ls'], row['e'], reading or '', reason])
        else:
            nr = normalize_reading(reading) if vid in reading_override else row['nr']
            r_updates.append((vid, reading, vocab_romaji(reading), row['ne'], nr))
    return r_updates, r_review


# ---------------------------------------------------------------------------------------------------------------
# Sinh SQL. Tu vung KHONG dinh danh bang id: seed Minna/vnjpclub chen vocabulary_entry khong ghi id (INSERT IGNORE theo
# khoa chuan hoa) nen id moi DB mot khac (DB local co them ~268k muc tu dien). Moi lenh dung khoa UNIQUE
# (normalized_expression, normalized_reading) + cach doc goc. Cau hoi (assessment_question) co id co dinh theo dai seed
# va con duoc rao bang noi dung goc nen giu theo id.
# ---------------------------------------------------------------------------------------------------------------

NK = 'utf8mb4_0900_as_cs'   # collation cua normalized_expression / normalized_reading (de dung index UNIQUE)


def nkstr(text):
    return '%s COLLATE %s' % (hexstr(text), NK)


def romaji_block(rows, title):
    """rows: [(normalized_expression, normalized_reading, reading, romaji)] -> bang tam + 1 lenh UPDATE ... JOIN.
    Chi ghi khi romaji con rong VA cach doc hien tai dung bang cach doc da dung de tinh romaji."""
    L = ['', title,
         'DROP TEMPORARY TABLE IF EXISTS tmp_vocab_romaji;',
         'CREATE TEMPORARY TABLE tmp_vocab_romaji (',
         '    ne VARCHAR(255) CHARACTER SET utf8mb4 COLLATE %s NOT NULL,' % NK,
         '    nr VARCHAR(255) CHARACTER SET utf8mb4 COLLATE %s NOT NULL,' % NK,
         '    reading VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,',
         '    romaji VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,',
         '    PRIMARY KEY (ne, nr)',
         ') ENGINE=InnoDB;']
    rows = sorted(set(rows))
    for i in range(0, len(rows), 500):
        chunk = rows[i:i + 500]
        L.append('INSERT INTO tmp_vocab_romaji (ne, nr, reading, romaji) VALUES')
        L.append(',\n'.join("    (%s, %s, %s, '%s')" % (hexstr(ne), hexstr(nr), hexstr(r), roma.replace("'", "''"))
                            for ne, nr, r, roma in chunk) + ';')
    L += ['UPDATE vocabulary_entry v',
          '    JOIN tmp_vocab_romaji t ON v.normalized_expression = t.ne AND v.normalized_reading = t.nr',
          'SET v.reading_romaji = t.romaji',
          "WHERE (v.reading_romaji IS NULL OR v.reading_romaji = '') AND v.reading COLLATE utf8mb4_bin = t.reading;",
          'DROP TEMPORARY TABLE IF EXISTS tmp_vocab_romaji;']
    return L


def write_lines(path, lines):
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('\n'.join(lines))


SOURCE_CODE = re.compile(r'^[A-Za-z0-9_.:-]{1,255}$')


def emit_085(q_updates, n_source, n_rule, romaji_rows):
    """q_updates: [(question_id, text_goc, text_moi, cach_gan, source_code|None)]; romaji_rows: [(ne, nr, reading, romaji)].
    Cau hoi co source_code (cau do sql/learning chen, id tu tang khac nhau giua cac DB) -> tim theo source_code (UNIQUE);
    cau hoi seed (source_code NULL, id co dinh theo dai seed) -> tim theo id."""
    by_code = sum(1 for u in q_updates if u[4])
    lines = [
        '-- 085) Gạch chân từ cần hỏi trong câu trắc nghiệm (<u>…</u> trong assessment_question.question_text)',
        '--      + romaji cho từ vựng của bài học đang để trống (vocabulary_entry.reading_romaji).',
        '-- SINH TỰ ĐỘNG bởi tools/gen_underline_romaji_migration.py — không sửa tay; sửa script rồi sinh lại.',
        '-- Chỉ sửa dữ liệu, không đổi schema. Chạy SAU các file seed VÀ sau learning data (sql/learning/learning_data_*.sql:',
        '-- dữ liệu được UPDATE phải có sẵn); nạp lại seed/learning data thì chạy lại 085 -> 086 -> 088.',
        '-- Chạy lại an toàn, đúng trên mọi DB: câu hỏi seed UPDATE theo id (id cố định theo dải seed), câu hỏi do learning',
        '-- data chèn (id tự tăng, khác nhau giữa các DB) theo source_code (UNIQUE); chỉ khi nội dung còn đúng bằng bản gốc.',
        '-- Từ vựng tìm theo khoá UNIQUE (normalized_expression, normalized_reading) — KHÔNG theo id vì id từ vựng khác',
        '-- nhau giữa các DB — và chỉ ghi khi romaji còn rỗng + cách đọc đúng bằng cách đọc đã dùng để tính romaji.',
        '-- Chuỗi tiếng Nhật ghi dạng hex.',
        '-- Câu hỏi: %d (nguồn crawl: %d, quy tắc: %d; theo source_code: %d). Romaji: %d từ.' % (
            len(q_updates), n_source, n_rule, by_code, len(romaji_rows)),
        '-- Danh sách cần duyệt tay: sql/audits/underline_review.csv, romaji_review.csv, missing_examples.csv.',
        'SET NAMES utf8mb4;',
        '',
        'START TRANSACTION;',
        '',
        '-- 1) Gạch chân từ cần hỏi',
    ]
    for qid, old, new, how, code in sorted(q_updates, key=lambda u: u[0]):
        if code:
            if not SOURCE_CODE.match(code):
                raise ValueError('source_code la: %r' % code)
            lines.append('-- %s [%s] %s' % (code, how, comment(new)))
            lines.append("UPDATE assessment_question SET question_text = %s WHERE source_code = '%s' AND question_text = %s "
                         "COLLATE utf8mb4_bin;" % (hexstr(new), code, hexstr(old)))
        else:
            lines.append('-- %d [%s] %s' % (qid, how, comment(new)))
            lines.append('UPDATE assessment_question SET question_text = %s WHERE id = %d AND question_text = %s '
                         'COLLATE utf8mb4_bin;' % (hexstr(new), qid, hexstr(old)))
    lines += romaji_block(romaji_rows, '-- 2) Romaji (Hepburn, cùng style dữ liệu từ điển hiện có: gakkou, shinbun, koohii)')
    lines += ['', 'COMMIT;', '']
    return lines


def write_085_migration(q_updates, stats, r_updates):
    write_lines(MIGRATION, emit_085(q_updates, stats['gan_tu_nguon'], stats['gan_theo_quy_tac'],
                                    [(ne, nr, reading, roma) for _, reading, roma, ne, nr in r_updates]))


# ---------------------------------------------------------------------------------------------------------------
# 086: sua cach doc dang chua chu Han (vd 住みます doc 住みます) roi dien romaji cho tu vua sua
# ---------------------------------------------------------------------------------------------------------------

READING_MIGRATION = os.path.join(REPO, 'sql', 'migrations', '086_vocab_reading_fix_and_romaji.sql')


def normalize_reading(value):
    """Quy tac cot normalized_*: katakana -> hiragana(NFKC(text)), giu ー va ・ (giong scripts/dictionary_import)."""
    return kata_to_hira(re.sub(r'\s+', ' ', unicodedata.normalize('NFKC', value or '')).strip())


def kana_reading(value):
    """Cach doc dung duoc: toan kana, hoac dang 'kana（kana）' cua tinh tu na (げんき（な）)."""
    v = (value or '').strip()
    return v if v and (is_kana(v) or KANA_PAREN.match(v)) else None


def emit_086(fixes, n_a, n_b, romaji_rows):
    """fixes: [(normalized_expression, cach doc cu, normalized_reading cu, cach doc moi, normalized_reading moi, a|b,
    mat chu)]; romaji_rows: [(ne, nr, reading, romaji)] cho cac tu vua sua."""
    lines = [
        '-- 086) Sửa cách đọc từ vựng của bài đang chứa chữ Hán (vd 住みます đọc "住みます") + romaji cho các từ vừa sửa.',
        '-- SINH TỰ ĐỘNG bởi tools/gen_underline_romaji_migration.py --reading-fix — không sửa tay.',
        '-- Chỉ sửa dữ liệu, không đổi schema. Chạy SAU seed (vnjpclub, Minna) và SAU 085.',
        '-- Nguồn cách đọc: a) cùng mặt chữ ở mục nội dung khác / thẻ flashcard, mọi cách đọc giống nhau;',
        '--                 b) JMdict trong DB, khớp chính xác mặt chữ và chỉ có 1 cách đọc. Còn lại: sql/audits/reading_review.csv.',
        '-- Chạy lại an toàn: tìm từ theo khoá UNIQUE (normalized_expression, normalized_reading) — KHÔNG theo id (id từ vựng',
        '-- khác nhau giữa các DB) — chỉ khi cách đọc hiện tại đúng bằng bản gốc và không đụng UNIQUE; romaji chỉ ghi khi',
        '-- còn rỗng và cách đọc đã là bản mới. Chuỗi tiếng Nhật ghi dạng hex.',
        '-- Cách đọc: %d (a: %d, b: %d). Romaji: %d.' % (len(fixes), n_a, n_b, len(romaji_rows)),
        'SET NAMES utf8mb4;',
        '',
        'START TRANSACTION;',
        '',
        '-- 1) Cách đọc (cập nhật kèm normalized_reading theo quy tắc katakana -> hiragana(NFKC))',
    ]
    for ne, old, old_nr, new, new_nr, how, expr in fixes:
        lines.append('-- [%s] %s: %s -> %s' % (how, comment(expr), comment(old), comment(new)))
        lines.append(
            'UPDATE vocabulary_entry SET reading = %s, normalized_reading = %s\n'
            'WHERE normalized_expression = %s AND normalized_reading = %s AND reading COLLATE utf8mb4_bin = %s\n'
            '  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM vocabulary_entry WHERE normalized_expression = %s\n'
            '                                 AND normalized_reading = %s LIMIT 1) d);'
            % (hexstr(new), hexstr(new_nr), nkstr(ne), nkstr(old_nr), hexstr(old), nkstr(ne), nkstr(new_nr)))
    lines += romaji_block(romaji_rows, '-- 2) Romaji cho từ vừa có cách đọc kana (cùng hàm/style với 085)')
    lines += ['', 'COMMIT;', '']
    return lines


def reading_fix(args, write_migration=True):
    """write_migration=False (--audit): chi tinh lai reading_review.csv + romaji_review.csv theo DB hien tai."""
    if write_migration and os.path.exists(READING_MIGRATION) and not args.force:
        sys.exit('%s da ton tai (va da chay tren DB) - khong ghi de. Dung --audit de cap nhat CSV.'
                 % os.path.relpath(READING_MIGRATION, REPO))
    rows = load_lesson_vocab(args)
    first_place = {}
    for row in sorted(rows, key=lambda r: (r['tid'], r['lo'], r['id'])):
        first_place.setdefault(row['id'], row)
    targets = {vid: r for vid, r in first_place.items() if any(is_kanji_char(c) for c in (r['r'] or ''))}
    print('[doc] tu cua bai co cach doc chua chu Han: %d' % len(targets))

    print('[db] doc cach doc o cho khac + tu dien...')
    content = defaultdict(set)      # a) muc noi dung khac (TEXTBOOK/BOTH) + the flashcard
    for row in query(args, "SELECT JSON_OBJECT('id', id, 'e', expression, 'r', reading) FROM vocabulary_entry "
                           "WHERE usage_scope IN ('TEXTBOOK', 'BOTH')"):
        if row['id'] in targets:
            continue
        r = kana_reading(row['r'])
        if r:
            content[(row['e'] or '').strip()].add(r)
    for row in query(args, "SELECT JSON_OBJECT('e', front, 'r', reading) FROM flashcard_card WHERE reading IS NOT NULL"):
        r = kana_reading(row['r'])
        if r:
            content[(row['e'] or '').strip()].add(r)
    jmdict = defaultdict(set)       # b) tu dien JMdict da nhap (usage_scope DICTIONARY)
    for row in query(args, "SELECT JSON_OBJECT('e', expression, 'r', reading) FROM vocabulary_entry "
                           "WHERE usage_scope = 'DICTIONARY' AND dictionary_status = 'PUBLISHED'"):
        r = kana_reading(row['r'])
        if r:
            jmdict[(row['e'] or '').strip()].add(r)
    taken = {}
    for row in query(args, "SELECT JSON_OBJECT('id', id, 'ne', normalized_expression, 'nr', normalized_reading) "
                           "FROM vocabulary_entry"):
        taken[(row['ne'], row['nr'])] = row['id']
        if row['id'] in targets:
            targets[row['id']]['ne'] = row['ne']

    fixes, review, stats = [], [], defaultdict(int)
    for vid in sorted(targets):
        row = targets[vid]
        expr = (row['e'] or '').strip()
        found = content.get(expr, set())
        how = None
        if len(found) == 1:
            new, how = next(iter(found)), 'a'
        elif len(found) > 1:
            review.append([vid, row['tb'], row['ls'], row['e'], row['r'],
                           'chỗ khác trong DB có nhiều cách đọc khác nhau: ' + ' / '.join(sorted(found))])
            continue
        else:
            dic = jmdict.get(expr, set())
            if len(dic) == 1:
                new, how = next(iter(dic)), 'b'
            else:
                review.append([vid, row['tb'], row['ls'], row['e'], row['r'],
                               'JMdict có %d cách đọc: %s' % (len(dic), ' / '.join(sorted(dic))) if dic
                               else 'không tìm thấy cùng mặt chữ ở chỗ khác hay trong JMdict (dạng chia ます/て, cụm, lỗi dữ liệu…)'])
                continue
        key = (row['ne'], normalize_reading(new))
        if key in taken and taken[key] != vid:
            review.append([vid, row['tb'], row['ls'], row['e'], row['r'],
                           'cách đọc đúng là %s nhưng đã có mục id %d cùng mặt chữ + cách đọc (cần gộp mục, không tự sửa)'
                           % (new, taken[key])])
            stats['trung'] += 1
            continue
        taken[key] = vid
        stats[how] += 1
        fixes.append((vid, row['r'], new, row['ne'], how, expr, row['nr']))

    # --audit: không áp cách đọc giả định, romaji_review phản ánh đúng DB hiện tại.
    override = {f[0]: f[2] for f in fixes} if write_migration else {}
    r_updates, r_review = romaji_plan(first_place, override)
    fixed_romaji = [u for u in r_updates if u[0] in override]
    if not write_migration:
        write_csv('reading_review.csv', ['id', 'giao_trinh', 'bai', 'tu', 'cach_doc_hien_tai', 'ly_do'], review)
        write_csv('romaji_review.csv', ['id', 'giao_trinh', 'bai', 'tu', 'cach_doc', 'ly_do'], r_review)
        print('[audit] reading_review: %d dong | romaji_review: %d dong | co the tu sua them theo a/b: %d | '
              'romaji co the dien them: %d' % (len(review), len(r_review), len(fixes), len(r_updates)))
        return

    write_lines(READING_MIGRATION, emit_086(
        [(ne, old, old_nr, new, normalize_reading(new), how, expr) for _, old, new, ne, how, expr, old_nr in fixes],
        stats['a'], stats['b'], [(ne, nr, reading, roma) for _, reading, roma, ne, nr in fixed_romaji]))

    write_csv('reading_review.csv', ['id', 'giao_trinh', 'bai', 'tu', 'cach_doc_hien_tai', 'ly_do'], review)
    write_csv('romaji_review.csv', ['id', 'giao_trinh', 'bai', 'tu', 'cach_doc', 'ly_do'], r_review)
    print('[doc] sua theo a: %d | theo b: %d | duyet tay: %d (trong do trung UNIQUE: %d)'
          % (stats['a'], stats['b'], len(review), stats['trung']))
    print('[romaji] dien them: %d | con duyet tay: %d' % (len(fixed_romaji), len(r_review)))
    print('-> %s' % os.path.relpath(READING_MIGRATION, REPO))


# ---------------------------------------------------------------------------------------------------------------
# 088: gop muc tu trung (muc sai: cach doc chua chu Han -> muc dung: cung mat chu, cach doc kana)
# ---------------------------------------------------------------------------------------------------------------

MERGE_MIGRATION = os.path.join(REPO, 'sql', 'migrations', '088_vocab_merge_duplicates.sql')
# Cot cua muc tu chep tu muc sai sang muc dung khi muc dung dang rong (muc dung co gia tri thi giu cua muc dung).
COPY_FIELDS = ['meaning', 'han_viet', 'word_type', 'jlpt_level', 'example_ja', 'example_vi', 'reading_romaji']
# Bang chi tiet cua muc tu (tu dien): chuyen cua muc sai sang muc dung chi khi muc dung chua co dong nao.
DETAIL_TABLES = ['vocabulary_kanji', 'vocabulary_sense', 'vocabulary_reading', 'vocabulary_variant',
                 'vocabulary_pitch_accent', 'vocabulary_antonym', 'vocabulary_external_reference']


def meaning_key(value):
    """So nghia bo qua dau cau, khoang trang, hoa/thuong: chi giu chu cai + chu so (NFKC)."""
    text = unicodedata.normalize('NFKC', value or '').lower()
    return ''.join(ch for ch in text if unicodedata.category(ch)[0] in 'LN')


def blank(value):
    return value is None or (isinstance(value, str) and not value.strip())


def merge_dupes(args):
    if os.path.exists(MERGE_MIGRATION) and not args.force:
        sys.exit('%s da ton tai (va da chay tren DB) - khong ghi de.' % os.path.relpath(MERGE_MIGRATION, REPO))
    rows = load_lesson_vocab(args)
    first_place = {}
    for row in sorted(rows, key=lambda r: (r['tid'], r['lo'], r['id'])):
        first_place.setdefault(row['id'], row)
    wrong_ids = sorted(vid for vid, r in first_place.items() if any(is_kanji_char(c) for c in (r['r'] or '')))
    print('[gop] muc tu cua bai co cach doc chua chu Han: %d' % len(wrong_ids))
    if not wrong_ids:
        return
    cols = ', '.join("'%s', v.%s" % (f, f) for f in COPY_FIELDS)
    entries = query(args, """
        SELECT JSON_OBJECT('id', v.id, 'e', v.expression, 'ne', v.normalized_expression, 'nr', v.normalized_reading,
                           'r', v.reading, 'scope', v.usage_scope, 'status', v.dictionary_status, %s)
        FROM vocabulary_entry v
        JOIN (SELECT DISTINCT normalized_expression ne FROM vocabulary_entry WHERE id IN (%s)) w
          ON w.ne = v.normalized_expression""" % (cols, ','.join(str(i) for i in wrong_ids)))
    by_id = {e['id']: e for e in entries}
    by_ne = defaultdict(list)
    for e in entries:
        by_ne[e['ne']].append(e)

    pairs, review, stats = [], [], defaultdict(int)
    strict_ambiguous = 0
    for wid in wrong_ids:
        w = by_id.get(wid)
        place = first_place[wid]
        if w is None:
            continue
        cands = [e for e in by_ne[w['ne']] if e['id'] != wid and kana_reading(e['r']) and e['status'] != 'HIDDEN']
        if len(cands) > 1:
            strict_ambiguous += 1
        content = [e for e in cands if e['scope'] in ('TEXTBOOK', 'BOTH')]
        pool = content or cands     # muc noi dung truoc; chi xet muc tu dien JMdict khi khong co muc noi dung nao

        def note(reason, c=None):
            review.append([wid, place['tb'], place['ls'], w['e'], w['r'], c['id'] if c else '', c['r'] if c else '',
                           w.get('meaning') or '', c.get('meaning') if c else '', reason])
            stats[reason.split(':')[0]] += 1

        if not pool:
            note('không có mục đúng (cùng mặt chữ, cách đọc kana)')
            continue
        if len(pool) > 1:
            note('có %d mục đúng: %s' % (len(pool), ' / '.join('%d %s' % (e['id'], e['r']) for e in pool)))
            continue
        c = pool[0]
        if not blank(w.get('meaning')) and not blank(c.get('meaning')) \
                and meaning_key(w['meaning']) != meaning_key(c['meaning']):
            note('nghĩa khác nhau rõ ràng', c)
            continue
        pairs.append((w, c))
        stats['gop'] += 1
        stats['gop_vao_muc_tu_dien' if c['scope'] == 'DICTIONARY' else 'gop_vao_muc_noi_dung'] += 1

    # Nhieu muc sai cung tro ve 1 muc dung: khong gop (tranh dung khoa khi tro lai), dua ca nhom vao duyet tay.
    per_c = defaultdict(list)
    for w, c in pairs:
        per_c[c['id']].append(w)
    for w, c in [p for p in pairs if len(per_c[p[1]['id']]) > 1]:
        review.append([w['id'], first_place[w['id']]['tb'], first_place[w['id']]['ls'], w['e'], w['r'], c['id'], c['r'],
                       w.get('meaning') or '', c.get('meaning') or '', 'nhiều mục sai cùng trỏ về 1 mục đúng'])
        stats['gop'] -= 1
        stats['gop_vao_muc_tu_dien' if c['scope'] == 'DICTIONARY' else 'gop_vao_muc_noi_dung'] -= 1
    pairs = [p for p in pairs if len(per_c[p[1]['id']]) == 1]

    # Cot chep sang muc dung khi muc dung dang rong (moi muc dung chi nhan tu dung 1 muc sai).
    copies = []
    for w, c in pairs:
        for f in COPY_FIELDS:
            if blank(c.get(f)) and not blank(w.get(f)):
                copies.append((c['ne'], c['nr'], f, w[f]))

    # Khoa tu nhien lay dung gia tri dang luu trong DB (normalized_* do seed ghi), khong tinh lai. Cot chep sang
    # (copies) chi de bao cao: 088 tinh lai luc chay tren DB dich.
    write_lines(MERGE_MIGRATION, emit_088(
        [(w['ne'], w['r'], w['nr'], c['r'], c['nr'], w['e'] + ': ' + w['r'] + ' -> ' + c['r']) for w, c in pairs],
        stats['gop_vao_muc_noi_dung'], stats['gop_vao_muc_tu_dien'], len(review), reading_fix_guards()))
    write_csv('merge_review.csv', ['id_muc_sai', 'giao_trinh', 'bai', 'tu', 'cach_doc_muc_sai', 'id_muc_dung',
                                   'cach_doc_muc_dung', 'nghia_muc_sai', 'nghia_muc_dung', 'ly_do'], review)
    print('[gop] cap gop: %d (vao muc noi dung: %d, vao muc tu dien: %d) | duyet tay: %d | cot chep sang: %d'
          % (len(pairs), stats['gop_vao_muc_noi_dung'], stats['gop_vao_muc_tu_dien'], len(review), len(copies)))
    for reason, n in sorted(stats.items()):
        if not reason.startswith('gop'):
            print('   %5d  %s' % (n, reason))
    print('[gop] neu xet ca muc tu dien ngang hang (dieu kien "duy nhat" chat nhat): %d muc sai co >= 2 muc dung'
          % strict_ambiguous)
    print('-> %s' % os.path.relpath(MERGE_MIGRATION, REPO))


def reading_fix_guards(path=None):
    """Cac cach doc 086 da sua tai cho (doi khoa tu nhien), doc tu file 086: [(ne, doc cu, nr cu, doc moi, nr moi, ghi chu)].
    Nap lai seed/learning data sau 086 se tao lai muc mang khoa cu (vd パン屋 doc パン屋); 088 gop muc do vao muc da sua."""
    path = path or READING_MIGRATION
    if not os.path.exists(path):
        return []
    text = open(path, encoding='utf-8').read()
    hx = r"CONVERT\(X'([0-9A-F]*)' USING utf8mb4\)"
    out = []
    for note, new, new_nr, ne, old_nr, old in re.findall(
            r"^-- (\[\w\] .*)\nUPDATE vocabulary_entry SET reading = %s, normalized_reading = %s\n"
            r"WHERE normalized_expression = %s COLLATE \w+ AND normalized_reading = %s COLLATE \w+ "
            r"AND reading COLLATE utf8mb4_bin = %s$" % (hx, hx, hx, hx, hx), text, re.M):
        dec = [bytes.fromhex(h).decode('utf-8') for h in (new, new_nr, ne, old_nr, old)]
        out.append((dec[2], dec[4], dec[3], dec[0], dec[1], '086 ' + note))
    if text.count('UPDATE vocabulary_entry SET reading =') != len(out):
        raise ValueError('khong doc het lenh sua cach doc trong %s' % path)
    return out


def guarded(cond, statement):
    """Mot cau lenh chi chay khi doi tuong schema co tren DB (cond = bien @has_... da tinh), nguoc lai DO 0 (khong loi).
    statement: MOT cau, khong ';' cuoi, khong chu thich '--' ben trong."""
    body = statement.strip()
    if body.endswith(';') or '--' in body:
        raise ValueError('cau lenh guarded khong hop le: %s' % body[:60])
    return ["SET @q := IF(%s, '%s', 'DO 0');" % (cond, body.replace("'", "''")),
            'PREPARE stmt FROM @q;', 'EXECUTE stmt;', 'DEALLOCATE PREPARE stmt;']


def emit_088(pairs, n_content, n_dict, n_review, guards=()):
    """pairs: [(normalized_expression, cach doc muc sai, normalized_reading sai, cach doc muc dung,
    normalized_reading dung, ghi chu)]; guards: cap cung dang voi pairs lay tu 086 (reading_fix_guards) - chi co tac
    dung khi muc mang khoa cu bi tao lai. Cot chep tu muc sai sang muc dung (COPY_FIELDS) tinh luc chay."""
    guards = [g for g in guards if (g[0], g[2]) not in {(p[0], p[2]) for p in pairs}]
    L = [
        '-- 088) Gộp mục từ trùng: mục SAI (từ của bài có cách đọc là chữ Hán, vd 住みます đọc "住みます", phần lớn do seed',
        '--      Minna tạo bản sao) -> mục ĐÚNG duy nhất cùng mặt chữ (normalized_expression) có cách đọc kana.',
        '-- SINH TỰ ĐỘNG bởi tools/gen_underline_romaji_migration.py --merge-dupes — không sửa tay.',
        '-- Chạy SAU seed (vnjpclub, Minna), SAU learning data (sql/learning/learning_data_*.sql) và SAU 085, 086; nạp lại',
        '-- seed/learning data thì chạy lại 085 -> 086 -> 088 (liên kết bị tạo lại trỏ về mục sai sẽ được trỏ lại, đúng chỗ cũ).',
        '-- Mục từ KHÔNG được định danh bằng id (id từ vựng khác nhau giữa các DB): bảng ánh xạ lưu khoá UNIQUE',
        '-- (normalized_expression, normalized_reading) của mục sai / mục đúng, id được tra lúc chạy. Chạy lại an toàn: mỗi cặp',
        '-- chỉ áp khi cả hai mục tồn tại, mục đúng không bị ẩn, cách đọc còn đúng bằng lúc sinh và nghĩa trên DB đang chạy',
        '-- không khác nhau rõ ràng; mọi bước còn lại tự thành no-op khi đã gộp. Nhóm nhiều mục sai cùng trỏ về 1 mục đúng',
        '-- (nếu có lúc chạy) bị bỏ qua.',
        '-- Quy tắc: mục đúng tìm trong mục nội dung (TEXTBOOK/BOTH) trước, chỉ xét mục từ điển JMdict khi không có mục nội dung;',
        '-- phải DUY NHẤT; nghĩa hai mục khác nhau rõ ràng thì không gộp (sql/audits/merge_review.csv).',
        '-- Mục sai KHÔNG xoá cứng: dictionary_status = HIDDEN; không bảng nghiệp vụ nào còn trỏ tới (dữ liệu riêng của mục',
        '-- như Kanji cấu thành / nghĩa từ điển / audio chỉ chuyển khi mục đúng chưa có, content_source = lịch sử nguồn).',
        '-- Thứ tự từ trong bài giữ nguyên: cột mới content_vocabulary.display_order = id từ vựng gốc (trước khi trỏ lại) cho',
        '-- các bài bị ảnh hưởng; bài khác để NULL và vẫn sắp theo id như trước.',
        '-- Cặp gộp: %d (vào mục nội dung: %d, vào mục từ điển: %d). Duyệt tay: %d.' % (
            len(pairs), n_content, n_dict, n_review),
        '-- Cặp phòng hờ cho 086: %d (086 sửa cách đọc tại chỗ nên đổi khoá; nạp lại seed/learning data sẽ tạo lại mục mang' % len(guards),
        '-- khoá cũ — cặp này gộp mục đó vào mục đã sửa; bình thường mục khoá cũ không tồn tại nên cặp tự bị bỏ qua).',
        'SET NAMES utf8mb4;',
        '',
        '-- 0) Cột thứ tự hiển thị từ trong bài (DDL, chạy lại an toàn; đặt trước transaction vì DDL tự commit).',
        "SET @has_col := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()",
        "                 AND table_name = 'content_vocabulary' AND column_name = 'display_order');",
        "SET @ddl := IF(@has_col = 0, 'ALTER TABLE content_vocabulary ADD COLUMN display_order INT NULL', 'DO 0');",
        'PREPARE stmt FROM @ddl;',
        'EXECUTE stmt;',
        'DEALLOCATE PREPARE stmt;',
        '',
        'START TRANSACTION;',
        '',
        '-- Đối tượng có thể chưa có trên DB dựng từ file -> bước tương ứng bỏ qua, không lỗi: cột learning_* của',
        '-- content_vocabulary (sql/learning/learning_schema.sql), jlpt_question.target_vocabulary_id (mất nếu chạy',
        '-- jlpt_full_setup.sql sau), daily_kana_challenge (028), quick_lookup_daily (066 / app lúc khởi động), sổ tay',
        '-- user_notebook_entry / user_notebook_review (038/039/052), gợi ý từ trái nghĩa tutor_antonym_suggestion (083).',
        "SET @has_learning := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()",
        "    AND table_name = 'content_vocabulary'",
        "    AND column_name IN ('learning_meaning_vi', 'learning_example_ja', 'learning_example_vi')) = 3;",
        "SET @has_jlpt_target := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()",
        "    AND table_name = 'jlpt_question' AND column_name = 'target_vocabulary_id') = 1;",
        "SET @has_kana := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()",
        "    AND table_name = 'daily_kana_challenge') = 1;",
        "SET @has_ql := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()",
        "    AND table_name = 'quick_lookup_daily') = 1;",
        "SET @has_notebook := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()",
        "    AND table_name IN ('user_notebook_entry', 'user_notebook_review')) = 2;",
        "SET @has_tutor := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()",
        "    AND table_name = 'tutor_antonym_suggestion') = 1;",
        '',
        '-- 1) Bảng ánh xạ mục sai -> mục đúng theo khoá tự nhiên + cách đọc gốc; id tra lúc chạy.',
        'DROP TEMPORARY TABLE IF EXISTS tmp_vocab_merge;',
        'CREATE TEMPORARY TABLE tmp_vocab_merge (',
        '    ne VARCHAR(255) CHARACTER SET utf8mb4 COLLATE %s NOT NULL,' % NK,
        '    wrong_nr VARCHAR(255) CHARACTER SET utf8mb4 COLLATE %s NOT NULL,' % NK,
        '    correct_nr VARCHAR(255) CHARACTER SET utf8mb4 COLLATE %s NOT NULL,' % NK,
        '    wrong_reading VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,',
        '    correct_reading VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,',
        '    is_guard TINYINT NOT NULL DEFAULT 0,',
        '    wrong_id BIGINT UNSIGNED NULL,',
        '    correct_id BIGINT UNSIGNED NULL,',
        '    PRIMARY KEY (ne, wrong_nr),',
        '    KEY idx_tmp_vocab_merge_wrong (wrong_id),',
        '    KEY idx_tmp_vocab_merge_correct (correct_id)',
        ') ENGINE=InnoDB;',
    ]
    for flag, group in ((0, list(pairs)), (1, list(guards))):
        for i in range(0, len(group), 200):
            chunk = group[i:i + 200]
            if flag:
                L.append('--    Cặp phòng hờ cho 086 (is_guard = 1): mục khoá cũ nếu bị tạo lại là mục MỚI (id mới), nên thứ tự trong bài')
                L.append('--    lấy theo id mục đúng (vị trí của từ trước khi bị nạp lại), không theo id mục sai.')
            L.append('INSERT INTO tmp_vocab_merge (ne, wrong_nr, correct_nr, wrong_reading, correct_reading, is_guard) VALUES')
            for j, (ne, w_r, w_nr, c_r, c_nr, note) in enumerate(chunk):
                # Dấu phẩy/chấm phẩy đặt TRƯỚC chú thích "--" (sau "--" là chú thích tới hết dòng).
                L.append('    (%s, %s, %s, %s, %s, %d)%s -- %s' % (
                    hexstr(ne), hexstr(w_nr), hexstr(c_nr), hexstr(w_r), hexstr(c_r), flag,
                    ',' if j < len(chunk) - 1 else ';', comment(note)))
    L += [
        'UPDATE tmp_vocab_merge m JOIN vocabulary_entry w',
        '    ON w.normalized_expression = m.ne AND w.normalized_reading = m.wrong_nr SET m.wrong_id = w.id;',
        'UPDATE tmp_vocab_merge m JOIN vocabulary_entry c',
        '    ON c.normalized_expression = m.ne AND c.normalized_reading = m.correct_nr SET m.correct_id = c.id;',
        'DELETE m FROM tmp_vocab_merge m',
        '    LEFT JOIN vocabulary_entry w ON w.id = m.wrong_id',
        '    LEFT JOIN vocabulary_entry c ON c.id = m.correct_id',
        'WHERE w.id IS NULL OR c.id IS NULL OR w.reading COLLATE utf8mb4_bin <> m.wrong_reading',
        "   OR c.reading COLLATE utf8mb4_bin <> m.correct_reading OR c.dictionary_status = 'HIDDEN'",
        '--    Nghĩa trên DB này khác nhau rõ ràng (cùng quy tắc với lúc sinh: bỏ dấu câu/khoảng trắng, không phân biệt hoa',
        '--    thường; seed có thể đã đổi nghĩa so với DB dùng để sinh file) thì không gộp.',
        "   OR (TRIM(COALESCE(w.meaning, '')) <> '' AND TRIM(COALESCE(c.meaning, '')) <> ''",
        "       AND REGEXP_REPLACE(LOWER(w.meaning), '[^\\\\p{L}\\\\p{N}]', '')",
        "           <> REGEXP_REPLACE(LOWER(c.meaning), '[^\\\\p{L}\\\\p{N}]', ''));",
        '--    Chốt an toàn: nhiều mục sai cùng trỏ về 1 mục đúng thì bỏ cả nhóm (trỏ lại sẽ đụng khoá), để duyệt tay.',
        'DROP TEMPORARY TABLE IF EXISTS tmp_vocab_merge_dup;',
        'CREATE TEMPORARY TABLE tmp_vocab_merge_dup (correct_id BIGINT UNSIGNED NOT NULL PRIMARY KEY) ENGINE=InnoDB',
        'SELECT correct_id FROM tmp_vocab_merge GROUP BY correct_id HAVING COUNT(*) > 1;',
        'DELETE m FROM tmp_vocab_merge m JOIN tmp_vocab_merge_dup d ON d.correct_id = m.correct_id;',
        'DROP TEMPORARY TABLE IF EXISTS tmp_vocab_merge_dup;',
        '',
        '-- 2) Giữ thứ tự từ trong các bài bị ảnh hưởng. Trang bài học sắp theo display_order (NULL xếp sau) rồi theo id từ',
        '--    vựng; trước khi gộp mọi bài đều NULL nên sắp theo id. Ghi display_order = id từ vựng HIỆN TẠI (tức id gốc, vì',
        '--    chưa trỏ lại) cho mọi dòng còn NULL của các bài có liên kết tới mục sai: thứ tự theo id gốc giữ nguyên sau khi',
        '--    đổi id. Không đánh số lại dòng đã có display_order. Liên kết bị tạo lại sau này (nạp lại seed/learning data trỏ',
        '--    về mục sai) được chạy lại 088 ghi đúng id gốc của nó nên trở về đúng chỗ cũ, không bị dồn xuống cuối bài.',
        'DROP TEMPORARY TABLE IF EXISTS tmp_vocab_merge_lesson;',
        'CREATE TEMPORARY TABLE tmp_vocab_merge_lesson (lesson_id BIGINT UNSIGNED NOT NULL PRIMARY KEY) ENGINE=InnoDB',
        'SELECT DISTINCT s.lesson_id FROM content_vocabulary cv',
        '    JOIN tmp_vocab_merge m ON m.wrong_id = cv.vocabulary_id',
        '    JOIN content_item ci ON ci.id = cv.content_item_id',
        '    JOIN lesson_section s ON s.id = ci.section_id;',
        'UPDATE content_vocabulary cv',
        '    JOIN content_item ci ON ci.id = cv.content_item_id',
        '    JOIN lesson_section s ON s.id = ci.section_id',
        '    JOIN tmp_vocab_merge_lesson l ON l.lesson_id = s.lesson_id',
        '    LEFT JOIN tmp_vocab_merge g ON g.wrong_id = cv.vocabulary_id AND g.is_guard = 1',
        'SET cv.display_order = COALESCE(g.correct_id, cv.vocabulary_id)',
        'WHERE cv.display_order IS NULL AND COALESCE(g.correct_id, cv.vocabulary_id) <= 2147483647;',
        '',
        '-- 3) Chép dữ liệu mục sai sang mục đúng khi mục đúng đang rỗng (mục đúng đã có giá trị thì giữ của mục đúng).',
        '--    Xét trên dữ liệu của chính DB đang chạy (seed có thể khác DB dùng để sinh file), từng cột: ' +
        ', '.join(COPY_FIELDS) + '.',
    ]
    for f in COPY_FIELDS:
        L.append('UPDATE vocabulary_entry c JOIN tmp_vocab_merge m ON m.correct_id = c.id JOIN vocabulary_entry w ON w.id = m.wrong_id\n'
                 "SET c.{0} = w.{0} WHERE (c.{0} IS NULL OR c.{0} = '') AND w.{0} IS NOT NULL AND w.{0} <> '';".format(f))
    L += [
        '',
        '-- 4) Liên kết từ - bài (content_vocabulary). Cùng content_item đã có cả mục đúng: giữ vị trí xuất hiện sớm hơn,',
        '--    chép phần nghĩa/ví dụ theo bài còn thiếu sang dòng của mục đúng rồi bỏ dòng của mục sai; còn lại đổi',
        '--    vocabulary_id (giữ display_order).',
        'UPDATE content_vocabulary cvc',
        '    JOIN tmp_vocab_merge m ON m.correct_id = cvc.vocabulary_id',
        '    JOIN content_vocabulary cvw ON cvw.content_item_id = cvc.content_item_id AND cvw.vocabulary_id = m.wrong_id',
        'SET cvc.display_order = CASE WHEN cvc.display_order IS NULL THEN cvw.display_order',
        '                             WHEN cvw.display_order IS NULL THEN cvc.display_order',
        '                             ELSE LEAST(cvc.display_order, cvw.display_order) END;',
    ]
    L += guarded('@has_learning', '\n'.join([
        'UPDATE content_vocabulary cvc',
        '    JOIN tmp_vocab_merge m ON m.correct_id = cvc.vocabulary_id',
        '    JOIN content_vocabulary cvw ON cvw.content_item_id = cvc.content_item_id AND cvw.vocabulary_id = m.wrong_id',
        'SET cvc.learning_meaning_vi = COALESCE(cvc.learning_meaning_vi, cvw.learning_meaning_vi),',
        '    cvc.learning_example_ja = COALESCE(cvc.learning_example_ja, cvw.learning_example_ja),',
        '    cvc.learning_example_vi = COALESCE(cvc.learning_example_vi, cvw.learning_example_vi)']))
    L += [
        'DELETE cvw FROM content_vocabulary cvw',
        '    JOIN tmp_vocab_merge m ON m.wrong_id = cvw.vocabulary_id',
        '    JOIN content_vocabulary cvc ON cvc.content_item_id = cvw.content_item_id AND cvc.vocabulary_id = m.correct_id;',
        'UPDATE content_vocabulary cv JOIN tmp_vocab_merge m ON m.wrong_id = cv.vocabulary_id',
        'SET cv.vocabulary_id = m.correct_id;',
        '',
        '-- 5) Audio của từ (content_audio, owner_type VOCABULARY_WORD): chuyển khi mục đúng chưa có audio cùng giọng.',
        'UPDATE content_audio aw',
        '    JOIN tmp_vocab_merge m ON aw.owner_type = \'VOCABULARY_WORD\' AND aw.owner_id = m.wrong_id',
        '    LEFT JOIN content_audio ac ON ac.owner_type = \'VOCABULARY_WORD\' AND ac.owner_id = m.correct_id',
        '                              AND ac.voice_code = aw.voice_code',
        'SET aw.owner_id = m.correct_id',
        'WHERE ac.id IS NULL;',
        '',
        '-- 6) Dữ liệu chi tiết của mục từ (Kanji cấu thành, nghĩa từ điển…): chuyển của MỘT mục sai (id nhỏ nhất có dữ',
        '--    liệu) sang mục đúng khi mục đúng chưa có dòng nào ở bảng đó; mục đúng đã có thì giữ của mục đúng.',
    ]
    for t in DETAIL_TABLES:
        L += [
            'DROP TEMPORARY TABLE IF EXISTS tmp_vocab_move;',
            'CREATE TEMPORARY TABLE tmp_vocab_move (wrong_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, '
            'correct_id BIGINT UNSIGNED NOT NULL) ENGINE=InnoDB',
            'SELECT MIN(m.wrong_id) AS wrong_id, m.correct_id FROM tmp_vocab_merge m',
            '    JOIN (SELECT DISTINCT vocabulary_id FROM %s) has_w ON has_w.vocabulary_id = m.wrong_id' % t,
            '    LEFT JOIN (SELECT DISTINCT vocabulary_id FROM %s) has_c ON has_c.vocabulary_id = m.correct_id' % t,
            'WHERE has_c.vocabulary_id IS NULL GROUP BY m.correct_id;',
            'UPDATE %s t JOIN tmp_vocab_move x ON x.wrong_id = t.vocabulary_id SET t.vocabulary_id = x.correct_id;' % t,
        ]
    L += [
        'DROP TEMPORARY TABLE IF EXISTS tmp_vocab_move;',
        '',
        '-- 7) Tham chiếu một-một (không có khoá trùng): đề thi JLPT, câu hỏi, lộ trình, lịch sử tra cứu, thử thách kana,',
        '--    bình luận từ điển.',
        'UPDATE assessment_question q JOIN tmp_vocab_merge m ON m.wrong_id = q.vocabulary_entry_id SET q.vocabulary_entry_id = m.correct_id;',
        'UPDATE user_learning_path_item i JOIN tmp_vocab_merge m ON m.wrong_id = i.vocabulary_entry_id SET i.vocabulary_entry_id = m.correct_id;',
        'UPDATE dictionary_search_history h JOIN tmp_vocab_merge m ON m.wrong_id = h.selected_vocabulary_id SET h.selected_vocabulary_id = m.correct_id;',
        "UPDATE content_comment cc JOIN tmp_vocab_merge m ON cc.target_type = 'VOCABULARY' AND m.wrong_id = cc.target_id SET cc.target_id = m.correct_id;",
    ]
    L += guarded('@has_jlpt_target', 'UPDATE jlpt_question q JOIN tmp_vocab_merge m ON m.wrong_id = q.target_vocabulary_id '
                                     'SET q.target_vocabulary_id = m.correct_id')
    L += guarded('@has_kana', 'UPDATE daily_kana_challenge d JOIN tmp_vocab_merge m ON m.wrong_id = d.vocabulary_id '
                              'SET d.vocabulary_id = m.correct_id')
    L += [
        '',
        '-- 8) Từ yêu thích (PK user + từ): đã có cả hai thì giữ dòng của mục đúng (bù ghi chú còn thiếu, giữ ngày lưu sớm hơn).',
        'UPDATE dictionary_favorite fc',
        '    JOIN tmp_vocab_merge m ON m.correct_id = fc.vocabulary_id',
        '    JOIN dictionary_favorite fw ON fw.user_id = fc.user_id AND fw.vocabulary_id = m.wrong_id',
        'SET fc.note = COALESCE(fc.note, fw.note), fc.created_at = LEAST(fc.created_at, fw.created_at);',
        'DELETE fw FROM dictionary_favorite fw',
        '    JOIN tmp_vocab_merge m ON m.wrong_id = fw.vocabulary_id',
        '    JOIN dictionary_favorite fc ON fc.user_id = fw.user_id AND fc.vocabulary_id = m.correct_id;',
        'UPDATE dictionary_favorite f JOIN tmp_vocab_merge m ON m.wrong_id = f.vocabulary_id SET f.vocabulary_id = m.correct_id;',
        '',
        '-- 9) Tra nhanh trong ngày (PK user + ngày + từ): trùng thì bỏ dòng của mục sai.',
    ]
    L += guarded('@has_ql', '\n'.join([
        'DELETE qw FROM quick_lookup_daily qw',
        '    JOIN tmp_vocab_merge m ON m.wrong_id = qw.vocabulary_id',
        '    JOIN quick_lookup_daily qc ON qc.user_id = qw.user_id AND qc.lookup_date = qw.lookup_date',
        '                              AND qc.vocabulary_id = m.correct_id']))
    L += guarded('@has_ql', 'UPDATE quick_lookup_daily q JOIN tmp_vocab_merge m ON m.wrong_id = q.vocabulary_id '
                            'SET q.vocabulary_id = m.correct_id')
    L += [
        '',
        '-- 10) Sổ tay + tiến độ ôn (user_notebook_entry / user_notebook_review, FK ghép chỉ ON DELETE CASCADE).',
        '--     Sổ tay đã có cả hai từ: giữ tiến độ CAO HƠN (repetitions lớn hơn; bằng nhau thì lần ôn gần nhất mới hơn),',
        '--     ghi vào dòng của mục đúng, ngày gặp đầu tiên lấy sớm hơn; có từ đúng nhưng chưa có tiến độ thì chép tiến độ',
        '--     của mục sai sang; sau đó bỏ dòng mục sai. Chỉ có mục sai: tạo dòng mục đúng, chuyển tiến độ, bỏ dòng mục sai.',
    ]
    for statement in [
        ['UPDATE user_notebook_review rc',
         '    JOIN tmp_vocab_merge m ON m.correct_id = rc.vocabulary_id',
         '    JOIN user_notebook_review rw ON rw.notebook_id = rc.notebook_id AND rw.vocabulary_id = m.wrong_id',
         'SET rc.ease_factor = rw.ease_factor, rc.repetitions = rw.repetitions, rc.interval_days = rw.interval_days,',
         '    rc.due_at = rw.due_at, rc.last_reviewed_at = rw.last_reviewed_at',
         'WHERE rw.repetitions > rc.repetitions',
         "   OR (rw.repetitions = rc.repetitions AND COALESCE(rw.last_reviewed_at, '1000-01-01') > COALESCE(rc.last_reviewed_at, '1000-01-01'))"],
        ['UPDATE user_notebook_review rc',
         '    JOIN tmp_vocab_merge m ON m.correct_id = rc.vocabulary_id',
         '    JOIN user_notebook_review rw ON rw.notebook_id = rc.notebook_id AND rw.vocabulary_id = m.wrong_id',
         'SET rc.first_seen_at = LEAST(rc.first_seen_at, rw.first_seen_at)'],
        ['INSERT INTO user_notebook_review (notebook_id, vocabulary_id, ease_factor, repetitions, interval_days, due_at,',
         '                                  first_seen_at, last_reviewed_at)',
         'SELECT rw.notebook_id, m.correct_id, rw.ease_factor, rw.repetitions, rw.interval_days, rw.due_at,',
         '       rw.first_seen_at, rw.last_reviewed_at',
         'FROM user_notebook_review rw',
         '    JOIN tmp_vocab_merge m ON m.wrong_id = rw.vocabulary_id',
         '    JOIN user_notebook_entry ec ON ec.notebook_id = rw.notebook_id AND ec.vocabulary_id = m.correct_id',
         '    LEFT JOIN user_notebook_review rc ON rc.notebook_id = rw.notebook_id AND rc.vocabulary_id = m.correct_id',
         'WHERE rc.notebook_id IS NULL'],
        ['UPDATE user_notebook_entry ec',
         '    JOIN tmp_vocab_merge m ON m.correct_id = ec.vocabulary_id',
         '    JOIN user_notebook_entry ew ON ew.notebook_id = ec.notebook_id AND ew.vocabulary_id = m.wrong_id',
         'SET ec.created_at = LEAST(ec.created_at, ew.created_at)'],
        ['DELETE ew FROM user_notebook_entry ew',
         '    JOIN tmp_vocab_merge m ON m.wrong_id = ew.vocabulary_id',
         '    JOIN user_notebook_entry ec ON ec.notebook_id = ew.notebook_id AND ec.vocabulary_id = m.correct_id'],
        ['INSERT INTO user_notebook_entry (notebook_id, vocabulary_id, created_at)',
         'SELECT ew.notebook_id, m.correct_id, ew.created_at FROM user_notebook_entry ew',
         '    JOIN tmp_vocab_merge m ON m.wrong_id = ew.vocabulary_id'],
        ['UPDATE user_notebook_review r JOIN tmp_vocab_merge m ON m.wrong_id = r.vocabulary_id SET r.vocabulary_id = m.correct_id'],
        ['DELETE ew FROM user_notebook_entry ew JOIN tmp_vocab_merge m ON m.wrong_id = ew.vocabulary_id'],
    ]:
        L += guarded('@has_notebook', '\n'.join(statement))
    L += [
        '',
        '-- 11) Gợi ý từ trái nghĩa của gia sư AI (tutor_antonym_suggestion, UNIQUE theo từ được hỏi): mục đúng đã có gợi ý',
        '--     thì bỏ dòng của mục sai, còn lại trỏ sang mục đúng; từ trái nghĩa trỏ thẳng sang mục đúng.',
    ]
    L += guarded('@has_tutor', '\n'.join([
        'DELETE tw FROM tutor_antonym_suggestion tw',
        '    JOIN tmp_vocab_merge m ON m.wrong_id = tw.vocabulary_id',
        '    JOIN tutor_antonym_suggestion tc ON tc.vocabulary_id = m.correct_id']))
    L += guarded('@has_tutor', 'UPDATE tutor_antonym_suggestion t JOIN tmp_vocab_merge m ON m.wrong_id = t.vocabulary_id '
                               'SET t.vocabulary_id = m.correct_id')
    L += guarded('@has_tutor', 'UPDATE tutor_antonym_suggestion t JOIN tmp_vocab_merge m ON m.wrong_id = t.antonym_vocabulary_id '
                               'SET t.antonym_vocabulary_id = m.correct_id')
    L += [
        '',
        '-- 12) Mục sai: ngừng dùng (xoá mềm), không xoá cứng.',
        "UPDATE vocabulary_entry w JOIN tmp_vocab_merge m ON m.wrong_id = w.id SET w.dictionary_status = 'HIDDEN'",
        "WHERE w.dictionary_status <> 'HIDDEN';",
        '',
        'DROP TEMPORARY TABLE IF EXISTS tmp_vocab_merge_lesson;',
        'DROP TEMPORARY TABLE IF EXISTS tmp_vocab_merge;',
        'COMMIT;',
        '',
    ]
    return L


if __name__ == '__main__':
    main()
