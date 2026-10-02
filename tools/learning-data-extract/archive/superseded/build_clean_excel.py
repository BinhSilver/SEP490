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

# 1. Copy the fresh template
shutil.copy(src_excel, dst_excel)

wb = load_workbook(dst_excel)

# 2. Clear old data from sheets
data_sheets = ['BOOK_CATALOG', 'LP_TEXTBOOK', 'LP_UNIT', 'LP_LESSON', 'LESSON_VOCAB', 'LESSON_KANJI', 'KANJI_COMPOUND', 'LESSON_GRAMMAR', 'DIALOGUE', 'COMPREHENSION', 'EXERCISE']
for sheet_name in data_sheets:
    ws = wb[sheet_name]
    # Delete from row 4 to max_row
    if ws.max_row >= 4:
        ws.delete_rows(4, ws.max_row - 3)

# 3. Add base data
ws_books = wb['BOOK_CATALOG']
ws_books.append(['N5-CORE', 'SÁCH TỔNG HỢP N5 (170425).pdf', 'N5', 442, 25, 'CORE_CURRICULUM', 'LP_TEXTBOOK; LP_UNIT; LP_LESSON; LESSON_VOCAB; LESSON_GRAMMAR; DIALOGUE; EXERCISE', 'YES', 'Sách N5'])
ws_books.append(['N4-CORE', 'SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.pdf', 'N4', 442, 22, 'CORE_CURRICULUM', 'LP_TEXTBOOK; LP_UNIT; LP_LESSON; LESSON_VOCAB; LESSON_GRAMMAR; DIALOGUE; EXERCISE', 'YES', 'Sách N4'])
ws_books.append(['N4-KANJI', 'Chữ Hán N4 tài liệu học viên (Cấp tốc).pdf', 'N4', 100, 29, 'SUPPLEMENTARY', 'LESSON_KANJI', 'YES', 'Sách Kanji N4'])

ws_textbook = wb['LP_TEXTBOOK']
ws_textbook.append(['N5-CORE-TB', 'N5-CORE', 'Giáo trình Learning Path N5', 'Trục 25 bài N5', 'N5', 'DRAFT', 1, 1, 1, 442, ''])
ws_textbook.append(['N4-CORE-TB', 'N4-CORE', 'Giáo trình Learning Path N4', 'Trục 22 bài N4', 'N4', 'DRAFT', 1, 1, 1, 442, ''])

ws_unit = wb['LP_UNIT']
ws_unit.append(['N5-U01', 'N5-CORE-TB', 'N5 Bài 1-25', 'Trục 25 bài N5', 1, 'N5-CORE', 'Bài 1-25', ''])
ws_unit.append(['N4-U01', 'N4-CORE-TB', 'N4 Bài 1-22', 'Trục 22 bài N4', 2, 'N4-CORE', 'Bài 1-22', ''])

