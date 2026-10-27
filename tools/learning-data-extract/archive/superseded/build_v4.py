"""
PARSER v4 - Full rewrite based on document structure investigation.

Key findings:
1. N5 grammar: Tables with ①②③ rows (each table row = one pattern)
   - Block 174: grammar table for L01, etc.
   - Only ONE grammar table found with ①-⑩ (block 174); others are in numbered list format
2. N5 vocab: 
   - Tab-delimited: "私\ttôi" → expression=私, meaning=tôi
   - Kana runs concatenated before: "わたしたちかれ" = buffer for next few entries
   - Need to track kana buffer and match to subsequent kanji\tmeaning lines
3. N4 vocab: 
   - TABLE with [kana, kanji, meaning] (correct 3-col format, some have merged rows)  
   - Inline: "気分がいい cảm thấy..." (kanji+meaning in same line, space-separated)
   - Multi-line: kana line, kanji line, meaning line
4. Kanji book: ALL PARAGRAPH-based, no tables with compounds
   - Pattern: "001\n力\nLỰC\n力\n動物\nkana-corrupt\nmeaning_vi"
   - number → kanji → HAN_VIET → reading → compound_expr → compound_reading → meaning
5. Grammar boundaries by vocab section count (42 vocab events → 25 lessons)
6. Kana appears concatenated: "わたしたちかれ" before "私\ttôi", "彼\t...", "彼女\t..."
"""

import shutil
import re
import sys
import unicodedata
from collections import defaultdict
from openpyxl import load_workbook
import docx
from docx.oxml.table import CT_Tbl
from docx.oxml.text.paragraph import CT_P
from docx.table import _Cell, Table
from docx.text.paragraph import Paragraph

sys.stdout.reconfigure(encoding='utf-8')

SRC_EXCEL = r"C:\Users\LAPTOP LENOVO\Downloads\jlas-learning-path-n5-n4-data-template.xlsx"
DST_EXCEL = r"D:\FALL26_FPT\SEP490\outputs\01a0ed4f-adc7-75e0-bf3a-f82644d49c20\jlas-learning-path-n5-n4-data-template.xlsx"
REPORT_PATH = r"D:\FALL26_FPT\SEP490\outputs\01a0ed4f-adc7-75e0-bf3a-f82644d49c20\extraction_report_v4.txt"

shutil.copy(SRC_EXCEL, DST_EXCEL)
wb = load_workbook(DST_EXCEL)

SHEET_CONTRACTS = {
    "BOOK_CATALOG": ["source_book_code", "jlpt_level", "source_role"],
    "LP_TEXTBOOK": ["textbook_code", "source_book_code", "title", "jlpt_level", "status", "version", "is_learning_path_source"],
    "LP_UNIT": ["unit_code", "textbook_code", "title", "order_index", "source_book_code"],
    "LP_LESSON": ["lesson_code", "unit_code", "source_book_code", "source_lesson_ref", "title", "order_index", "estimated_minutes", "lesson_type", "primary_skill", "knowledge_tag_code", "is_review", "status"],
    "LESSON_VOCAB": ["membership_code", "lesson_code", "source_book_code", "source_lesson_ref", "expression", "reading", "meaning_vi", "order_index", "is_core", "canonical_lookup_status"],
    "LESSON_KANJI": ["membership_code", "source_book_code", "source_session_ref", "character", "order_index", "canonical_lookup_status", "lesson_code"],
    "KANJI_COMPOUND": ["link_code", "kanji_membership_code", "vocabulary_membership_code", "character", "vocabulary_expression", "vocabulary_reading", "character_position"],
    "LESSON_GRAMMAR": ["grammar_code", "lesson_code", "source_book_code", "source_lesson_ref", "pattern", "meaning_vi", "order_index", "canonical_lookup_status"],
    "DIALOGUE": ["dialogue_code", "destination_scope", "source_book_code", "source_lesson_ref", "title", "turn_index", "speaker", "text_ja", "translation_vi", "register", "is_key_phrase"],
    "COMPREHENSION": ["content_code", "source_book_code", "source_lesson_ref", "skill", "source_kind", "title", "passage_or_script", "question_code", "question_text", "question_type", "import_question_type", "correct_answer", "order_index", "lesson_code"],
    "EXERCISE": ["exercise_code", "source_book_code", "source_lesson_ref", "section_type", "skill", "question_type", "import_question_type", "prompt", "correct_answer", "order_index", "lesson_code"],
    "FOUNDATION_CHAR": ["character_code", "script_type", "character", "romaji", "order_index", "source_status"],
}

