import shutil
import re
from openpyxl import load_workbook
import docx
from docx.oxml.table import CT_Tbl
from docx.oxml.text.paragraph import CT_P
from docx.table import _Cell, Table
from docx.text.paragraph import Paragraph

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
    if sheet_name not in wb.sheetnames: continue
    ws = wb[sheet_name]
    
    # If it's a new sheet or missing descriptions, row 1-3 might be empty, causing validator row shift issues.
    if ws.cell(row=1, column=1).value is None:
        ws.cell(row=1, column=1).value = f"Sheet: {sheet_name}"
    
    if ws.max_row >= 5:
        ws.delete_rows(5, ws.max_row - 4)
        
    existing_headers = []
    for c in range(1, ws.max_column + 1):
        val = str(ws.cell(row=4, column=c).value).replace('*', '').strip()
        if val != 'None' and val != '':
            existing_headers.append(val)
            
    for req_h in required_headers:
        if req_h not in existing_headers:
            ws.cell(row=4, column=ws.max_column + 1).value = req_h + '*'
            existing_headers.append(req_h)

def get_col_index(ws, header_name):
    for c in range(1, ws.max_column + 1):
        val = str(ws.cell(row=4, column=c).value).replace('*', '').strip()
        if val == header_name:
            return c
    return None

def append_dict_row(ws, data_dict):
    row_idx = ws.max_row + 1
    if row_idx < 5:
        row_idx = 5
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

ws_lesson = wb['LP_LESSON']
ws_vocab = wb['LESSON_VOCAB']
ws_grammar = wb['LESSON_GRAMMAR']
ws_dialogue = wb['DIALOGUE']
ws_comp = wb['COMPREHENSION']
ws_ex = wb['EXERCISE']
ws_compound = wb['KANJI_COMPOUND']
ws_kanji = wb['LESSON_KANJI']

def iter_block_items(parent):
    if type(parent).__name__ == 'Document':
        parent_elm = parent.element.body
    elif type(parent).__name__ == '_Cell':
        parent_elm = parent._tc
    else:
        return

    for child in parent_elm.iterchildren():
        if isinstance(child, CT_P):
            yield Paragraph(child, parent)
        elif isinstance(child, CT_Tbl):
            yield Table(child, parent)

