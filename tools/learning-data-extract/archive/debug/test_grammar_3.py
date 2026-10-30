"""
Investigate N5 grammar paragraphs.
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
print("Scanning N5 for grammar patterns...")

for i, child in enumerate(doc.element.body.iterchildren()):
    if isinstance(child, CT_P):
        p = Paragraph(child, doc)
        text = clean(p.text)
        if re.match(r'^[①-⑩⑪-⑳]\s+', text) or re.match(r'^\d+[\.．]\s+', text):
            if 'Ngữ pháp' in text or 'TỪ VỰNG' in text: continue
            print(f"[{i:05d}] {repr(text[:80])}")