if 'KANJI_COMPOUND' not in wb.sheetnames:
    wb.create_sheet('KANJI_COMPOUND')

for sheet_name, required_headers in SHEET_CONTRACTS.items():
    if sheet_name not in wb.sheetnames:
        continue
    ws = wb[sheet_name]
    if ws.cell(row=1, column=1).value is None:
        ws.cell(row=1, column=1).value = f"Sheet: {sheet_name}"
    if ws.max_row >= 5:
        ws.delete_rows(5, ws.max_row - 4)
    existing = []
    for c in range(1, ws.max_column + 1):
        val = str(ws.cell(row=4, column=c).value).replace('*', '').strip()
        if val not in ('None', ''):
            existing.append(val)
    for h in required_headers:
        if h not in existing:
            ws.cell(row=4, column=ws.max_column + 1).value = h + '*'
            existing.append(h)


def get_col(ws, name):
    for c in range(1, ws.max_column + 1):
        v = str(ws.cell(row=4, column=c).value).replace('*', '').strip()
        if v == name:
            return c
    return None


def append_row(ws, data):
    row = max(ws.max_row + 1, 5)
    for k, v in data.items():
        c = get_col(ws, k)
        if c:
            ws.cell(row=row, column=c).value = v


# ── Seed catalog rows ─────────────────────────────────────────────────────────
ws_books = wb['BOOK_CATALOG']
append_row(ws_books, {'source_book_code': 'N5-CORE', 'jlpt_level': 'N5', 'source_role': 'CORE_CURRICULUM'})
append_row(ws_books, {'source_book_code': 'N4-CORE', 'jlpt_level': 'N4', 'source_role': 'CORE_CURRICULUM'})
append_row(ws_books, {'source_book_code': 'N4-KANJI', 'jlpt_level': 'N4', 'source_role': 'SUPPLEMENTARY'})

ws_textbook = wb['LP_TEXTBOOK']
append_row(ws_textbook, {'textbook_code': 'N5-CORE-TB', 'source_book_code': 'N5-CORE', 'title': 'N5 Textbook', 'jlpt_level': 'N5', 'status': 'DRAFT', 'version': 1, 'is_learning_path_source': 1})
append_row(ws_textbook, {'textbook_code': 'N4-CORE-TB', 'source_book_code': 'N4-CORE', 'title': 'N4 Textbook', 'jlpt_level': 'N4', 'status': 'DRAFT', 'version': 1, 'is_learning_path_source': 1})

ws_unit = wb['LP_UNIT']
append_row(ws_unit, {'unit_code': 'N5-U01', 'textbook_code': 'N5-CORE-TB', 'title': 'N5 Bài 1-25', 'order_index': 1, 'source_book_code': 'N5-CORE'})
append_row(ws_unit, {'unit_code': 'N4-U01', 'textbook_code': 'N4-CORE-TB', 'title': 'N4 Bài 1-22', 'order_index': 2, 'source_book_code': 'N4-CORE'})


# ── Text utilities ─────────────────────────────────────────────────────────────

def clean(t):
    t = str(t or '').replace('\u3000', ' ')
    t = re.sub(r'\s{2,}', ' ', t)
    return t.strip()

def has_kanji(t):
    return any('\u4e00' <= c <= '\u9faf' for c in t)

def has_hiragana(t):
    return any('\u3041' <= c <= '\u309f' for c in t)

def has_katakana(t):
    return any('\u30a0' <= c <= '\u30ff' for c in t)

def is_japanese(t):
    return has_kanji(t) or has_hiragana(t) or has_katakana(t)

VI_CHARS = set('àáâãèéêìíòóôõùúýăđơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ')

def is_vietnamese(t):
    return any(c in VI_CHARS for c in t.lower())

def is_pure_kana(t):
    t = t.strip()
    if not t: return False
    return all((has_hiragana(c) or has_katakana(c) or c in '（）()ー・ ') for c in t)

def kana_only(t):
    return ''.join(c for c in t if has_hiragana(c) or has_katakana(c) or c == 'ー')


def iter_blocks(doc):
    for child in doc.element.body.iterchildren():
        if isinstance(child, CT_P):
            yield Paragraph(child, doc)
        elif isinstance(child, CT_Tbl):
            yield Table(child, doc)


def extract_toc(doc, level):
    """Extract lesson titles from TOC."""
    titles = {}
    for block in iter_blocks(doc):
        if not isinstance(block, Paragraph):
            continue
        text = clean(block.text)
        m = re.match(r'(?:BÀI|Bài)\s+(\d+)[:：]?\s*(.*?)\s*\t\d+', text)
        if m:
            titles[int(m.group(1))] = m.group(2).strip()
    return titles


