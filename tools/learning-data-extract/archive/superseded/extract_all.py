import os
import re
from openpyxl import load_workbook
import docx
from docx.oxml.table import CT_Tbl
from docx.oxml.text.paragraph import CT_P
from docx.table import _Cell, Table
from docx.text.paragraph import Paragraph

excel_path = r"D:\FALL26_FPT\SEP490\outputs\01a0ed4f-adc7-75e0-bf3a-f82644d49c20\jlas-learning-path-n5-n4-data-template.xlsx"
n5_main_path = r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx"
n4_main_path = r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx"
n4_kanji_path = r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\Chữ Hán N4 tài liệu học viên (Cấp tốc).docx"

wb = load_workbook(excel_path)

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

# Update BOOK_CATALOG
ws_books = wb['BOOK_CATALOG']
books = [
    ['N5-CORE', 'SÁCH TỔNG HỢP N5 (170425).docx', 'N5', 442, 25, 'CORE_CURRICULUM', 'LP_TEXTBOOK', 'YES', 'Sách N5'],
    ['N4-CORE', 'SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx', 'N4', 442, 22, 'CORE_CURRICULUM', 'LP_TEXTBOOK', 'YES', 'Sách N4'],
    ['N4-KANJI', 'Chữ Hán N4 tài liệu học viên (Cấp tốc).docx', 'N4', 100, 29, 'SUPPLEMENTARY', 'LESSON_KANJI', 'YES', 'Sách Kanji N4']
]
for b in books:
    ws_books.append(b)

# Update LP_TEXTBOOK
ws_textbook = wb['LP_TEXTBOOK']
textbooks = [
    ['SAMPLE-N5-CORE', 'N5-CORE', 'Giáo trình Learning Path N5', 'Trục 25 bài N5', 'N5', 'DRAFT', 1, 1, 1, 442, ''],
    ['SAMPLE-N4-CORE', 'N4-CORE', 'Giáo trình Learning Path N4', 'Trục 22 bài N4', 'N4', 'DRAFT', 1, 1, 1, 442, '']
]
for t in textbooks:
    ws_textbook.append(t)

# Update LP_UNIT
ws_unit = wb['LP_UNIT']
units = [
    ['SAMPLE-N5-U01', 'SAMPLE-N5-CORE', 'N5 Bài 1-25', 'Trục 25 bài N5', 1, 'N5-CORE', 'Bài 1-25', ''],
    ['SAMPLE-N4-U01', 'SAMPLE-N4-CORE', 'N4 Bài 1-22', 'Trục 22 bài N4', 2, 'N4-CORE', 'Bài 1-22', '']
]
for u in units:
    ws_unit.append(u)

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
    
    ws_lesson = wb['LP_LESSON']
    ws_vocab = wb['LESSON_VOCAB']
    ws_grammar = wb['LESSON_GRAMMAR']
    ws_dialogue = wb['DIALOGUE']

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
                    
                    lesson_code = f'SAMPLE-{level}-L{current_lesson:02d}'
                    ws_lesson.append([
                        lesson_code, unit_code, book_code, f'Bài {current_lesson}', 
                        f'{level} Bài {current_lesson} {title}', '', current_lesson, 45, 'CURRICULUM', 'VOCABULARY', 
                        f'JLPT_{level}_LESSON_{current_lesson:02d}', '', 0, 'DRAFT', '', ''
                    ])
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
                    current_grammar_pattern = g_match.group(1).strip()
                    g_code = f'SAMPLE-{level}-L{current_lesson:02d}-G{grammar_count:03d}'
                    ws_grammar.append([
                        g_code, f'SAMPLE-{level}-L{current_lesson:02d}', book_code, f'Bài {current_lesson}', 
                        '', '', current_grammar_pattern, '', '', '', '', '', '', grammar_count, 'NEEDS_LOOKUP', ''
                    ])
                    grammar_count += 1
            
            if current_section == 'DIALOGUE' and current_lesson > 0:
                d_match = re.match(r'([A-Z]|.+?)\s*[:：]\s*(.+)', text)
                if d_match:
                    speaker = d_match.group(1).strip()
                    dialogue_text = d_match.group(2).strip()
                    if len(speaker) < 15:
                        d_code = f'SAMPLE-{level}-L{current_lesson:02d}-D{dialogue_count:03d}'
                        ws_dialogue.append([
                            d_code, f'SAMPLE-{level}-L{current_lesson:02d}', '', 'JLPT_LESSON', book_code, f'Bài {current_lesson}',
                            f'Kaiwa {current_lesson}', dialogue_count, speaker, dialogue_text, '', '', 'POLITE', 1, '', '', '', ''
                        ])
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
                        
                        v_code = f'SAMPLE-{level}-L{current_lesson:02d}-V{vocab_count:03d}'
                        ws_vocab.append([
                            v_code, f'SAMPLE-{level}-L{current_lesson:02d}', book_code, f'Bài {current_lesson}', 
                            '', kanji if kanji and kanji != '-' else hiragana, hiragana, '', '', '', meaning, '', '', vocab_count, 1, 'NEEDS_LOOKUP', ''
                        ])
                        vocab_count += 1

print("Parsing N5 Main Book...")
parse_main_book(n5_main_path, 'N5', 'N5-CORE', 'SAMPLE-N5-U01')
print("Parsing N4 Main Book...")
parse_main_book(n4_main_path, 'N4', 'N4-CORE', 'SAMPLE-N4-U01')

def parse_kanji_book(doc_path):
    try:
        doc = docx.Document(doc_path)
    except Exception as e:
        print(f"Could not load Kanji {doc_path}: {e}")
        return

    ws_kanji = wb['LESSON_KANJI']
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
                        
                        k_code = f'SAMPLE-N4-KANJI-{kanji_count:03d}'
                        ws_kanji.append([
                            k_code, '', f'N4-KANJI-SESSION-{current_session:02d}', 'N4-KANJI', f'Buổi {current_session}', 
                            '', character, '', han_viet, meaning, '', '', '', '', '', kanji_count, 'NEEDS_LOOKUP', ''
                        ])
                        kanji_count += 1

print("Parsing N4 Kanji Book...")
parse_kanji_book(n4_kanji_path)

wb.save(excel_path)
print(f"Data extraction complete. Populated sheets saved to {excel_path}")
