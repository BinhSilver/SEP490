"""
Test N5 grammar extraction - dump table 2.
"""
import docx
import re
import sys
sys.stdout.reconfigure(encoding='utf-8')
from docx.oxml.table import CT_Tbl
from docx.table import Table

def clean(t):
    t = str(t or '').replace('\u3000', ' ')
    t = re.sub(r'\s{2,}', ' ', t)
    return t.strip()

doc = docx.Document(r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx")
tables = []
for child in doc.element.body.iterchildren():
    if isinstance(child, CT_Tbl):
        tables.append(Table(child, doc))

count = 0
for table in tables:
    is_grammar = False
    for row in table.rows:
        cells_text = ' '.join(clean(c.text) for c in row.cells)
        if re.search(r'[①-⑩]', cells_text):
            is_grammar = True
            break
    
    if is_grammar:
        count += 1
        print(f"\n--- Grammar Table {count} ---")
        for r, row in enumerate(table.rows):
            cells = [clean(c.text) for c in row.cells]
            print(f"Row {r}: {cells}")
        if count >= 3:
            break