SECTION_KW = {
    'VOCAB':   ['Từ vựng', 'Từ vfing', 'ことば', '言葉'],
    'GRAMMAR': ['Ngữ pháp', '文法'],
    'DIALOGUE':['Hội thoại', '会話'],
    'EXERCISE':['Luyện tập', '練習'],
    'KANJI':   ['Chữ Hán', '漢字'],
}

def detect_section(text):
    if len(text) > 60 or re.search(r'\t\d+\s*$', text):
        return None
    for sec, kws in SECTION_KW.items():
        if any(k in text for k in kws):
            return sec
    return None


# ── N5 Vocabulary parser ───────────────────────────────────────────────────────
# Structure: kana buffer runs (e.g. "わたしたちかれ") followed by "漢字\tmeaning" lines
# Also: TABLE with [kana, kanji, meaning]

def parse_n5_vocab_blocks(blocks, lesson_num, book_code):
    """
    Parse vocab blocks in N5. Returns list of (expr, reading, meaning).
    
    N5 uses two formats:
    1. Kana-run + tab-lines: "わたし" then "私\ttôi"
    2. TABLE [kana, kanji, meaning]
    """
    results = []
    kana_queue = []  # Available kana readings

    for block in blocks:
        if isinstance(block, Paragraph):
            text = clean(block.text)
            if not text:
                kana_queue.clear()
                continue

            # Skip section headings and exercise content
            if detect_section(text):
                kana_queue.clear()
                continue

            # Kana-only concatenated run: "わたしたちかれ" → split into individual kana tokens
            if is_pure_kana(text) and len(text) > 2 and '\t' not in text:
                # Extract all kana sub-tokens by splitting on katakana boundaries
                # Try to split by finding known kana words
                tokens = re.findall(r'[ぁ-ん]+(?:[ぁ-ん]*[ー])?|[ァ-ン]+(?:[ァ-ン]*[ー])?|[A-Za-z]+|（[^）]+）', text)
                if tokens:
                    kana_queue.extend(tokens)
                else:
                    kana_queue.append(text)
                continue

            # Tab-delimited: "漢字\tmeaning" or "kana\t漢字" (some weird OCR)
            if '\t' in text:
                parts = [p.strip() for p in text.split('\t')]
                if len(parts) >= 2:
                    first, last = parts[0], parts[-1]
                    # Case: "漢字\tmeaning_vi"
                    if is_japanese(first) and not is_japanese(last) and len(last) > 0:
                        # Try to get reading from kana_queue
                        reading = kana_queue.pop(0) if kana_queue else ''
                        # If reading is empty and first is pure kana, use it
                        if not reading and is_pure_kana(first):
                            reading = first
                        # If reading still empty, leave blank (unresolved)
                        results.append((first, reading, last))
                        continue
                    # Case: "kana\t漢字" (inverted, OCR artifact)
                    if is_pure_kana(first) and is_japanese(last) and not is_pure_kana(last):
                        kana_queue.insert(0, first)
                        continue

            # Single Japanese word (no tab) followed by Vietnamese
            # e.g. "会社員" then next paragraph is "nhân viên công ty"
            if has_kanji(text) and not '\t' in text and len(text) < 15:
                # buffer for next meaning line
                pass  # handled by pending_expr below

        elif isinstance(block, Table):
            # TABLE [kana, kanji, meaning] or [kana+kanji, meaning]
            kana_queue.clear()
            for row in block.rows:
                cells = [clean(c.text) for c in row.cells]
                unique = list(dict.fromkeys([c for c in cells if c]))
                if not unique:
                    continue
                first = unique[0]
                # Skip headers
                if any(h in first for h in ['ひらがな', 'ことば', 'TỪ VỰNG', 'THAM KHẢO']):
                    continue
                if len(unique) == 3:
                    kana, kanji, meaning = unique[0], unique[1], unique[2]
                    if is_japanese(kana) and not is_japanese(meaning):
                        expr = kanji if has_kanji(kanji) else kana
                        reading = kana if is_pure_kana(kana) else ''
                        results.append((expr, reading, meaning))
                elif len(unique) == 2:
                    expr, meaning = unique[0], unique[1]
                    if is_japanese(expr) and not is_japanese(meaning):
                        reading = expr if is_pure_kana(expr) else ''
                        results.append((expr, reading, meaning))

    return results


