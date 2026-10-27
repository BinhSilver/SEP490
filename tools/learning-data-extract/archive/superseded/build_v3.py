"""
FINAL PARSER v3 - Strategy: Use TOC to build a page-to-lesson map.
Then use section headings (Ngữ pháp, Từ vựng) within blocks as state markers.
Grammar table comes right after "bài kiểm tra" section (block 150+).
Each document block is associated to the lesson it belongs to based on the TOC page map.

Key insight: 
- N5/N4 lesson headings only appear once (in TOC). 
- Content does NOT have repeated BÀI N: headings.
- Instead, grammar tables appear sequentially, section headers (Ngữ pháp, Từ vựng...) appear 
  within the content area for each lesson.
- The FIRST grammar table after TOC belongs to Bài 1, the NEXT to Bài 2, etc.
- Vocab paragraphs appear after each grammar table.

New approach: process ALL blocks sequentially after TOC boundary.
Track sections using headings: each "Ngữ pháp" or "Từ vựng" header advances the lesson tracker.
The TOC tells us the ORDER: bài 1 vocab before bài 1 grammar before bài 2 vocab etc.
"""

import shutil
import re
import sys
from openpyxl import load_workbook
import docx
from docx.oxml.table import CT_Tbl
from docx.oxml.text.paragraph import CT_P
from docx.table import _Cell, Table
from docx.text.paragraph import Paragraph

sys.stdout.reconfigure(encoding='utf-8')

src_excel = r"C:\Users\LAPTOP LENOVO\Downloads\jlas-learning-path-n5-n4-data-template.xlsx"
dst_excel = r"D:\FALL26_FPT\SEP490\outputs\01a0ed4f-adc7-75e0-bf3a-f82644d49c20\jlas-learning-path-n5-n4-data-template.xlsx"

shutil.copy(src_excel, dst_excel)
wb = load_workbook(dst_excel)

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


def get_col_index(ws, header_name):
    for c in range(1, ws.max_column + 1):
        val = str(ws.cell(row=4, column=c).value).replace('*', '').strip()
        if val == header_name:
            return c
    return None


def append_dict_row(ws, data_dict):
    row_idx = max(ws.max_row + 1, 5)
    for k, v in data_dict.items():
        col_idx = get_col_index(ws, k)
        if col_idx:
            ws.cell(row=row_idx, column=col_idx).value = v


ws_books = wb['BOOK_CATALOG']
append_dict_row(ws_books, {'source_book_code': 'N5-CORE', 'jlpt_level': 'N5', 'source_role': 'CORE_CURRICULUM'})
append_dict_row(ws_books, {'source_book_code': 'N4-CORE', 'jlpt_level': 'N4', 'source_role': 'CORE_CURRICULUM'})
append_dict_row(ws_books, {'source_book_code': 'N4-KANJI', 'jlpt_level': 'N4', 'source_role': 'SUPPLEMENTARY'})

ws_textbook = wb['LP_TEXTBOOK']
append_dict_row(ws_textbook, {'textbook_code': 'N5-CORE-TB', 'source_book_code': 'N5-CORE', 'title': 'N5 Textbook', 'jlpt_level': 'N5', 'status': 'DRAFT', 'version': 1, 'is_learning_path_source': 1})
append_dict_row(ws_textbook, {'textbook_code': 'N4-CORE-TB', 'source_book_code': 'N4-CORE', 'title': 'N4 Textbook', 'jlpt_level': 'N4', 'status': 'DRAFT', 'version': 1, 'is_learning_path_source': 1})

ws_unit = wb['LP_UNIT']
append_dict_row(ws_unit, {'unit_code': 'N5-U01', 'textbook_code': 'N5-CORE-TB', 'title': 'N5 Bài 1-25', 'order_index': 1, 'source_book_code': 'N5-CORE'})
append_dict_row(ws_unit, {'unit_code': 'N4-U01', 'textbook_code': 'N4-CORE-TB', 'title': 'N4 Bài 1-22', 'order_index': 2, 'source_book_code': 'N4-CORE'})


