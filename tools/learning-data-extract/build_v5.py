"""
PARSER v5 - Production-ready extraction
- N5 lesson boundaries: Heading 3 styles with numbers
- N4 lesson boundaries: 'ことば' or 'từ vựng' paragraphs
- N5 grammar: Heuristic based on Vて, Vます, N1, N2, ～
- N4 vocab: Robust multi-line buffering
- Kanji compound: Only real compounds, skip placeholders, leave missing kanji unresolved
"""

import shutil
import re
import sys
import unicodedata
from openpyxl import load_workbook
import docx
from docx.oxml.table import CT_Tbl
from docx.oxml.text.paragraph import CT_P
from docx.table import _Cell, Table
from docx.text.paragraph import Paragraph

sys.stdout.reconfigure(encoding='utf-8')

SRC_EXCEL = r"C:\Users\LAPTOP LENOVO\Downloads\jlas-learning-path-n5-n4-data-template.xlsx"
DST_EXCEL = r"D:\FALL26_FPT\SEP490\outputs\01a0ed4f-adc7-75e0-bf3a-f82644d49c20\jlas-learning-path-n5-n4-data-template.xlsx"
REPORT_PATH = r"D:\FALL26_FPT\SEP490\outputs\01a0ed4f-adc7-75e0-bf3a-f82644d49c20\extraction_report_v5.txt"

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

# ── Seed catalogs ──
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


# ── Utilities ──
def clean(t):
    t = str(t or '').replace('\u3000', ' ')
    t = re.sub(r'\s{2,}', ' ', t)
    return t.strip()

def has_kanji(t): return any('\u4e00' <= c <= '\u9faf' for c in t)
def has_hiragana(t): return any('\u3041' <= c <= '\u309f' for c in t)
def has_katakana(t): return any('\u30a0' <= c <= '\u30ff' for c in t)
def is_japanese(t): return has_kanji(t) or has_hiragana(t) or has_katakana(t)
VI_CHARS = set('àáâãèéêìíòóôõùúýăđơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ')
def is_vietnamese(t): return any(c in VI_CHARS for c in t.lower())
def is_pure_kana(t):
    t = t.strip()
    if not t: return False
    return all((has_hiragana(c) or has_katakana(c) or c in '（）()ー・ ') for c in t)

def iter_blocks(doc):
    for child in doc.element.body.iterchildren():
        if isinstance(child, CT_P):
            yield Paragraph(child, doc)
        elif isinstance(child, CT_Tbl):
            yield Table(child, doc)


# ── Parser Logic ──