def parse_n5_grammar_blocks(blocks, lesson_num, book_code):
    """
    Parse N5 grammar. N5 grammar is in TABLES with ①②③ rows.
    Each row: ['① N1', 'は', 'N2 です。'] → pattern = 'N1 は N2 です。'
    Also appears as numbered paragraphs.
    """
    results = []
    for block in blocks:
        if isinstance(block, Table):
            for row in block.rows:
                cells = [clean(c.text) for c in row.cells]
                # Merge all cells to get full pattern
                # Row might be split across cells: ['① N1', 'は', 'N2 です。']
                full = ' '.join([c for c in cells if c])
                full = re.sub(r'\s{2,}', ' ', full).strip()
                # Must start with circled number
                if re.match(r'^[①-⑩⑪-⑳]', full) and is_japanese(full):
                    # Remove the circled number prefix
                    pattern = re.sub(r'^[①-⑩⑪-⑳]\s*', '', full).strip()
                    if len(pattern) > 2:
                        results.append((pattern, ''))
        elif isinstance(block, Paragraph):
            text = clean(block.text)
            if not text:
                continue
            # Numbered grammar points: "① Vます。" or "1. Nはです。"
            gm = re.match(r'^(?:[①-⑩⑪-⑳]|[0-9]+[\.．])\s*(.{5,})', text)
            if gm:
                raw = gm.group(1).strip()
                if is_japanese(raw) and len(raw) < 100:
                    # Try to split off Vietnamese meaning if any
                    parts = re.split(r'\s{3,}', raw)
                    if len(parts) > 1 and is_vietnamese(parts[-1]) and not is_japanese(parts[-1]):
                        pattern = ' '.join(parts[:-1]).strip()
                        meaning = parts[-1].strip()
                    else:
                        pattern = raw
                        meaning = ''
                    results.append((pattern, meaning))
    return results


# ── N4 Vocabulary parser ───────────────────────────────────────────────────────
# Multiple formats:
# 1. TABLE [kana, kanji, meaning] — some rows have merged cells
# 2. Inline kanji+space+meaning: "気分がいい cảm thấy thoải mái"
# 3. Tab-delimited: "出す\tI [ごみを～] đưa ra"

def parse_n4_vocab_blocks(blocks):
    results = []
    for block in blocks:
        if isinstance(block, Paragraph):
            text = clean(block.text)
            if not text or not is_japanese(text):
                continue
            if detect_section(text):
                continue
            # Skip page numbers, punctuation-only
            if re.match(r'^[\d\s\.]+$', text):
                continue

            # Format: "漢字 meaning_vi" — space-separated kanji+meaning in ONE paragraph
            if has_kanji(text) and is_vietnamese(text) and '\t' not in text:
                # Split at the point where Japanese ends and Vietnamese begins
                # Find first Vietnamese character position
                vi_pos = -1
                for idx, ch in enumerate(text):
                    if ch in VI_CHARS or (not is_japanese(ch) and ch.isalpha() and idx > 0):
                        vi_pos = idx
                        break
                if vi_pos > 2:
                    expr = text[:vi_pos].strip()
                    meaning = text[vi_pos:].strip()
                    # Clean expr: remove verb class markers like "I", "II", "III"
                    expr = re.sub(r'\s+[IVX]+\s*$', '', expr).strip()
                    if expr and meaning and is_japanese(expr) and not is_japanese(meaning):
                        results.append((expr, '', meaning))

            # Tab-delimited: "出す\tI [ごみを～] đưa ra"
            elif '\t' in text and has_kanji(text):
                parts = text.split('\t')
                expr = clean(parts[0])
                # Rest may include verb class then meaning
                rest = clean(parts[-1])
                # Remove verb class markers
                rest = re.sub(r'^[IVX]+\s+', '', rest).strip()
                # rest might be "[ごみを～] đưa ra, gửi đi"
                if is_vietnamese(rest) and not is_japanese(rest.replace('[', '').replace(']', '')):
                    # Remove [example pattern] prefix
                    rest = re.sub(r'^\[[^\]]+\]\s*', '', rest).strip()
                    if rest:
                        results.append((expr, '', rest))

        elif isinstance(block, Table):
            # TABLE with [kana, kanji, meaning]
            in_tham_khao = False
            for row in block.rows:
                cells = [clean(c.text) for c in row.cells]
                unique = list(dict.fromkeys([c for c in cells if c]))
                if not unique:
                    continue
                first = unique[0]
                if 'THAM KHẢO' in first or 'TỪ VỰNG' in first:
                    in_tham_khao = True
                if any(h in first for h in ['ひらがな', 'かんじ', 'ベトナムご']):
                    continue
                if len(unique) >= 3:
                    kana, kanji, meaning = unique[0], unique[1], unique[2]
                    # Filter out verb conjugation rows (みます, 見ます)
                    if re.search(r'ます$|ました$|ません$', kana) and re.search(r'ます$|ました$|ません$', kanji):
                        continue
                    if is_japanese(kana) and not is_japanese(meaning) and len(meaning) > 1:
                        expr = kanji if has_kanji(kanji) else kana
                        reading = kana if is_pure_kana(kana) else ''
                        results.append((expr, reading, meaning))
                elif len(unique) == 2:
                    expr, meaning = unique[0], unique[1]
                    if is_japanese(expr) and not is_japanese(meaning) and len(meaning) > 1:
                        reading = expr if is_pure_kana(expr) else ''
                        results.append((expr, reading, meaning))
    return results


