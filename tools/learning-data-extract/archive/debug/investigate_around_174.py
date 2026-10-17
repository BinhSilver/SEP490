"""
Dump around grammar table 174.
"""
import docx
import re
import sys
sys.stdout.reconfigure(encoding='utf-8')
from docx.oxml.text.paragraph import CT_P
from docx.text.paragraph import Paragraph
from docx.oxml.table import CT_Tbl
from docx.table import Table

def clean(t):
    t = str(t or '').replace('\u3000', ' ')
    t = re.sub(r'\s{2,}', ' ', t)
    return t.strip()

doc = docx.Document(r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx")
print("Blocks 160-200:")
for i, child in enumerate(doc.element.body.iterchildren()):
    if 160 <= i <= 200:
        if isinstance(child, CT_P):
            print(f"[{i:05d}] {repr(clean(Paragraph(child, doc).text))}")
        elif isinstance(child, CT_Tbl):
            print(f"[{i:05d}] [TABLE]")