def parse_n5(doc_path):
    print("Parsing N5...")
    doc = docx.Document(doc_path)
    ws_lesson = wb['LP_LESSON']
    ws_vocab = wb['LESSON_VOCAB']
    ws_grammar = wb['LESSON_GRAMMAR']
    
    # Extract TOC titles
    titles = {}
    toc_end = 0
    blocks = []
    for i, block in enumerate(iter_blocks(doc)):
        if isinstance(block, Paragraph):
            text = clean(block.text)
            blocks.append((text, block.style.name if block.style else ""))
            m = re.match(r'(?:BÀI|Bài)\s+(\d+)[:：]?\s*(.*?)\s*\t\d+', text)
            if m: titles[int(m.group(1))] = m.group(2).strip()
            if re.search(r'\t\d+\s*$', text): toc_end = i
        else:
            # For tables, we'll extract cells as a single string
            cells = []
            for row in block.rows:
                cells.extend([clean(c.text) for c in row.cells])
            blocks.append((" ".join(cells), "TABLE"))
            
    # Find lesson boundaries using Heading 3
    lesson_starts = {1: toc_end + 1}
    for i in range(toc_end + 1, len(blocks)):
        text, style = blocks[i]
        if 'Heading 3' in style and re.match(r'^\d+$', text):
            num = int(text)
            if num not in lesson_starts:
                lesson_starts[num] = i
    
    boundaries = []
    for n in range(1, 26):
        start = lesson_starts.get(n, lesson_starts.get(n-1, toc_end+1))
        boundaries.append((n, start))
        
    v_stats, g_stats = {}, {}
    for idx, (n, start) in enumerate(boundaries):
        end = boundaries[idx+1][1] if idx+1 < len(boundaries) else len(blocks)
        l_code = f'N5-L{n:02d}'
        append_row(ws_lesson, {
            'lesson_code': l_code,
            'unit_code': 'N5-U01',
            'source_book_code': 'N5-CORE',
            'source_lesson_ref': f'Bài {n}',
            'title': f'N5 Bài {n}: {titles.get(n, f"Bài {n}")}',
            'order_index': n,
            'estimated_minutes': 45,
            'lesson_type': 'CURRICULUM',
            'primary_skill': 'VOCABULARY',
            'knowledge_tag_code': f'JLPT_N5_LESSON_{n:02d}',
            'status': 'DRAFT',
        })
        
        # Parse content
        v_count = 0
        g_count = 0
        kana_queue = []
        for i in range(start, end):
            text, style = blocks[i]
            
            # Vocab
            if is_pure_kana(text) and len(text) > 2 and '\t' not in text and style != "TABLE":
                tokens = re.findall(r'[ぁ-ん]+(?:[ぁ-ん]*[ー])?|[ァ-ン]+(?:[ァ-ン]*[ー])?|[A-Za-z]+|（[^）]+）', text)
                kana_queue.extend(tokens if tokens else [text])
                continue
                
            if '\t' in text and not style == "TABLE":
                parts = text.split('\t')
                first, last = parts[0].strip(), parts[-1].strip()
                if is_japanese(first) and not is_japanese(last) and len(last) > 0:
                    reading = kana_queue.pop(0) if kana_queue else (first if is_pure_kana(first) else '')
                    if reading == first and has_kanji(first): reading = '' # Fix rule
                    v_count += 1
                    append_row(ws_vocab, {
                        'membership_code': f'{l_code}-V{v_count:03d}',
                        'lesson_code': l_code, 'source_book_code': 'N5-CORE', 'source_lesson_ref': f'Bài {n}',
                        'expression': first, 'reading': reading, 'meaning_vi': last,
                        'order_index': v_count, 'is_core': 1, 'canonical_lookup_status': 'NEEDS_LOOKUP'
                    })
                    
            if style == "TABLE" and "ひらがな" not in text and "かんじ" not in text:
                # Vocab or Grammar table
                # Rough parse for grammar table
                if re.search(r'[①-⑩]', text):
                    # It's a grammar table
                    parts = re.split(r'([①-⑩])', text)
                    for pt in parts:
                        if is_japanese(pt) and len(pt) > 5:
                            pat = re.sub(r'^[①-⑩]\s*', '', pt).strip()
                            if not pat: continue
                            g_count += 1
                            append_row(ws_grammar, {
                                'grammar_code': f'{l_code}-G{g_count:03d}',
                                'lesson_code': l_code, 'source_book_code': 'N5-CORE', 'source_lesson_ref': f'Bài {n}',
                                'pattern': pat, 'meaning_vi': '-', 'order_index': g_count, 'canonical_lookup_status': 'NEEDS_LOOKUP'
                            })
                else:
                    # Vocab table is parsed by generic split
                    pass
            
            # Grammar heuristics
            if not style == "TABLE" and len(text) < 100:
                # Numbered items in N5 that are not exercise
                if re.match(r'^(?:[①-⑩⑪-⑳]|[0-9]+[\.．])\s*(.{5,})', text) and "1. " not in text:
                    pat = re.sub(r'^(?:[①-⑩⑪-⑳]|[0-9]+[\.．])\s*', '', text).strip()
                    if any(m in pat for m in ['Vて', 'Vます', 'N1', 'N2', '～']) and is_japanese(pat):
                        mean = '-'
                        if is_vietnamese(pat):
                            for idx, c in enumerate(pat):
                                if c in VI_CHARS or (c.isalpha() and not is_japanese(c)):
                                    mean = pat[idx:].strip()
                                    pat = pat[:idx].strip()
                                    break
                        if not pat: continue
                        g_count += 1
                        append_row(ws_grammar, {
                            'grammar_code': f'{l_code}-G{g_count:03d}',
                            'lesson_code': l_code, 'source_book_code': 'N5-CORE', 'source_lesson_ref': f'Bài {n}',
                            'pattern': pat, 'meaning_vi': mean, 'order_index': g_count, 'canonical_lookup_status': 'NEEDS_LOOKUP'
                        })
                # Free text patterns
                elif any(m in text for m in ['Vて', 'Vます', 'N1', 'N2', '～']) and is_japanese(text):
                    pat = text.strip()
                    mean = '-'
                    if is_vietnamese(pat):
                        for idx, c in enumerate(pat):
                            if c in VI_CHARS or (c.isalpha() and not is_japanese(c)):
                                mean = pat[idx:].strip()
                                pat = pat[:idx].strip()
                                break
                    if len(pat) > 2:
                        g_count += 1
                        append_row(ws_grammar, {
                            'grammar_code': f'{l_code}-G{g_count:03d}',
                            'lesson_code': l_code, 'source_book_code': 'N5-CORE', 'source_lesson_ref': f'Bài {n}',
                            'pattern': pat, 'meaning_vi': mean, 'order_index': g_count, 'canonical_lookup_status': 'NEEDS_LOOKUP'
                        })
                    
        v_stats[n] = v_count
        g_stats[n] = g_count
    return v_stats, g_stats


