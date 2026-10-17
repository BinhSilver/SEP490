"""
Investigate N4 document structure for lesson boundaries using styles or specific text markers.
"""
import docx
import re
import sys
sys.stdout.reconfigure(encoding='utf-8')
from docx.oxml.text.paragraph import CT_P
from docx.table import _Cell, Table
from docx.text.paragraph import Paragraph

def clean(t):
    t = str(t or '').replace('\u3000', ' ')
    t = re.sub(r'\s{2,}', ' ', t)
    return t.strip()

doc = docx.Document(r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx")
print("Scanning for large text or specific styles that might indicate lesson starts in N4...")

for i, child in enumerate(doc.element.body.iterchildren()):
    if isinstance(child, CT_P):
        p = Paragraph(child, doc)
        text = clean(p.text)
        style = p.style.name if p.style else "None"
        if 'Heading' in style or 'Title' in style or 'Heading 1' in style:
            print(f"[{i:05d}] {style}: {repr(text)}")

print("Done scanning.")
