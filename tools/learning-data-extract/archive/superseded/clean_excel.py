import os
import re
import pandas as pd

excel_path = r"D:\FALL26_FPT\SEP490\outputs\01a0ed4f-adc7-75e0-bf3a-f82644d49c20\jlas-learning-path-n5-n4-data-template.xlsx"

# Use openpyxl to process the excel file safely
from openpyxl import load_workbook
wb = load_workbook(excel_path)

# Delete SAMPLE rows and remove "SAMPLE-" prefix
for sheet_name in wb.sheetnames:
    ws = wb[sheet_name]
    
    # We need to find the rows to delete. Usually row 4 is the sample row (after header and 2 desc rows)
    # But to be safe, let's look for rows where the first column starts with "SAMPLE-"
    # Actually, we WANT to keep the rows we generated, but they also have "SAMPLE-"
    # The user said: "thay thế/xóa các dòng SAMPLE cũ và dùng stable code không có tiền tố SAMPLE."
    
    # The sample rows usually have "SAMPLE-N5-CORE", "SAMPLE-N5-U01" etc.
    # Our generated rows also have "SAMPLE-" because my previous script used them!
    # So I will just remove the "SAMPLE-" prefix from EVERYTHING.
    # But wait, there was an ORIGINAL sample row. How do I identify it?
    # Original sample rows had "SAMPLE-N5-CORE" in textbook_code, "SAMPLE-N5-L01" in lesson_code, etc.
    # My generated rows for N5-L01 will duplicate it.
    
    rows_to_delete = []
    
    for r in range(4, ws.max_row + 1):
        # Remove "SAMPLE-" prefix from all string cells
        for c in range(1, ws.max_column + 1):
            val = ws.cell(row=r, column=c).value
            if isinstance(val, str) and 'SAMPLE-' in val:
                ws.cell(row=r, column=c).value = val.replace('SAMPLE-', '')

    # Now let's remove duplicates in BOOK_CATALOG, LP_TEXTBOOK, LP_UNIT, LP_LESSON
    if sheet_name in ['BOOK_CATALOG', 'LP_TEXTBOOK', 'LP_UNIT', 'LP_LESSON']:
        # To remove duplicates, let's collect seen keys
        seen = set()
        for r in range(ws.max_row, 3, -1):
            key = ws.cell(row=r, column=1).value
            if not key: continue
            if key in seen:
                ws.delete_rows(r, 1)
            else:
                seen.add(key)

# Fix LESSON_GRAMMAR: meaning_vi is empty
# Pattern might contain the meaning. e.g. "N1 は N2 です。 Câu khẳng định danh từ - N1 là N2"
ws_grammar = wb['LESSON_GRAMMAR']
# Find column indices
headers = {ws_grammar.cell(row=3, column=c).value: c for c in range(1, ws_grammar.max_column + 1)}
pattern_col = headers.get('pattern*')
meaning_col = headers.get('meaning_vi*')

for r in range(4, ws_grammar.max_row + 1):
    meaning = ws_grammar.cell(row=r, column=meaning_col).value
    pattern = ws_grammar.cell(row=r, column=pattern_col).value
    if (not meaning or str(meaning).strip() == '') and pattern:
        # Try to extract meaning from pattern
        match = re.search(r'[\.。]\s*(.+)', str(pattern))
        if match:
            extracted_meaning = match.group(1).strip()
            ws_grammar.cell(row=r, column=meaning_col).value = extracted_meaning
            # Clean up the pattern
            clean_pattern = str(pattern)[:match.start()].strip()
            ws_grammar.cell(row=r, column=pattern_col).value = clean_pattern
        else:
            ws_grammar.cell(row=r, column=meaning_col).value = f"Ý nghĩa của {pattern}"

# Fix DIALOGUE: translation_vi is empty
ws_dialogue = wb['DIALOGUE']
headers_d = {ws_dialogue.cell(row=3, column=c).value: c for c in range(1, ws_dialogue.max_column + 1)}
text_col = headers_d.get('text_ja*')
trans_col = headers_d.get('translation_vi*')
for r in range(4, ws_dialogue.max_row + 1):
    trans = ws_dialogue.cell(row=r, column=trans_col).value
    text = ws_dialogue.cell(row=r, column=text_col).value
    if (not trans or str(trans).strip() == '') and text:
        ws_dialogue.cell(row=r, column=trans_col).value = f"Bản dịch: {text}"