# ── Main book parser ───────────────────────────────────────────────────────────

def parse_main_book(doc_path, level, book_code, unit_code, expected_lessons):
    try:
        doc = docx.Document(doc_path)
    except Exception as e:
        print(f"ERROR: {e}")
        return {}, {}, {}

    ws_lesson = wb['LP_LESSON']
    ws_vocab = wb['LESSON_VOCAB']
    ws_grammar = wb['LESSON_GRAMMAR']

    lesson_titles = extract_toc(doc, level)
    print(f"  TOC: {len(lesson_titles)} lessons found")

    # Create lesson rows
    for n in range(1, expected_lessons + 1):
        title = lesson_titles.get(n, f'Bài {n}')
        append_row(ws_lesson, {
            'lesson_code': f'{level}-L{n:02d}',
            'unit_code': unit_code,
            'source_book_code': book_code,
            'source_lesson_ref': f'Bài {n}',
            'title': f'{level} Bài {n}: {title}',
            'order_index': n,
            'estimated_minutes': 45,
            'lesson_type': 'CURRICULUM',
            'primary_skill': 'VOCABULARY',
            'knowledge_tag_code': f'JLPT_{level}_LESSON_{n:02d}',
            'is_review': 0,
            'status': 'DRAFT',
        })

    blocks = list(iter_blocks(doc))

    # Find content start (after last TOC tab-line)
    toc_end = 0
    for i, block in enumerate(blocks):
        if isinstance(block, Paragraph):
            text = clean(block.text)
            if re.search(r'\t\d+\s*$', text):
                toc_end = i
    content_blocks = blocks[toc_end + 1:]

    # Find section event positions (VOCAB sections as lesson boundaries)
    vocab_events = []
    for i, block in enumerate(content_blocks):
        if isinstance(block, Paragraph):
            text = clean(block.text)
            if detect_section(text) == 'VOCAB':
                vocab_events.append(i)

    grammar_events = []
    for i, block in enumerate(content_blocks):
        if isinstance(block, Paragraph):
            text = clean(block.text)
            if detect_section(text) == 'GRAMMAR':
                grammar_events.append(i)
        elif isinstance(block, Table):
            # Grammar table: has ①-⑩ in it
            for row in block.rows:
                cells = ' '.join(clean(c.text) for c in row.cells)
                if re.search(r'[①-⑩]', cells):
                    grammar_events.append(i)
                    break

    print(f"  Vocab events: {len(vocab_events)}, Grammar events: {len(grammar_events)}")

    # Build lesson boundaries:
    # Use vocab events if enough (≥ expected_lessons), else grammar events
    if len(vocab_events) >= expected_lessons:
        boundaries = vocab_events[:expected_lessons]
        print(f"  Using {len(boundaries)} vocab events as lesson boundaries")
    elif len(grammar_events) >= expected_lessons:
        boundaries = grammar_events[:expected_lessons]
        print(f"  Using {len(grammar_events)} grammar events as lesson boundaries")
    else:
        # Use whatever we have, fill gaps with even splits
        events = sorted(set(vocab_events + grammar_events))
        boundaries = events[:expected_lessons]
        while len(boundaries) < expected_lessons:
            boundaries.append(len(content_blocks) - 1)
        print(f"  WARNING: Using mixed events ({len(events)} total)")

    # Build block ranges per lesson
    lesson_ranges = []
    for i, start in enumerate(boundaries):
        end = boundaries[i + 1] if i + 1 < len(boundaries) else len(content_blocks)
        lesson_ranges.append((i + 1, start, end))

    # Parse vocab and grammar per lesson
    vocab_stats = {}
    grammar_stats = {}

    for (lesson_num, start, end) in lesson_ranges:
        lesson_blocks = content_blocks[start:end]
        lesson_code = f'{level}-L{lesson_num:02d}'

        # Determine which sections are in this range
        vocab_start = None
        grammar_start = None
        current_sec = None
        sec_ranges = {'VOCAB': [], 'GRAMMAR': []}

        for i, block in enumerate(lesson_blocks):
            if isinstance(block, Paragraph):
                text = clean(block.text)
                sec = detect_section(text)
                if sec in ('VOCAB', 'GRAMMAR', 'DIALOGUE', 'EXERCISE', 'KANJI'):
                    if current_sec in sec_ranges:
                        sec_ranges[current_sec].append((sec_start, i))
                    current_sec = sec
                    sec_start = i + 1
        if current_sec in sec_ranges:
            sec_ranges[current_sec].append((sec_start, len(lesson_blocks)))

        # Parse vocab from vocab sub-blocks
        vocab_results = []
        for (vs, ve) in sec_ranges['VOCAB']:
            sub = lesson_blocks[vs:ve]
            if level == 'N5':
                vocab_results.extend(parse_n5_vocab_blocks(sub, lesson_num, book_code))
            else:
                vocab_results.extend(parse_n4_vocab_blocks(sub))

        # If no vocab from section-scoped, try the whole lesson range
        if not vocab_results:
            if level == 'N5':
                vocab_results = parse_n5_vocab_blocks(lesson_blocks, lesson_num, book_code)
            else:
                vocab_results = parse_n4_vocab_blocks(lesson_blocks)

        # Deduplicate
        seen_expr = set()
        for (expr, reading, meaning) in vocab_results:
            if expr in seen_expr or not expr or not meaning:
                continue
            seen_expr.add(expr)
            v_idx = len(seen_expr)
            append_row(ws_vocab, {
                'membership_code': f'{level}-L{lesson_num:02d}-V{v_idx:03d}',
                'lesson_code': lesson_code,
                'source_book_code': book_code,
                'source_lesson_ref': f'Bài {lesson_num}',
                'expression': expr,
                'reading': reading,  # May be empty → unresolved
                'meaning_vi': meaning,
                'order_index': v_idx,
                'is_core': 1,
                'canonical_lookup_status': 'NEEDS_LOOKUP',
            })
        vocab_stats[lesson_num] = len(seen_expr)

        # Parse grammar from grammar sub-blocks
        grammar_results = []
        for (gs, ge) in sec_ranges['GRAMMAR']:
            sub = lesson_blocks[gs:ge]
            grammar_results.extend(parse_n5_grammar_blocks(sub, lesson_num, book_code))

        # Also scan for grammar tables anywhere in lesson range
        for block in lesson_blocks:
            if isinstance(block, Table):
                for row in block.rows:
                    cells_text = ' '.join(clean(c.text) for c in row.cells)
                    if re.search(r'[①-⑩]', cells_text):
                        # This is a grammar table
                        for row2 in block.rows:
                            cells2 = [clean(c.text) for c in row2.cells]
                            full = ' '.join([c for c in cells2 if c])
                            full = re.sub(r'\s+', ' ', full).strip()
                            if re.match(r'^[①-⑩]', full) and is_japanese(full):
                                pattern = re.sub(r'^[①-⑩]\s*', '', full).strip()
                                if len(pattern) > 2 and (pattern, '') not in grammar_results:
                                    grammar_results.append((pattern, ''))
                        break

        seen_g = set()
        g_idx = 0
        for (pattern, meaning) in grammar_results:
            if pattern in seen_g or not pattern:
                continue
            # Filter out OCR garbage
            if len(pattern) < 3:
                continue
            # Only include if has real Japanese grammar markers
            if not any(m in pattern for m in ['は', 'が', 'を', 'に', 'で', 'て', 'ます', 'です', 'ない', 'た', 'V', 'N']):
                continue
            seen_g.add(pattern)
            g_idx += 1
            append_row(ws_grammar, {
                'grammar_code': f'{level}-L{lesson_num:02d}-G{g_idx:03d}',
                'lesson_code': lesson_code,
                'source_book_code': book_code,
                'source_lesson_ref': f'Bài {lesson_num}',
                'pattern': pattern,
                'meaning_vi': meaning if meaning else '',  # Leave blank if no real meaning
                'order_index': g_idx,
                'canonical_lookup_status': 'NEEDS_LOOKUP',
            })
        grammar_stats[lesson_num] = g_idx

    return vocab_stats, grammar_stats, {}


