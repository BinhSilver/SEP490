"""
Test N5 grammar extraction.
"""
import docx
import re
import sys
sys.stdout.reconfigure(encoding='utf-8')
from docx.oxml.table import CT_Tbl
from docx.table import _Cell, Table

def clean(t):
    t = str(t or '').replace('\u3000', ' ')
    t = re.sub(r'\s{2,}', ' ', t)
    return t.strip()

def has_hiragana(t):
    return any('\u3041' <= c <= '\u309f' for c in t)

def is_japanese(t):
    return has_hiragana(t) or any('\u4e00' <= c <= '\u9faf' for c in t) or any('\u30a0' <= c <= '\u30ff' for c in t)

doc = docx.Document(r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx")
tables = []
for child in doc.element.body.iterchildren():
    if isinstance(child, CT_Tbl):
        tables.append(Table(child, doc))

print(f"Found {len(tables)} tables.")
count = 0
for table in tables:
    for row in table.rows:
        cells_text = ' '.join(clean(c.text) for c in row.cells)
        if re.search(r'[①-⑩]', cells_text):
            print(f"Grammar Table found!")
            for row2 in table.rows:
                cells2 = [clean(c.text) for c in row2.cells]
                full = ' '.join([c for c in cells2 if c])
                full = re.sub(r'\s+', ' ', full).strip()
                if re.match(r'^[①-⑩]', full) and is_japanese(full):
                    pattern = re.sub(r'^[①-⑩]\s*', '', full).strip()
                    if not any(m in pattern for m in ['は', 'が', 'を', 'に', 'で', 'て', 'ます', 'です', 'ない', 'た', 'V', 'N']):
                        print(f"  Filtered: {pattern}")
                    else:
                        print(f"  Extracted: {pattern}")
                        count += 1
            break
print(f"Total extracted: {count}")