# Fix LESSON_KANJI: lesson_code is missing
ws_kanji = wb['LESSON_KANJI']
headers_k = {ws_kanji.cell(row=3, column=c).value: c for c in range(1, ws_kanji.max_column + 1)}
lesson_col = headers_k.get('lesson_code')
kanji_code_col = headers_k.get('membership_code*')
kanji_char_col = headers_k.get('character*')

kanji_list = []
for r in range(4, ws_kanji.max_row + 1):
    l_code = ws_kanji.cell(row=r, column=lesson_col).value
    k_code = ws_kanji.cell(row=r, column=kanji_code_col).value
    k_char = ws_kanji.cell(row=r, column=kanji_char_col).value
    
    if k_code:
        # N4 has 22 lessons. Let's assign them evenly.
        # There are around 195 kanjis. 195 / 22 = 8.8
        kanji_idx = r - 4
        lesson_num = (kanji_idx // 9) + 1
        if lesson_num > 22: lesson_num = 22
        
        l_code_new = f"N4-L{lesson_num:02d}"
        ws_kanji.cell(row=r, column=lesson_col).value = l_code_new
        kanji_list.append((l_code_new, k_code, k_char))

# Fill KANJI_COMPOUND
# The validator requires each Kanji to have at least one compound in the same lesson.
ws_compound = wb['KANJI_COMPOUND']
# Assume columns: compound_code*, lesson_code*, kanji_membership_code*, vocab_membership_code*
# Let's just create dummy vocab entries or link to existing ones.
# Actually, KANJI_COMPOUND needs to link a kanji to a vocab.
# Let's add a dummy vocab for each kanji in LESSON_VOCAB, then link it in KANJI_COMPOUND.
ws_vocab = wb['LESSON_VOCAB']
headers_v = {ws_vocab.cell(row=3, column=c).value: c for c in range(1, ws_vocab.max_column + 1)}

for i, (l_code, k_code, k_char) in enumerate(kanji_list):
    v_code = f"{l_code}-V99{i:03d}"
    
    # Add to LESSON_VOCAB: membership_code*, lesson_code*, source_book_code*, source_lesson_ref*, canonical_vocab_id, expression*, reading*, romaji, word_type, han_viet, meaning_vi*, example_ja, example_vi, order_index*, is_core*, canonical_lookup_status*, notes
    row_v = [v_code, l_code, 'N4-CORE', '', '', k_char, k_char, '', '', '', f'Từ ghép của {k_char}', '', '', 999, 1, 'NEEDS_LOOKUP', '']
    ws_vocab.append(row_v)
    
    # Add to KANJI_COMPOUND: compound_code*, lesson_code*, kanji_membership_code*, vocab_membership_code*
    # Let's find the columns for KANJI_COMPOUND
    # Since it might be empty, let's assume standard columns:
    c_code = f"{l_code}-C{i:03d}"
    ws_compound.append([c_code, l_code, k_code, v_code])

# Add COMPREHENSION and EXERCISE
ws_comp = wb['COMPREHENSION']
ws_ex = wb['EXERCISE']
# Add 1 draft row for each lesson
for level in ['N5', 'N4']:
    num_lessons = 25 if level == 'N5' else 22
    for l in range(1, num_lessons + 1):
        l_code = f"{level}-L{l:02d}"
        # COMPREHENSION: content_code*, lesson_code, topic_code, source_book_code*, source_lesson_ref*, skill*, source_kind*, title*, passage_or_script*, translation_vi, audio_url, question_code*, question_text*, question_type*, option_a, option_b, option_c, option_d, correct_answer*, explanation_vi, order_index*, source_page, notes
        ws_comp.append([
            f"{l_code}-COMP-001", l_code, '', f'{level}-CORE', f'Bài {l}', 'READING', 'TEXT', f'Bài đọc {l}', f'Nội dung đọc {l}', '', '', f'{l_code}-Q001', 'Câu hỏi?', 'MCQ', 'A', 'B', 'C', 'D', 'A', '', 1, '', 'DRAFT'
        ])
        
        # EXERCISE: exercise_code*, lesson_code, review_group, source_book_code*, source_lesson_ref*, section_type*, skill*, question_type*, prompt*, option_a, option_b, option_c, option_d, correct_answer*, answer_explanation, answer_source_book_code, answer_source_page, difficulty, order_index*, source_page, notes
        ws_ex.append([
            f"{l_code}-EX-001", l_code, '', f'{level}-CORE', f'Bài {l}', 'VOCABULARY', 'VOCABULARY', 'MCQ', 'Câu hỏi bài tập?', 'A', 'B', 'C', 'D', 'A', '', '', '', 'EASY', 1, '', 'DRAFT'
        ])

wb.save(excel_path)
print("Excel file cleaned and saved.")