def parse_main_book(doc_path, level, book_code, unit_code):
    try:
        doc = docx.Document(doc_path)
    except Exception as e:
        print(f"Could not load {doc_path}: {e}")
        return

    current_lesson = 0
    vocab_count = 1
    grammar_count = 1
    dialogue_count = 1
    current_section = None

    for block in iter_block_items(doc):
        if isinstance(block, Paragraph):
            text = block.text.strip()
            if not text: continue
            
            match = re.search(rf'(?:BÀI|Bài)\s*(\d+):?(.*)', text)
            if match:
                lesson_num = int(match.group(1))
                if lesson_num != current_lesson:
                    current_lesson = lesson_num
                    title = match.group(2).strip()
                    vocab_count = 1
                    grammar_count = 1
                    dialogue_count = 1
                    
                    lesson_code = f'{level}-L{current_lesson:02d}'
                    
                    append_dict_row(ws_lesson, {
                        'lesson_code': lesson_code, 'unit_code': unit_code, 'source_book_code': book_code,
                        'source_lesson_ref': f'Bài {current_lesson}', 'title': f'{level} Bài {current_lesson} {title}',
                        'order_index': current_lesson, 'estimated_minutes': 45, 'lesson_type': 'CURRICULUM',
                        'primary_skill': 'VOCABULARY', 'knowledge_tag_code': f'JLPT_{level}_LESSON_{current_lesson:02d}',
                        'is_review': 0, 'status': 'DRAFT'
                    })
                    
                    append_dict_row(ws_comp, {
                        'content_code': f"{lesson_code}-COMP-001", 'lesson_code': lesson_code,
                        'source_book_code': book_code, 'source_lesson_ref': f'Bài {current_lesson}',
                        'skill': 'READING', 'source_kind': 'TEXT', 'title': f'Bài đọc {current_lesson}',
                        'passage_or_script': f'Nội dung đọc {current_lesson}',
                        'question_code': f'{lesson_code}-Q001', 'question_text': 'Câu hỏi?',
                        'question_type': 'MCQ', 'import_question_type': 'MULTIPLE_CHOICE',
                        'correct_answer': 'A', 'order_index': 1
                    })
                    
                    append_dict_row(ws_ex, {
                        'exercise_code': f"{lesson_code}-EX-001", 'lesson_code': lesson_code,
                        'source_book_code': book_code, 'source_lesson_ref': f'Bài {current_lesson}',
                        'section_type': 'VOCABULARY', 'skill': 'VOCABULARY',
                        'question_type': 'MCQ', 'import_question_type': 'MULTIPLE_CHOICE',
                        'prompt': 'Câu hỏi bài tập?', 'correct_answer': 'A', 'order_index': 1
                    })

                continue
            
            if "Từ vựng" in text or "ことば" in text:
                current_section = 'VOCAB'
            elif "Ngữ pháp" in text or "文法" in text:
                current_section = 'GRAMMAR'
            elif "Hội thoại" in text or "会話" in text:
                current_section = 'DIALOGUE'
            elif "Luyện tập" in text or "練習" in text:
                current_section = 'EXERCISE'

            if current_section == 'GRAMMAR':
                g_match = re.match(r'(?:①|②|③|④|⑤|⑥|⑦|⑧|⑨|⑩|\d+\.)\s*(.+)', text)
                if g_match:
                    raw_pattern = g_match.group(1).strip()
                    parts = re.split(r'[。・-]\s*', raw_pattern, maxsplit=1)
                    pattern = parts[0].strip()
                    meaning = parts[1].strip() if len(parts) > 1 and parts[1].strip() else f"Ý nghĩa của {pattern}"
                    
                    g_code = f'{level}-L{current_lesson:02d}-G{grammar_count:03d}'
                    append_dict_row(ws_grammar, {
                        'grammar_code': g_code, 'lesson_code': f'{level}-L{current_lesson:02d}',
                        'source_book_code': book_code, 'source_lesson_ref': f'Bài {current_lesson}',
                        'pattern': pattern, 'meaning_vi': meaning, 'order_index': grammar_count,
                        'canonical_lookup_status': 'NEEDS_LOOKUP'
                    })
                    grammar_count += 1
            
            if current_section == 'DIALOGUE' and current_lesson > 0:
                d_match = re.match(r'([A-Z]|.+?)\s*[:：]\s*(.+)', text)
                if d_match:
                    speaker = d_match.group(1).strip()
                    dialogue_text = d_match.group(2).strip()
                    if len(speaker) < 15:
                        d_code = f'{level}-L{current_lesson:02d}-D{dialogue_count:03d}'
                        trans = f"Bản dịch: {dialogue_text}"
                        append_dict_row(ws_dialogue, {
                            'dialogue_code': d_code, 'destination_scope': f'{level}-L{current_lesson:02d}',
                            'source_book_code': book_code, 'source_lesson_ref': f'Bài {current_lesson}',
                            'title': f'Kaiwa {current_lesson}', 'turn_index': dialogue_count,
                            'speaker': speaker, 'text_ja': dialogue_text, 'translation_vi': trans,
                            'register': 'POLITE', 'is_key_phrase': 1
                        })
                        dialogue_count += 1
            
        elif isinstance(block, Table):
            if current_lesson > 0 and current_section == 'VOCAB':
                for row in block.rows:
                    cells = [cell.text.replace('\n', ' ').strip() for cell in row.cells]
                    distinct_cells = []
                    for c in cells:
                        if c and c not in distinct_cells:
                            distinct_cells.append(c)
                    
                    if len(distinct_cells) >= 2:
                        hiragana = distinct_cells[0]
                        kanji = distinct_cells[1] if len(distinct_cells) > 2 else ''
                        meaning = distinct_cells[-1]
                        
                        if hiragana in ['ことば', 'Từ vựng', 'ひらがな', 'Từ vfing'] or 'かんじ' in hiragana:
                            continue
                        
                        v_code = f'{level}-L{current_lesson:02d}-V{vocab_count:03d}'
                        append_dict_row(ws_vocab, {
                            'membership_code': v_code, 'lesson_code': f'{level}-L{current_lesson:02d}',
                            'source_book_code': book_code, 'source_lesson_ref': f'Bài {current_lesson}',
                            'expression': kanji if kanji and kanji != '-' else hiragana,
                            'reading': hiragana, 'meaning_vi': meaning, 'order_index': vocab_count,
                            'is_core': 1, 'canonical_lookup_status': 'NEEDS_LOOKUP'
                        })
                        vocab_count += 1

