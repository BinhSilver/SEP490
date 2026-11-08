"""
Simpler approach: just look for blocks after block index 145 (where TOC ends in N5).
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

def dump_from_index(path, label, start_idx=145, limit=100):
    print(f"\n=== {label} (from block {start_idx}) ===")
    doc = docx.Document(path)
    blocks = list(iter_block_items(doc))
    
    for i in range(start_idx, min(start_idx + limit, len(blocks))):
        block = blocks[i]
        if isinstance(block, Paragraph):
            text = block.text.strip()
            style = block.style.name if block.style else "?"
            if text:
                print(f"{i:04d} [P|{style[:15]}] {repr(text[:120])}")
        elif isinstance(block, Table):
            rows = block.rows
            nr = len(rows)
            if nr > 0:
                print(f"{i:04d} [TABLE nr={nr}]")
                for ri, row in enumerate(rows[:6]):
                    cells = [c.text.replace('\n', ' ').strip()[:35] for c in row.cells]
                    distinct = list(dict.fromkeys([x for x in cells if x]))
                    if distinct:
                        print(f"       row{ri}: {distinct}")

# For N5, TOC ended around index 145 based on previous dump
dump_from_index(r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx", "N5", start_idx=145, limit=120)
# For N4, TOC ended around index 100
dump_from_index(r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx", "N4", start_idx=100, limit=120)