def iter_blocks(doc):
    parent_elm = doc.element.body
    for child in parent_elm.iterchildren():
        if isinstance(child, CT_P):
            yield Paragraph(child, doc)
        elif isinstance(child, CT_Tbl):
            yield Table(child, doc)


def clean(t):
    t = str(t or '').replace('\u3000', ' ')
    t = re.sub(r'\s{2,}', ' ', t)
    return t.strip()


def has_cjk(text):
    return any('\u3000' <= c <= '\u9fff' or '\u4e00' <= c <= '\u9faf' for c in text)


def has_hiragana(text):
    return any('\u3041' <= c <= '\u309f' for c in text)


def has_katakana(text):
    return any('\u30a0' <= c <= '\u30ff' for c in text)


def is_japanese(text):
    return has_cjk(text) or has_hiragana(text) or has_katakana(text)


VI_CHARS = set('àáâãèéêìíòóôõùúýăđơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ')


def is_vietnamese(text):
    return any(c in VI_CHARS for c in text.lower())


def parse_vocab_table(table):
    results = []
    for row in table.rows:
        cells = [clean(cell.text) for cell in row.cells]
        seen = []
        for c in cells:
            if c and c not in seen:
                seen.append(c)
        if not seen:
            continue
        first = seen[0]
        if any(h in first for h in ['ひらがな', 'かんじ', 'ことば', 'ベトナムご', 'TỪ VỰNG', 'THAM KHẢO']):
            continue
        if len(seen) >= 3:
            kana, kanji, meaning = seen[0], seen[1], seen[2]
            if is_japanese(kana) and not is_japanese(meaning):
                expr = kanji if any('\u4e00' <= c <= '\u9faf' for c in kanji) else kana
                results.append((expr, kana, meaning))
        elif len(seen) == 2:
            expr, meaning = seen[0], seen[1]
            if is_japanese(expr) and not is_japanese(meaning):
                results.append((expr, expr, meaning))
    return results


def extract_toc_data(doc):
    """Extract lesson titles and section order from TOC."""
    lessons = {}  # lesson_num -> {'title': ..., 'sections': []}
    blocks = list(iter_blocks(doc))
    for block in blocks:
        if not isinstance(block, Paragraph):
            continue
        text = clean(block.text)
        if not text:
            continue
        # TOC lesson entry: "BÀI N: Title \t page"
        m = re.match(r'(?:BÀI|Bài)\s+(\d+)[:：]?\s*(.*?)\s*\t\d+', text)
        if m:
            num = int(m.group(1))
            title = m.group(2).strip()
            lessons[num] = title
    return lessons


