"""
Investigate N5 Ngữ pháp content.
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
print("Scanning N5 for 'Ngữ pháp'...")

blocks = []
for i, child in enumerate(doc.element.body.iterchildren()):
    if isinstance(child, CT_P):
        blocks.append(clean(Paragraph(child, doc).text))
    else:
        blocks.append("[TABLE]")

for i, text in enumerate(blocks):
    if text.lower() == 'ngữ pháp' or text.lower() == 'ngữ pháp ／文法':
        print(f"\n--- Ngữ pháp at {i:05d} ---")
        for j in range(1, 10):
            if i + j < len(blocks):
                print(f"  [{i+j:05d}] {repr(blocks[i+j][:80])}")