def parse_n4(doc_path):
    print("Parsing N4...")
    doc = docx.Document(doc_path)
    ws_lesson = wb['LP_LESSON']
    ws_vocab = wb['LESSON_VOCAB']
    ws_grammar = wb['LESSON_GRAMMAR']
    
    titles = {}
    toc_end = 0
    blocks = []
    for i, block in enumerate(iter_blocks(doc)):
        if isinstance(block, Paragraph):
            text = clean(block.text)
            blocks.append((text, block.style.name if block.style else "", "P"))
            m = re.match(r'(?:BÀI|Bài)\s+(\d+)[:：]?\s*(.*?)\s*\t\d+', text)
            if m: titles[int(m.group(1))] = m.group(2).strip()
            if re.search(r'\t\d+\s*$', text): toc_end = i
        else:
            blocks.append((block, "TABLE", "T"))
            
    # N4 lesson boundaries by 'ことば'
    lesson_starts = []
    for i in range(toc_end + 1, len(blocks)):
        text, _, btype = blocks[i]
        if btype == "P" and ('ことば' in text.lower() or text.lower() == 'từ vựng' or text.lower() == 'từ vựng ／言葉'):
            lesson_starts.append(i)
            
    print(f"  Found {len(lesson_starts)} 'ことば' boundaries in N4.")
    if len(lesson_starts) < 22:
        # Fallback
        while len(lesson_starts) < 22: lesson_starts.append(len(blocks)-1)
    
    v_stats, g_stats = {}, {}
    for n in range(1, 23):
        start = lesson_starts[n-1]
        end = lesson_starts[n] if n < len(lesson_starts) else len(blocks)
        l_code = f'N4-L{n:02d}'
        
        append_row(ws_lesson, {
            'lesson_code': l_code,
            'unit_code': 'N4-U01',
            'source_book_code': 'N4-CORE',
            'source_lesson_ref': f'Bài {n}',
            'title': f'N4 Bài {n}: {titles.get(n, f"Bài {n}")}',
            'order_index': n,
            'estimated_minutes': 45,
            'lesson_type': 'CURRICULUM',
            'primary_skill': 'VOCABULARY',
            'knowledge_tag_code': f'JLPT_N4_LESSON_{n:02d}',
            'status': 'DRAFT',
        })
        
        v_count, g_count = 0, 0
        vocab_buffer = [] # [expr, reading]
        
        for i in range(start, end):
            b_data, style, btype = blocks[i]
            
            if btype == "P":
                text = b_data
                if not text: continue
                if 'ことば' in text or 'Ngữ pháp' in text or 'Luyện tập' in text: continue
                
                # Grammar heuristics (starts with ①-⑩ or has N1/N2 etc)
                if re.match(r'^[①-⑩⑪-⑳]\s+', text) or ('N1' in text and 'N2' in text):
                    pat = re.sub(r'^[①-⑩⑪-⑳]\s*', '', text).strip()
                    mean = '-'
                    if is_vietnamese(pat):
                        for idx, c in enumerate(pat):
                            if c in VI_CHARS or (c.isalpha() and not is_japanese(c)):
                                mean = pat[idx:].strip()
                                pat = pat[:idx].strip()
                                break
                    g_count += 1
                    append_row(ws_grammar, {
                        'grammar_code': f'{l_code}-G{g_count:03d}', 'lesson_code': l_code,
                        'source_book_code': 'N4-CORE', 'source_lesson_ref': f'Bài {n}',
                        'pattern': pat, 'meaning_vi': mean, 'order_index': g_count, 'canonical_lookup_status': 'NEEDS_LOOKUP'
                    })
                    continue

                # Inline space vocab "気分がいい cảm thấy..."
                if has_kanji(text) and is_vietnamese(text) and '\t' not in text:
                    vi_pos = -1
                    for idx, ch in enumerate(text):
                        if ch in VI_CHARS or (not is_japanese(ch) and ch.isalpha() and idx > 0):
                            vi_pos = idx
                            break
                    if vi_pos > 2:
                        expr = text[:vi_pos].strip()
                        meaning = text[vi_pos:].strip()
                        expr = re.sub(r'\s+[IVX]+\s*$', '', expr).strip()
                        if expr and meaning:
                            v_count += 1
                            append_row(ws_vocab, {
                                'membership_code': f'{l_code}-V{v_count:03d}', 'lesson_code': l_code,
                                'source_book_code': 'N4-CORE', 'source_lesson_ref': f'Bài {n}',
                                'expression': expr, 'reading': '', 'meaning_vi': meaning, 'order_index': v_count, 'is_core': 1, 'canonical_lookup_status': 'NEEDS_LOOKUP'
                            })
                            continue
                
                # Tab separated vocab
                if '\t' in text and has_kanji(text):
                    parts = text.split('\t')
                    expr = clean(parts[0])
                    rest = clean(parts[-1])
                    rest = re.sub(r'^[IVX]+\s+', '', rest).strip()
                    if is_vietnamese(rest) and not is_japanese(rest.replace('[', '').replace(']', '')):
                        rest = re.sub(r'^\[[^\]]+\]\s*', '', rest).strip()
                        v_count += 1
                        append_row(ws_vocab, {
                            'membership_code': f'{l_code}-V{v_count:03d}', 'lesson_code': l_code,
                            'source_book_code': 'N4-CORE', 'source_lesson_ref': f'Bài {n}',
                            'expression': expr, 'reading': '', 'meaning_vi': rest, 'order_index': v_count, 'is_core': 1, 'canonical_lookup_status': 'NEEDS_LOOKUP'
                        })
                        continue
                
                # Multi-line vocab: buffering Japanese lines until a Vietnamese line
                if is_japanese(text) and not is_vietnamese(text) and len(text) < 30:
                    vocab_buffer.append(text)
                elif is_vietnamese(text) and vocab_buffer:
                    expr = vocab_buffer[-1]
                    reading = vocab_buffer[0] if len(vocab_buffer) > 1 else (expr if is_pure_kana(expr) else '')
                    if reading == expr and has_kanji(expr): reading = ''
                    v_count += 1
                    append_row(ws_vocab, {
                        'membership_code': f'{l_code}-V{v_count:03d}', 'lesson_code': l_code,
                        'source_book_code': 'N4-CORE', 'source_lesson_ref': f'Bài {n}',
                        'expression': expr, 'reading': reading, 'meaning_vi': text, 'order_index': v_count, 'is_core': 1, 'canonical_lookup_status': 'NEEDS_LOOKUP'
                    })
                    vocab_buffer.clear()
                    
            elif btype == "TABLE":
                # Vocab table
                for row in b_data.rows:
                    cells = [clean(c.text) for c in row.cells]
                    unique = list(dict.fromkeys([c for c in cells if c]))
                    if not unique: continue
                    if any(h in unique[0] for h in ['ひらがな', 'かんじ', 'ベトナムご', 'TỪ VỰNG']): continue
                    if len(unique) >= 3:
                        kana, kanji, meaning = unique[0], unique[1], unique[2]
                        if re.search(r'ます$|ました$|ません$', kana) and re.search(r'ます$|ました$|ません$', kanji): continue
                        if is_japanese(kana) and not is_japanese(meaning) and len(meaning) > 1:
                            expr = kanji if has_kanji(kanji) else kana
                            reading = kana if is_pure_kana(kana) else ''
                            if reading == expr and has_kanji(expr): reading = ''
                            v_count += 1
                            append_row(ws_vocab, {
                                'membership_code': f'{l_code}-V{v_count:03d}', 'lesson_code': l_code,
                                'source_book_code': 'N4-CORE', 'source_lesson_ref': f'Bài {n}',
                                'expression': expr, 'reading': reading, 'meaning_vi': meaning, 'order_index': v_count, 'is_core': 1, 'canonical_lookup_status': 'NEEDS_LOOKUP'
                            })
                    elif len(unique) == 2:
                        expr, meaning = unique[0], unique[1]
                        if is_japanese(expr) and not is_japanese(meaning) and len(meaning) > 1:
                            reading = expr if is_pure_kana(expr) else ''
                            if reading == expr and has_kanji(expr): reading = ''
                            v_count += 1
                            append_row(ws_vocab, {
                                'membership_code': f'{l_code}-V{v_count:03d}', 'lesson_code': l_code,
                                'source_book_code': 'N4-CORE', 'source_lesson_ref': f'Bài {n}',
                                'expression': expr, 'reading': reading, 'meaning_vi': meaning, 'order_index': v_count, 'is_core': 1, 'canonical_lookup_status': 'NEEDS_LOOKUP'
                            })
        
        v_stats[n] = v_count
        g_stats[n] = g_count
    return v_stats, g_stats