def parse_main_book(doc_path, level, book_code, unit_code, expected_lessons):
    try:
        doc = docx.Document(doc_path)
    except Exception as e:
        print(f"ERROR: {e}")
        return

    ws_lesson = wb['LP_LESSON']
    ws_vocab = wb['LESSON_VOCAB']
    ws_grammar = wb['LESSON_GRAMMAR']
    ws_dialogue = wb['DIALOGUE']

    # Step 1: Extract lesson titles from TOC
    lesson_titles = extract_toc_data(doc)
    print(f"  TOC found {len(lesson_titles)} lesson titles")

    # Step 2: Create all lessons from TOC data
    for lesson_num in range(1, expected_lessons + 1):
        title = lesson_titles.get(lesson_num, f'Bài {lesson_num}')
        lesson_code = f'{level}-L{lesson_num:02d}'
        append_dict_row(ws_lesson, {
            'lesson_code': lesson_code,
            'unit_code': unit_code,
            'source_book_code': book_code,
            'source_lesson_ref': f'Bài {lesson_num}',
            'title': f'{level} Bài {lesson_num}: {title}',
            'order_index': lesson_num,
            'estimated_minutes': 45,
            'lesson_type': 'CURRICULUM',
            'primary_skill': 'VOCABULARY',
            'knowledge_tag_code': f'JLPT_{level}_LESSON_{lesson_num:02d}',
            'is_review': 0,
            'status': 'DRAFT',
        })

    # Step 3: Parse content blocks after TOC
    # TOC boundary: last block that's a TOC line with BÀI heading
    blocks = list(iter_blocks(doc))
    toc_end = 0
    for i, block in enumerate(blocks):
        if isinstance(block, Paragraph):
            text = clean(block.text)
            if re.search(r'\t\d+\s*$', text):
                toc_end = i

    content_blocks = blocks[toc_end + 1:]
    print(f"  Content starts at block {toc_end + 1}, total content blocks: {len(content_blocks)}")

    # State machine: track lesson by counting section transitions
    # Strategy: Each lesson has [vocab_section, grammar_section, dialogue_section, exercise_section]
    # Count how many "Ngữ pháp" sections we've seen to determine current lesson
    
    # First pass: find all section boundaries
    section_events = []  # (block_idx, type, block)
    VOCAB_PATTERNS = ['Từ vựng', 'Từ vfing', 'ことば', '言葉']
    GRAMMAR_PATTERNS = ['Ngữ pháp', '文法', 'ぶんぽう']
    DIALOGUE_PATTERNS = ['Hội thoại', '会話', 'かいわ']
    EXERCISE_PATTERNS = ['Luyện tập', '練習', 'れんしゅう']
    KANJI_PATTERNS = ['Chữ Hán', '漢字', 'かんじ']

    for i, block in enumerate(content_blocks):
        if isinstance(block, Paragraph):
            text = clean(block.text)
            if not text:
                continue
            for p in VOCAB_PATTERNS:
                if p in text and len(text) < 40:
                    section_events.append((i, 'VOCAB'))
                    break
            else:
                for p in GRAMMAR_PATTERNS:
                    if p in text and len(text) < 40:
                        section_events.append((i, 'GRAMMAR'))
                        break
                else:
                    for p in DIALOGUE_PATTERNS:
                        if p in text and len(text) < 40:
                            section_events.append((i, 'DIALOGUE'))
                            break
                    else:
                        for p in EXERCISE_PATTERNS:
                            if p in text and len(text) < 40:
                                section_events.append((i, 'EXERCISE'))
                                break

    print(f"  Section events: {len(section_events)}")
    
    # Each grammar section increments lesson number (grammar appears once per lesson)
    grammar_sections = [(i, t) for i, t in section_events if t == 'GRAMMAR']
    vocab_sections = [(i, t) for i, t in section_events if t == 'VOCAB']
    
    print(f"  Grammar sections: {len(grammar_sections)}, Vocab sections: {len(vocab_sections)}")
    
    # Map block ranges to lessons
    # Strategy: use grammar section as lesson boundary
    # Lesson N spans from its first section event to the start of lesson N+1's first section event
    
    # Build lesson boundaries using grammar section events as lesson markers
    lesson_boundaries = []  # (lesson_num, start_block_idx, end_block_idx)
    
    if len(grammar_sections) >= expected_lessons:
        for i, (block_idx, _) in enumerate(grammar_sections[:expected_lessons]):
            lesson_num = i + 1
            end_idx = grammar_sections[i + 1][0] if i + 1 < len(grammar_sections) else len(content_blocks)
            lesson_boundaries.append((lesson_num, block_idx, end_idx))
    elif len(vocab_sections) >= expected_lessons:
        for i, (block_idx, _) in enumerate(vocab_sections[:expected_lessons]):
            lesson_num = i + 1
            end_idx = vocab_sections[i + 1][0] if i + 1 < len(vocab_sections) else len(content_blocks)
            lesson_boundaries.append((lesson_num, block_idx, end_idx))
    else:
        # Fallback: split blocks evenly
        chunk = len(content_blocks) // expected_lessons
        for i in range(expected_lessons):
            lesson_boundaries.append((i + 1, i * chunk, (i + 1) * chunk))
        print(f"  WARNING: Using even split fallback")

    print(f"  Lesson boundaries: {len(lesson_boundaries)}")

    # Step 4: Extract vocab and grammar per lesson
    vocab_totals = {}
    grammar_totals = {}
    dialogue_totals = {}

    for (lesson_num, start_idx, end_idx) in lesson_boundaries:
        lesson_code = f'{level}-L{lesson_num:02d}'
        v_count = 0
        g_count = 0
        d_count = 0
        current_section = None

        for block in content_blocks[start_idx:end_idx]:
            if isinstance(block, Paragraph):
                text = clean(block.text)
                if not text:
                    continue
                
                # Section headers
                if any(p in text for p in VOCAB_PATTERNS) and len(text) < 40:
                    current_section = 'VOCAB'
                    continue
                if any(p in text for p in GRAMMAR_PATTERNS) and len(text) < 40:
                    current_section = 'GRAMMAR'
                    continue
                if any(p in text for p in DIALOGUE_PATTERNS) and len(text) < 40:
                    current_section = 'DIALOGUE'
                    continue
                if any(p in text for p in EXERCISE_PATTERNS + KANJI_PATTERNS) and len(text) < 40:
                    current_section = 'OTHER'
                    continue
                
                # Vocab: tab-separated line like "私\ttôi" or "kanji\tmeaning"
                if current_section == 'VOCAB' and '\t' in text:
                    parts = text.split('\t')
                    expr = clean(parts[0])
                    meaning = clean(parts[-1])
                    if expr and meaning and is_japanese(expr) and not is_japanese(meaning) and len(expr) < 30:
                        v_count += 1
                        append_dict_row(ws_vocab, {
                            'membership_code': f'{level}-L{lesson_num:02d}-V{v_count:03d}',
                            'lesson_code': lesson_code,
                            'source_book_code': book_code,
                            'source_lesson_ref': f'Bài {lesson_num}',
                            'expression': expr,
                            'reading': expr,
                            'meaning_vi': meaning,
                            'order_index': v_count,
                            'is_core': 1,
                            'canonical_lookup_status': 'NEEDS_LOOKUP',
                        })
                
                # Grammar: numbered patterns
                if current_section == 'GRAMMAR':
                    gm = re.match(r'^(?:[①-⑩]|[0-9]+[\.．])\s*(.{5,})', text)
                    if gm:
                        raw = gm.group(1).strip()
                        if is_japanese(raw):
                            # Try to split off Vietnamese meaning
                            vi_part = ''
                            parts_split = re.split(r'(?<=[。）】])\s+', raw)
                            if len(parts_split) > 1 and is_vietnamese(parts_split[-1]):
                                pattern = ' '.join(parts_split[:-1])
                                vi_part = parts_split[-1]
                            else:
                                pattern = raw
                            
                            g_count += 1
                            append_dict_row(ws_grammar, {
                                'grammar_code': f'{level}-L{lesson_num:02d}-G{g_count:03d}',
                                'lesson_code': lesson_code,
                                'source_book_code': book_code,
                                'source_lesson_ref': f'Bài {lesson_num}',
                                'pattern': pattern,
                                'meaning_vi': vi_part,
                                'order_index': g_count,
                                'canonical_lookup_status': 'NEEDS_LOOKUP',
                            })
                
                # Dialogue
                if current_section == 'DIALOGUE':
                    dm = re.match(r'^(.{1,15}?)\s*[:：]\s*(.{5,})', text)
                    if dm and is_japanese(dm.group(2)):
                        d_count += 1
                        append_dict_row(ws_dialogue, {
                            'dialogue_code': f'{level}-L{lesson_num:02d}-D{d_count:03d}',
                            'destination_scope': lesson_code,
                            'source_book_code': book_code,
                            'source_lesson_ref': f'Bài {lesson_num}',
                            'title': f'{level} L{lesson_num:02d} Kaiwa',
                            'turn_index': d_count,
                            'speaker': dm.group(1).strip(),
                            'text_ja': dm.group(2).strip(),
                            'translation_vi': '',
                            'register': 'POLITE',
                            'is_key_phrase': 0,
                        })

            elif isinstance(block, Table):
                # Vocab table
                if current_section == 'VOCAB':
                    for (expr, reading, meaning) in parse_vocab_table(block):
                        v_count += 1
                        append_dict_row(ws_vocab, {
                            'membership_code': f'{level}-L{lesson_num:02d}-V{v_count:03d}',
                            'lesson_code': lesson_code,
                            'source_book_code': book_code,
                            'source_lesson_ref': f'Bài {lesson_num}',
                            'expression': expr,
                            'reading': reading,
                            'meaning_vi': meaning,
                            'order_index': v_count,
                            'is_core': 1,
                            'canonical_lookup_status': 'NEEDS_LOOKUP',
                        })
                
                # Grammar table (common in N5)
                if current_section == 'GRAMMAR':
                    for row in block.rows:
                        cells = [clean(c.text) for c in row.cells]
                        unique = list(dict.fromkeys([c for c in cells if c]))
                        # Grammar table rows like ['① N1', 'は', 'N2 です。']
                        joined = ' '.join(unique)
                        if is_japanese(joined) and re.match(r'[①-⑩]', joined):
                            g_count += 1
                            append_dict_row(ws_grammar, {
                                'grammar_code': f'{level}-L{lesson_num:02d}-G{g_count:03d}',
                                'lesson_code': lesson_code,
                                'source_book_code': book_code,
                                'source_lesson_ref': f'Bài {lesson_num}',
                                'pattern': joined,
                                'meaning_vi': '',
                                'order_index': g_count,
                                'canonical_lookup_status': 'NEEDS_LOOKUP',
                            })

        vocab_totals[lesson_num] = v_count
        grammar_totals[lesson_num] = g_count
        dialogue_totals[lesson_num] = d_count

    total_v = sum(vocab_totals.values())
    total_g = sum(grammar_totals.values())
    total_d = sum(dialogue_totals.values())
    print(f"  Total vocab: {total_v}, grammar: {total_g}, dialogue: {total_d}")
    
    # Show per-lesson distribution
    for i in range(1, min(6, expected_lessons + 1)):
        print(f"  L{i:02d}: V={vocab_totals.get(i, 0)} G={grammar_totals.get(i, 0)} D={dialogue_totals.get(i, 0)}")


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
    kanji_count = 0
    compound_count = 0

    for block in blocks:
        if not isinstance(block, Table):
            continue
        rows = block.rows
        if not rows:
            continue
        
        for row in rows:
            cells = [clean(cell.text) for cell in row.cells]
            unique = list(dict.fromkeys([c for c in cells if c]))
            if not unique:
                continue
            first = unique[0]
            if first in ['漢字', 'Kanji', '音読み', '訓読み', 'ひらがな']:
                continue
            if len(first) <= 2 and any('\u4e00' <= c <= '\u9faf' for c in first):
                char = first[0]
                lesson_num = (kanji_count // 9) + 1
                if lesson_num > 22:
                    lesson_num = 22
                l_code = f"N4-L{lesson_num:02d}"

                kanji_count += 1
                k_code = f'N4-KANJI-{kanji_count:03d}'
                append_dict_row(ws_kanji, {
                    'membership_code': k_code,
                    'lesson_code': l_code,
                    'source_book_code': 'N4-KANJI',
                    'source_session_ref': f'Buổi {(kanji_count-1)//10 + 1}',
                    'character': char,
                    'order_index': kanji_count,
                    'canonical_lookup_status': 'NEEDS_LOOKUP',
                })

                # Find a compound word from remaining cells
                compound_found = False
                for cell_text in unique[1:]:
                    if any('\u4e00' <= c <= '\u9faf' for c in cell_text) and char in cell_text and 1 < len(cell_text) <= 6:
                        expr = cell_text
                        try:
                            pos = list(expr).index(char) + 1
                        except ValueError:
                            continue
                        meaning_cand = [uc for uc in unique if is_vietnamese(uc) and not is_japanese(uc)]
                        meaning = meaning_cand[0] if meaning_cand else f'Từ có {char}'
                        
                        compound_count += 1
                        v_code = f"N4-L{lesson_num:02d}-KC{compound_count:03d}"
                        append_dict_row(ws_vocab, {
                            'membership_code': v_code,
                            'lesson_code': l_code,
                            'source_book_code': 'N4-KANJI',
                            'source_lesson_ref': f'Buổi {(kanji_count-1)//10 + 1}',
                            'expression': expr,
                            'reading': expr,
                            'meaning_vi': meaning,
                            'order_index': 9000 + compound_count,
                            'is_core': 0,
                            'canonical_lookup_status': 'NEEDS_LOOKUP',
                        })
                        c_code = f"N4-KC-LINK-{compound_count:03d}"
                        append_dict_row(ws_compound, {
                            'link_code': c_code,
                            'kanji_membership_code': k_code,
                            'vocabulary_membership_code': v_code,
                            'character': char,
                            'vocabulary_expression': expr,
                            'vocabulary_reading': expr,
                            'character_position': pos,
                        })
                        compound_found = True
                        break

                if not compound_found:
                    # Fallback: kanji + さ (Japanese suffix, not Latin o)
                    expr = char + 'さ'
                    compound_count += 1
                    v_code = f"N4-L{lesson_num:02d}-KC{compound_count:03d}"
                    append_dict_row(ws_vocab, {
                        'membership_code': v_code,
                        'lesson_code': l_code,
                        'source_book_code': 'N4-KANJI',
                        'source_lesson_ref': f'Buổi {(kanji_count-1)//10 + 1}',
                        'expression': expr,
                        'reading': expr,
                        'meaning_vi': f'Từ có {char} (cần lookup)',
                        'order_index': 9000 + compound_count,
                        'is_core': 0,
                        'canonical_lookup_status': 'NEEDS_LOOKUP',
                    })
                    c_code = f"N4-KC-LINK-{compound_count:03d}"
                    append_dict_row(ws_compound, {
                        'link_code': c_code,
                        'kanji_membership_code': k_code,
                        'vocabulary_membership_code': v_code,
                        'character': char,
                        'vocabulary_expression': expr,
                        'vocabulary_reading': expr,
                        'character_position': 1,
                    })

    print(f"  Kanji: {kanji_count}, Compounds: {compound_count}")


print("\nParsing N5...")
parse_main_book(
    r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx",
    'N5', 'N5-CORE', 'N5-U01', expected_lessons=25
)

print("\nParsing N4...")
parse_main_book(
    r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx",
    'N4', 'N4-CORE', 'N4-U01', expected_lessons=22
)

print("\nParsing Kanji book...")
parse_kanji_book(
    r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\Chữ Hán N4 tài liệu học viên (Cấp tốc).docx"
)

wb.save(dst_excel)
print(f"\nSaved to {dst_excel}")

print("\nRow counts per sheet:")
for s in ['BOOK_CATALOG', 'LP_TEXTBOOK', 'LP_UNIT', 'LP_LESSON',
          'LESSON_VOCAB', 'LESSON_GRAMMAR', 'DIALOGUE', 'LESSON_KANJI', 'KANJI_COMPOUND']:
    ws = wb[s]
    count = ws.max_row - 4
    print(f"  {s}: {count}")
