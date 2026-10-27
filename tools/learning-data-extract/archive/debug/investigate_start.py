"""
Investigate N4 document start of content.
"""
import docx
import sys
sys.stdout.reconfigure(encoding='utf-8')
from docx.oxml.text.paragraph import CT_P
from docx.text.paragraph import Paragraph

doc = docx.Document(r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx")
print("Blocks 110-150:")
count = 0
for child in doc.element.body.iterchildren():
    if isinstance(child, CT_P):
        p = Paragraph(child, doc)
        text = str(p.text).strip().replace('\n', ' ')
        if text:
            print(f"[{count:04d}] {repr(text)}")
    else:
        # Table
        pass
    count += 1
    if count > 150:
        break
