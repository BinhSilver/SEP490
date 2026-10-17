"""
Investigate N5 grammar patterns.
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

doc = docx.Document(r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx")
print("Scanning N5 for grammar-like patterns...")

count = 0
for i, child in enumerate(doc.element.body.iterchildren()):
    if isinstance(child, CT_P):
        p = Paragraph(child, doc)
        text = clean(p.text)
        if re.search(r'N[1-5]|V[てたますないる]', text) and not 'TỪ VỰNG' in text:
            print(f"[{i:05d}] {repr(text)}")
            count += 1
            if count > 50: break
