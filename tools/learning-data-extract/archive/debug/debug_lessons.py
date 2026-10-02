"""
Debug: understand how content is structured post-TOC.
Find section headings and vocab/grammar blocks in content area.
"""
import docx
import re
import sys
sys.stdout.reconfigure(encoding='utf-8')
from docx.oxml.table import CT_Tbl
from docx.oxml.text.paragraph import CT_P
from docx.table import _Cell, Table
from docx.text.paragraph import Paragraph

def iter_blocks(doc):
    parent_elm = doc.element.body
    for child in parent_elm.iterchildren():
        if isinstance(child, CT_P):
            yield Paragraph(child, doc)
        elif isinstance(child, CT_Tbl):
            yield Table(child, doc)

def dump_section_blocks(path, label, start, end):
    doc = docx.Document(path)
    blocks = list(iter_blocks(doc))
    print(f"\n=== {label}: blocks {start}-{end} ===")
    for i in range(start, min(end, len(blocks))):
        block = blocks[i]
        if isinstance(block, Paragraph):
            text = block.text.strip()
            if text:
                style = block.style.name if block.style else "?"
                print(f"  {i}: [{style[:12]}] {repr(text[:100])}")
        elif isinstance(block, Table):
            rows = block.rows
            print(f"  {i}: [TABLE nr={len(rows)}]")
            for ri, row in enumerate(rows[:3]):
                cells = [c.text.replace('\n',' ').strip()[:25] for c in row.cells]
                d = list(dict.fromkeys([x for x in cells if x]))
                if d:
                    print(f"       r{ri}: {d}")

# N5: content should start around block 150-200
dump_section_blocks(r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx", "N5-content-start", 150, 300)
