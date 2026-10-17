"""
Inspect structure of the Word documents to understand heading patterns.
"""
import docx
from docx.oxml.table import CT_Tbl
from docx.oxml.text.paragraph import CT_P
from docx.table import _Cell, Table
from docx.text.paragraph import Paragraph

def iter_block_items(parent):
    if type(parent).__name__ == 'Document':
        parent_elm = parent.element.body
    elif type(parent).__name__ == '_Cell':
        parent_elm = parent._tc
    else:
        return
    for child in parent_elm.iterchildren():
        if isinstance(child, CT_P):
            yield Paragraph(child, parent)
        elif isinstance(child, CT_Tbl):
            yield Table(child, parent)

def inspect_doc(path, label, max_blocks=120):
    print(f"\n{'='*60}")
    print(f"  {label}")
    print(f"{'='*60}")
    doc = docx.Document(path)
    count = 0
    for block in iter_block_items(doc):
        if count >= max_blocks:
            break
        if isinstance(block, Paragraph):
            text = block.text.strip()
            style = block.style.name if block.style else "?"
            if text:
                print(f"[P|{style}] {text[:100]}")
                count += 1
        elif isinstance(block, Table):
            rows = block.rows
            if rows:
                first_row = [c.text.strip() for c in rows[0].cells]
                distinct = list(dict.fromkeys([x for x in first_row if x]))
                print(f"[TABLE rows={len(rows)}] first row: {distinct[:5]}")
                count += 1

inspect_doc(
    r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx",
    "N5 SÁCH TỔNG HỢP"
)
inspect_doc(
    r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx",
    "N4 SÁCH TỔNG HỢP"
)
inspect_doc(
    r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\Chữ Hán N4 tài liệu học viên (Cấp tốc).docx",
    "N4 CHỮ HÁN"
)