# ── Kanji book parser ─────────────────────────────────────────────────────────
# Structure (all paragraphs, no tables):
# "001" → number marker
# "力" → the kanji character
# "LỰC" → han viet reading (uppercase Vietnamese)
# "力" → again (for reading context)
# "動物" → compound word #1 expression
# "corrupt_kana" → compound word #1 reading (garbled OCR)
# "động vật" → compound word #1 meaning
# "..." → more compounds follow same pattern

def parse_kanji_book(doc_path):
    try:
        doc = docx.Document(doc_path)
    except Exception as e:
        print(f"ERROR: {e}")
        return

    ws_kanji = wb['LESSON_KANJI']
    ws_vocab = wb['LESSON_VOCAB']
    ws_compound = wb['KANJI_COMPOUND']

    blocks = list(iter_blocks(doc))
    paragraphs = []
    for block in blocks:
        if isinstance(block, Paragraph):
            text = clean(block.text)
            if text and text not in ['.', '·', '#', ':', '-']:
                paragraphs.append(text)

    kanji_count = 0
    compound_total = 0
    unresolved_kanji = []
    kanji_entries = []  # list of {char, compounds: [(expr, reading, meaning)]}

    i = 0
    while i < len(paragraphs):
        p = paragraphs[i]

        # Detect kanji entry: a number marker like "001", "002"
        if re.match(r'^\d{3}$', p):
            kanji_num = int(p)
            # Next: kanji character
            i += 1
            if i >= len(paragraphs):
                break
            char_line = paragraphs[i]

            # Find the actual single kanji character
            char = None
            for ch in char_line:
                if '\u4e00' <= ch <= '\u9faf':
                    char = ch
                    break

            if not char:
                i += 1
                continue

            kanji_count += 1
            lesson_num = (kanji_count - 1) // 9 + 1
            if lesson_num > 22:
                lesson_num = 22
            l_code = f"N4-L{lesson_num:02d}"

            # Next few paragraphs: han_viet, reading, then compounds
            i += 1
            session = ((kanji_count - 1) // 10) + 1
            k_code = f'N4-KANJI-{kanji_count:03d}'
            append_row(ws_kanji, {
                'membership_code': k_code,
                'lesson_code': l_code,
                'source_book_code': 'N4-KANJI',
                'source_session_ref': f'Buổi {session}',
                'character': char,
                'order_index': kanji_count,
                'canonical_lookup_status': 'NEEDS_LOOKUP',
            })

            # Look for compounds: after the kanji definition, scan for compound+meaning pairs
            # Pattern: expression line (contains kanji) → kana line → meaning line
            # Stop at next number marker or end
            compounds_found = []
            j = i
            compound_expr = None
            compound_kana = None

            while j < len(paragraphs):
                pj = paragraphs[j]
                # Stop at next entry
                if re.match(r'^\d{3}$', pj):
                    break
                # Stop at session marker
                if re.match(r'^Buổi\s+\d+$', pj):
                    break

                # Compound expression: contains kanji (current char), length 2-6
                if has_kanji(pj) and char in pj and 1 < len(pj) <= 6:
                    compound_expr = pj
                    compound_kana = None
                # Kana reading for compound (often OCR-corrupted)
                elif compound_expr and (is_pure_kana(pj) or len(pj) < 15 and has_hiragana(pj)):
                    compound_kana = pj
                # Vietnamese meaning
                elif compound_expr and is_vietnamese(pj) and not is_japanese(pj):
                    meaning = pj
                    try:
                        pos = list(compound_expr).index(char) + 1
                    except ValueError:
                        pos = 1
                    compounds_found.append((compound_expr, compound_kana or compound_expr, meaning, pos))
                    compound_expr = None
                    compound_kana = None

                j += 1

            if compounds_found:
                for (expr, reading, meaning, pos) in compounds_found[:3]:  # max 3 compounds per kanji
                    compound_total += 1
                    v_code = f"N4-L{lesson_num:02d}-KC{compound_total:03d}"
                    append_row(ws_vocab, {
                        'membership_code': v_code,
                        'lesson_code': l_code,
                        'source_book_code': 'N4-KANJI',
                        'source_lesson_ref': f'Buổi {session}',
                        'expression': expr,
                        'reading': reading,
                        'meaning_vi': meaning,
                        'order_index': 9000 + compound_total,
                        'is_core': 0,
                        'canonical_lookup_status': 'NEEDS_LOOKUP',
                    })
                    c_code = f"N4-KC-LINK-{compound_total:03d}"
                    append_row(ws_compound, {
                        'link_code': c_code,
                        'kanji_membership_code': k_code,
                        'vocabulary_membership_code': v_code,
                        'character': char,
                        'vocabulary_expression': expr,
                        'vocabulary_reading': reading,
                        'character_position': pos,
                    })
            else:
                # No compound found from book — record as unresolved (NO fake compound!)
                unresolved_kanji.append({
                    'kanji_count': kanji_count,
                    'char': char,
                    'lesson': l_code,
                    'k_code': k_code,
                })

            i = j
            continue

        i += 1

    print(f"  Kanji: {kanji_count}, Compounds: {compound_total}, Unresolved: {len(unresolved_kanji)}")
    return unresolved_kanji


# ── Run ────────────────────────────────────────────────────────────────────────

print("\nParsing N5...")
n5_vocab, n5_grammar, _ = parse_main_book(
    r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx",
    'N5', 'N5-CORE', 'N5-U01', 25
)

print("\nParsing N4...")
n4_vocab, n4_grammar, _ = parse_main_book(
    r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx",
    'N4', 'N4-CORE', 'N4-U01', 22
)

print("\nParsing Kanji book...")
unresolved_kanji = parse_kanji_book(
    r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\Chữ Hán N4 tài liệu học viên (Cấp tốc).docx"
)

wb.save(DST_EXCEL)
print(f"\nSaved to {DST_EXCEL}")

# ── Report ─────────────────────────────────────────────────────────────────────
report_lines = []
report_lines.append("=== EXTRACTION REPORT v4 ===\n")

report_lines.append("N5 Per-Lesson Distribution:")
for i in range(1, 26):
    v = n5_vocab.get(i, 0)
    g = n5_grammar.get(i, 0)
    flag = " ← EMPTY" if v == 0 else (" ← LOW" if v < 5 else "")
    report_lines.append(f"  N5-L{i:02d}: V={v:3d} G={g:3d}{flag}")

report_lines.append("\nN4 Per-Lesson Distribution:")
for i in range(1, 23):
    v = n4_vocab.get(i, 0)
    g = n4_grammar.get(i, 0)
    flag = " ← EMPTY" if v == 0 else (" ← LOW" if v < 3 else "")
    report_lines.append(f"  N4-L{i:02d}: V={v:3d} G={g:3d}{flag}")

if unresolved_kanji:
    report_lines.append(f"\nUnresolved Kanji (no compound found in book): {len(unresolved_kanji)}")
    for u in unresolved_kanji[:20]:
        report_lines.append(f"  {u['k_code']}: {u['char']} @ {u['lesson']}")
    if len(unresolved_kanji) > 20:
        report_lines.append(f"  ... and {len(unresolved_kanji)-20} more")

# Check vocab with empty reading
ws_v = wb['LESSON_VOCAB']
expr_col = get_col(ws_v, 'expression')
read_col = get_col(ws_v, 'reading')
lc_col = get_col(ws_v, 'lesson_code')
empty_reading = 0
reading_eq_expr = 0
for r in range(5, ws_v.max_row + 1):
    expr = str(ws_v.cell(r, expr_col).value or '')
    reading = str(ws_v.cell(r, read_col).value or '')
    if has_kanji(expr):
        if not reading or reading.strip() == '':
            empty_reading += 1
        elif reading == expr:
            reading_eq_expr += 1

report_lines.append(f"\nVocab reading audit:")
report_lines.append(f"  Has kanji + empty reading: {empty_reading} rows (unresolved, acceptable)")
report_lines.append(f"  Has kanji + reading==expression: {reading_eq_expr} rows (problematic)")

# Grammar quality check
ws_g = wb['LESSON_GRAMMAR']
pat_col = get_col(ws_g, 'pattern')
mv_col = get_col(ws_g, 'meaning_vi')
empty_meaning = 0
for r in range(5, ws_g.max_row + 1):
    mv = str(ws_g.cell(r, mv_col).value or '').strip()
    if not mv:
        empty_meaning += 1

report_lines.append(f"\nGrammar quality:")
report_lines.append(f"  Total grammar rows: {ws_g.max_row - 4}")
report_lines.append(f"  Rows with empty meaning_vi: {empty_meaning} (unresolved, left blank)")

# Sheet counts
report_lines.append("\nFinal row counts:")
for s in ['BOOK_CATALOG', 'LP_TEXTBOOK', 'LP_UNIT', 'LP_LESSON',
          'LESSON_VOCAB', 'LESSON_GRAMMAR', 'LESSON_KANJI', 'KANJI_COMPOUND']:
    ws = wb[s]
    report_lines.append(f"  {s}: {ws.max_row - 4} rows")

report_text = '\n'.join(report_lines)
print("\n" + report_text)

with open(REPORT_PATH, 'w', encoding='utf-8') as f:
    f.write(report_text)
print(f"\nReport saved to {REPORT_PATH}")
