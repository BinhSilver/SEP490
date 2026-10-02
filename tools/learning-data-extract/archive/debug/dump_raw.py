"""
Dump all block types with position info to understand document structure.
Shows where lesson headings and vocab/grammar sections are.
"""
import docx
import re
import sys
sys.stdout.reconfigure(encoding='utf-8')

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

def dump_raw(path, label, limit=300):
    print(f"\n=== {label} ===")
    doc = docx.Document(path)
    i = 0
    for block in iter_block_items(doc):
        if i >= limit:
            break
        if isinstance(block, Paragraph):
            text = block.text.strip()
            style = block.style.name if block.style else "?"
            if text:
                print(f"{i:04d} [P|{style}] {repr(text[:100])}")
                i += 1
        elif isinstance(block, Table):
            rows = block.rows
            nr = len(rows)
            if rows:
                first = [c.text.replace('\n', ' ').strip()[:25] for c in rows[0].cells]
                print(f"{i:04d} [TABLE nr={nr}] {first[:4]}")
                i += 1

dump_raw(r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx", "N5", limit=200)
dump_raw(r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx", "N4", limit=200)
