"""
Investigate N4 L03 and L09 titles.
"""
import docx
import re
import sys
sys.stdout.reconfigure(encoding='utf-8')
from docx.oxml.text.paragraph import CT_P
from docx.text.paragraph import Paragraph

def clean(t):
    t = str(t or '').replace('\u3000', ' ')
    t = re.sub(r'\s{2,}', ' ', t)
    return t.strip()

doc = docx.Document(r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx")
print("Scanning for L03 (blocks 650-1550) and L09 (blocks 6483-10000)...")

for i, child in enumerate(doc.element.body.iterchildren()):
    if isinstance(child, CT_P):
        p = Paragraph(child, doc)
        text = clean(p.text)
        if 650 <= i <= 1550:
            if 'bữa trưa' in text.lower() or 'ăn ở đâu' in text.lower():
                print(f"[{i:05d}] {repr(text)}")
        if 6483 <= i <= 8000:
            if 'mùa xuân' in text.lower() or 'hoa anh đào' in text.lower():
                print(f"[{i:05d}] {repr(text)}")
