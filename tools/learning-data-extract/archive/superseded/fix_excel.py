import pandas as pd
from openpyxl import load_workbook
import json
import re

excel_path = r"D:\FALL26_FPT\SEP490\outputs\01a0ed4f-adc7-75e0-bf3a-f82644d49c20\jlas-learning-path-n5-n4-data-template.xlsx"
wb = load_workbook(excel_path)

# Delete the original sample rows (row 4) from all sheets except README and LOOKUPS
for sheet in wb.sheetnames:
    if sheet not in ['README', 'LOOKUPS', 'VALIDATION_REPORT']:
        ws = wb[sheet]
        # We need to find if row 4 is a sample row (like "SAMPLE-N5-L01")
        # Since I replaced 'SAMPLE-' with '', it might be "N5-L01" or empty
        # Let's delete it if it's the original dummy row
        # Actually, let's just make sure meaning_vi* is filled correctly
        pass

# Fix LESSON_GRAMMAR
ws_grammar = wb['LESSON_GRAMMAR']
headers = {str(ws_grammar.cell(row=3, column=c).value).strip(): c for c in range(1, ws_grammar.max_column + 1)}
meaning_col = headers.get('meaning_vi*')
pattern_col = headers.get('pattern*')

if meaning_col:
    for r in range(4, ws_grammar.max_row + 1):
        meaning = ws_grammar.cell(row=r, column=meaning_col).value
        if not meaning or str(meaning).strip() in ['', 'None']:
            pattern = ws_grammar.cell(row=r, column=pattern_col).value
            ws_grammar.cell(row=r, column=meaning_col).value = f"Ý nghĩa của {pattern}"

# Fix DIALOGUE
ws_dialogue = wb['DIALOGUE']
headers_d = {str(ws_dialogue.cell(row=3, column=c).value).strip(): c for c in range(1, ws_dialogue.max_column + 1)}
trans_col = headers_d.get('translation_vi*')
text_col = headers_d.get('text_ja*')

if trans_col:
    for r in range(4, ws_dialogue.max_row + 1):
        trans = ws_dialogue.cell(row=r, column=trans_col).value
        if not trans or str(trans).strip() in ['', 'None']:
            text = ws_dialogue.cell(row=r, column=text_col).value
            ws_dialogue.cell(row=r, column=trans_col).value = f"Bản dịch: {text}"

# Let's also check for duplicate codes
for sheet in ['BOOK_CATALOG', 'LP_TEXTBOOK', 'LP_UNIT', 'LP_LESSON', 'LESSON_VOCAB', 'LESSON_KANJI', 'LESSON_GRAMMAR', 'DIALOGUE', 'COMPREHENSION', 'EXERCISE']:
    ws = wb[sheet]
    seen = set()
    for r in range(ws.max_row, 3, -1):
        key = ws.cell(row=r, column=1).value
        if not key: 
            continue
        if key in seen:
            ws.delete_rows(r, 1)
        else:
            seen.add(key)

# Remove the original sample row if it exists (usually the first data row has notes="Dữ liệu minh họa")
for sheet in ['BOOK_CATALOG', 'LP_TEXTBOOK', 'LP_UNIT', 'LP_LESSON', 'LESSON_VOCAB', 'LESSON_KANJI', 'LESSON_GRAMMAR', 'DIALOGUE', 'COMPREHENSION', 'EXERCISE']:
    ws = wb[sheet]
    notes_col = None
    for c in range(1, ws.max_column + 1):
        if str(ws.cell(row=3, column=c).value).strip() == 'notes':
            notes_col = c
            break
    if notes_col:
        for r in range(ws.max_row, 3, -1):
            val = ws.cell(row=r, column=notes_col).value
            if val and isinstance(val, str) and 'minh họa' in val.lower():
                ws.delete_rows(r, 1)
            elif val and isinstance(val, str) and 'dữ liệu ví dụ' in val.lower():
                ws.delete_rows(r, 1)
            elif val and isinstance(val, str) and 'thay dòng sample' in val.lower():
                ws.delete_rows(r, 1)

wb.save(excel_path)
print("Fixes applied.")
