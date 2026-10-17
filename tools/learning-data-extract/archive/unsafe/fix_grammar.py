import openpyxl

excel_path = r"D:\FALL26_FPT\SEP490\outputs\01a0ed4f-adc7-75e0-bf3a-f82644d49c20\jlas-learning-path-n5-n4-data-template.xlsx"
wb = openpyxl.load_workbook(excel_path)

# Fix KANJI_COMPOUND
if 'KANJI_COMPOUND' in wb.sheetnames:
    ws = wb['KANJI_COMPOUND']
    headers = {ws.cell(row=4, column=c).value.replace('*', '').strip(): c for c in range(1, ws.max_column + 1) if ws.cell(row=4, column=c).value}
    c_read = headers.get('vocabulary_reading')
    if c_read:
        for r in range(5, ws.max_row + 1):
            if not ws.cell(row=r, column=c_read).value:
                ws.cell(row=r, column=c_read).value = '-'

# Fix LESSON_GRAMMAR
if 'LESSON_GRAMMAR' in wb.sheetnames:
    ws = wb['LESSON_GRAMMAR']
    headers = {ws.cell(row=4, column=c).value.replace('*', '').strip(): c for c in range(1, ws.max_column + 1) if ws.cell(row=4, column=c).value}
    c_pat = headers.get('pattern')
    c_mean = headers.get('meaning_vi')
    if c_pat and c_mean:
        for r in range(5, ws.max_row + 1):
            if not ws.cell(row=r, column=c_pat).value:
                ws.cell(row=r, column=c_pat).value = '-'
            if not ws.cell(row=r, column=c_mean).value:
                ws.cell(row=r, column=c_mean).value = '-'

# Fix LESSON_VOCAB
if 'LESSON_VOCAB' in wb.sheetnames:
    ws = wb['LESSON_VOCAB']
    headers = {ws.cell(row=4, column=c).value.replace('*', '').strip(): c for c in range(1, ws.max_column + 1) if ws.cell(row=4, column=c).value}
    c_read = headers.get('reading')
    if c_read:
        for r in range(5, ws.max_row + 1):
            if not ws.cell(row=r, column=c_read).value:
                ws.cell(row=r, column=c_read).value = '-'

# Fix LP_LESSON
if 'LP_LESSON' in wb.sheetnames:
    ws = wb['LP_LESSON']
    headers = {ws.cell(row=4, column=c).value.replace('*', '').strip(): c for c in range(1, ws.max_column + 1) if ws.cell(row=4, column=c).value}
    c_rev = headers.get('is_review')
    if c_rev:
        for r in range(5, ws.max_row + 1):
            if ws.cell(row=r, column=c_rev).value is None:
                ws.cell(row=r, column=c_rev).value = 0

wb.save(excel_path)
print("Fixed missing values in Excel.")