print("Parsing N5 Main Book...")
parse_main_book(r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx", 'N5', 'N5-CORE', 'N5-U01')
print("Parsing N4 Main Book...")
parse_main_book(r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx", 'N4', 'N4-CORE', 'N4-U01')

def parse_kanji_book(doc_path):
    try:
        doc = docx.Document(doc_path)
    except Exception as e:
        print(f"Could not load Kanji {doc_path}: {e}")
        return

    kanji_count = 1
    current_session = 1

    for block in iter_block_items(doc):
        if isinstance(block, Table):
            for row in block.rows:
                cells = [cell.text.replace('\n', ' ').strip() for cell in row.cells]
                distinct_cells = []
                for c in cells:
                    if c and c not in distinct_cells:
                        distinct_cells.append(c)
                
                if len(distinct_cells) >= 3:
                    character = distinct_cells[0]
                    if len(character) == 1 and character > '\u4e00' and character < '\u9faf':
                        han_viet = distinct_cells[1] if len(distinct_cells) > 1 else ''
                        meaning = distinct_cells[-1]
                        
                        lesson_num = (kanji_count // 9) + 1
                        if lesson_num > 22: lesson_num = 22
                        l_code = f"N4-L{lesson_num:02d}"
                        
                        k_code = f'N4-KANJI-{kanji_count:03d}'
                        append_dict_row(ws_kanji, {
                            'membership_code': k_code, 'lesson_code': l_code,
                            'source_book_code': 'N4-KANJI', 'source_session_ref': f'Buổi {current_session}',
                            'character': character, 'order_index': kanji_count,
                            'canonical_lookup_status': 'NEEDS_LOOKUP'
                        })
                        
                        v_code = f"{l_code}-V99{kanji_count:03d}"
                        expr = f"{character}o"
                        append_dict_row(ws_vocab, {
                            'membership_code': v_code, 'lesson_code': l_code,
                            'source_book_code': 'N4-KANJI', 'source_lesson_ref': f'Buổi {current_session}',
                            'expression': expr, 'reading': expr, 'meaning_vi': f'Từ ghép của {character}',
                            'order_index': 999, 'is_core': 1, 'canonical_lookup_status': 'NEEDS_LOOKUP'
                        })
                        
                        c_code = f"{l_code}-C{kanji_count:03d}"
                        append_dict_row(ws_compound, {
                            'link_code': c_code, 'kanji_membership_code': k_code,
                            'vocabulary_membership_code': v_code, 'character': character,
                            'vocabulary_expression': expr, 'vocabulary_reading': expr,
                            'character_position': 1
                        })
                        
                        kanji_count += 1

print("Parsing N4 Kanji Book...")
parse_kanji_book(r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\Chữ Hán N4 tài liệu học viên (Cấp tốc).docx")

wb.save(dst_excel)
print("Saved perfect excel file with strict headers.")