def parse_kanji_book(doc_path):
    print("Parsing Kanji...")
    doc = docx.Document(doc_path)
    ws_kanji = wb['LESSON_KANJI']
    ws_vocab = wb['LESSON_VOCAB']
    ws_compound = wb['KANJI_COMPOUND']

    paragraphs = []
    for block in iter_blocks(doc):
        if isinstance(block, Paragraph):
            text = clean(block.text)
            if text and text not in ['.', '·', '#', ':', '-']:
                paragraphs.append(text)

    k_count, c_count, unresolved = 0, 0, 0
    i = 0
    while i < len(paragraphs):
        p = paragraphs[i]
        if re.match(r'^\d{3}$', p):
            kanji_num = int(p)
            i += 1
            if i >= len(paragraphs): break
            char_line = paragraphs[i]
            char = next((ch for ch in char_line if '\u4e00' <= ch <= '\u9faf'), None)
            if not char:
                i += 1; continue

            k_count += 1
            l_code = f"N4-L{min((k_count - 1) // 9 + 1, 22):02d}"
            k_code = f'N4-KANJI-{k_count:03d}'
            session = ((k_count - 1) // 10) + 1
            
            append_row(ws_kanji, {
                'membership_code': k_code, 'lesson_code': l_code,
                'source_book_code': 'N4-KANJI', 'source_session_ref': f'Buổi {session}',
                'character': char, 'order_index': k_count, 'canonical_lookup_status': 'NEEDS_LOOKUP'
            })

            i += 1
            compounds_found = []
            j = i
            c_expr, c_kana = None, None
            while j < len(paragraphs):
                pj = paragraphs[j]
                if re.match(r'^\d{3}$', pj) or re.match(r'^Buổi\s+\d+$', pj): break
                
                if has_kanji(pj) and char in pj and 1 < len(pj) <= 6:
                    c_expr, c_kana = pj, None
                elif c_expr and (is_pure_kana(pj) or len(pj) < 15 and has_hiragana(pj)):
                    c_kana = pj
                elif c_expr and is_vietnamese(pj) and not is_japanese(pj):
                    pos = list(c_expr).index(char) + 1 if char in c_expr else 1
                    compounds_found.append((c_expr, c_kana or '', pj, pos))
                    c_expr, c_kana = None, None
                j += 1

                if compounds_found:
                    for (expr, reading, meaning, pos) in compounds_found[:3]:
                        c_count += 1
                        v_code = f"N4-L{int(l_code[-2:]):02d}-KC{c_count:03d}"
                        if reading == expr and has_kanji(expr): reading = ''
                        if not reading: reading = '-'
                        append_row(ws_vocab, {
                            'membership_code': v_code, 'lesson_code': l_code,
                            'source_book_code': 'N4-KANJI', 'source_lesson_ref': f'Buổi {session}',
                            'expression': expr, 'reading': reading, 'meaning_vi': meaning,
                            'order_index': 9000 + c_count, 'is_core': 0, 'canonical_lookup_status': 'NEEDS_LOOKUP'
                        })
                        append_row(ws_compound, {
                            'link_code': f"N4-KC-LINK-{c_count:03d}", 'kanji_membership_code': k_code,
                            'vocabulary_membership_code': v_code, 'character': char,
                            'vocabulary_expression': expr, 'vocabulary_reading': reading, 'character_position': pos
                        })
            else:
                unresolved += 1
            i = j
            continue
        i += 1
    print(f"  Kanji: {k_count}, Compounds: {c_count}, Unresolved: {unresolved}")

# ── RUN ──
n5_v, n5_g = parse_n5(r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx")
n4_v, n4_g = parse_n4(r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx")
parse_kanji_book(r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\Chữ Hán N4 tài liệu học viên (Cấp tốc).docx")
wb.save(DST_EXCEL)

# ── REPORT ──
rep = ["=== EXTRACTION REPORT v5 ==="]
rep.append("\nN5 Lessons:")
for i in range(1, 26):
    rep.append(f"  L{i:02d}: V={n5_v.get(i,0):3d} G={n5_g.get(i,0):3d}")
rep.append("\nN4 Lessons:")
for i in range(1, 23):
    rep.append(f"  L{i:02d}: V={n4_v.get(i,0):3d} G={n4_g.get(i,0):3d}")

with open(REPORT_PATH, 'w', encoding='utf-8') as f:
    f.write('\n'.join(rep))
print("Done. Saved to", DST_EXCEL)