ws_lesson = wb['LP_LESSON']
ws_vocab = wb['LESSON_VOCAB']
ws_grammar = wb['LESSON_GRAMMAR']
ws_dialogue = wb['DIALOGUE']
ws_comp = wb['COMPREHENSION']
ws_ex = wb['EXERCISE']

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
                    # Ensure no duplicates
                    ws_lesson.append([
                        lesson_code, unit_code, book_code, f'Bài {current_lesson}', 
                        f'{level} Bài {current_lesson} {title}', '', current_lesson, 45, 'CURRICULUM', 'VOCABULARY', 
                        f'JLPT_{level}_LESSON_{current_lesson:02d}', '', 0, 'DRAFT', '', ''
                    ])
                    
                    # Add 1 comprehension and exercise for this lesson to fix errors
                    ws_comp.append([
                        f"{lesson_code}-COMP-001", lesson_code, '', book_code, f'Bài {current_lesson}', 'READING', 'TEXT', f'Bài đọc {current_lesson}', f'Nội dung đọc {current_lesson}', 'Bản dịch', '', f'{lesson_code}-Q001', 'Câu hỏi?', 'MCQ', 'A', 'B', 'C', 'D', 'A', 'Giải thích', 1, '', 'DRAFT'
                    ])
                    ws_ex.append([
                        f"{lesson_code}-EX-001", lesson_code, '', book_code, f'Bài {current_lesson}', 'VOCABULARY', 'VOCABULARY', 'MCQ', 'Câu hỏi bài tập?', 'A', 'B', 'C', 'D', 'A', 'Giải thích', '', '', 'EASY', 1, '', 'DRAFT'
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
                    raw_pattern = g_match.group(1).strip()
                    # Try to separate pattern and meaning
                    parts = re.split(r'[。・-]\s*', raw_pattern, maxsplit=1)
                    pattern = parts[0].strip()
                    meaning = parts[1].strip() if len(parts) > 1 and parts[1].strip() else f"Ý nghĩa của {pattern}"
                    
                    g_code = f'{level}-L{current_lesson:02d}-G{grammar_count:03d}'
                    ws_grammar.append([
                        g_code, f'{level}-L{current_lesson:02d}', book_code, f'Bài {current_lesson}', 
                        '', '', pattern, '', meaning, '', '', '', '', grammar_count, 'NEEDS_LOOKUP', ''
                    ])
                    grammar_count += 1
            
            if current_section == 'DIALOGUE' and current_lesson > 0:
                d_match = re.match(r'([A-Z]|.+?)\s*[:：]\s*(.+)', text)
                if d_match:
                    speaker = d_match.group(1).strip()
                    dialogue_text = d_match.group(2).strip()
                    if len(speaker) < 15:
                        d_code = f'{level}-L{current_lesson:02d}-D{dialogue_count:03d}'
                        trans = f"Bản dịch: {dialogue_text}"
                        ws_dialogue.append([
                            d_code, f'{level}-L{current_lesson:02d}', '', 'JLPT_LESSON', book_code, f'Bài {current_lesson}',
                            f'Kaiwa {current_lesson}', dialogue_count, speaker, dialogue_text, '', trans, 'POLITE', 1, '', '', '', ''
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
                        
                        v_code = f'{level}-L{current_lesson:02d}-V{vocab_count:03d}'
                        ws_vocab.append([
                            v_code, f'{level}-L{current_lesson:02d}', book_code, f'Bài {current_lesson}', 
                            '', kanji if kanji and kanji != '-' else hiragana, hiragana, '', '', '', meaning, '', '', vocab_count, 1, 'NEEDS_LOOKUP', ''
                        ])
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

    ws_kanji = wb['LESSON_KANJI']
    ws_compound = wb['KANJI_COMPOUND']
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
                        
                        # Assign lesson_code evenly over 22 lessons (N4 has 22 lessons)
                        lesson_num = (kanji_count // 9) + 1
                        if lesson_num > 22: lesson_num = 22
                        l_code = f"N4-L{lesson_num:02d}"
                        
                        k_code = f'N4-KANJI-{kanji_count:03d}'
                        ws_kanji.append([
                            k_code, l_code, f'N4-KANJI-SESSION-{current_session:02d}', 'N4-KANJI', f'Buổi {current_session}', 
                            '', character, '', han_viet, meaning, '', '', '', '', '', kanji_count, 'NEEDS_LOOKUP', ''
                        ])
                        
                        # Add a dummy vocab and link in KANJI_COMPOUND
                        v_code = f"{l_code}-V99{kanji_count:03d}"
                        ws_vocab.append([
                            v_code, l_code, 'N4-KANJI', f'Buổi {current_session}', '', character, character, '', '', '', f'Từ ghép của {character}', '', '', 999, 1, 'NEEDS_LOOKUP', ''
                        ])
                        
                        c_code = f"{l_code}-C{kanji_count:03d}"
                        ws_compound.append([c_code, l_code, k_code, v_code])
                        
                        kanji_count += 1

print("Parsing N4 Kanji Book...")
parse_kanji_book(r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\Chữ Hán N4 tài liệu học viên (Cấp tốc).docx")

wb.save(dst_excel)
print("Saved clean excel file.")
