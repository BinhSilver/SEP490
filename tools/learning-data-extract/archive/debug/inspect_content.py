"""
Inspect deeper structure - actual block types INSIDE each lesson, not just TOC.
We need to see how vocab/grammar tables look inside each lesson.
"""
import docx
import re
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

def inspect_doc_content(path, label, start_lesson=1, end_lesson=3):
    """Inspect actual content blocks for specific lessons, skipping TOC."""
    print(f"\n{'='*60}")
    print(f"  {label}: Lessons {start_lesson}-{end_lesson}")
    print(f"{'='*60}")
    doc = docx.Document(path)
    
    # First pass: find page number of lesson headings
    toc_done = False
    current_lesson = 0
    in_toc = True
    
    for block in iter_block_items(doc):
        if isinstance(block, Paragraph):
            text = block.text.strip()
            style = block.style.name if block.style else "?"
            if not text:
                continue
            
            # Detect TOC end (first BÀI without page number context)
            is_lesson_heading = bool(re.match(r'BÀI\s+\d+', text))
            has_tab_page = '\t' in text  # TOC entries have tab+page number
            
            if is_lesson_heading and not has_tab_page:
                in_toc = False
                lesson_num_m = re.match(r'BÀI\s+(\d+)', text)
                if lesson_num_m:
                    current_lesson = int(lesson_num_m.group(1))
                    if start_lesson <= current_lesson <= end_lesson:
                        print(f"\n--- {text[:80]} ---")
                        print(f"    [style={style}]")
                continue
            
            if not in_toc and start_lesson <= current_lesson <= end_lesson:
                # Skip TOC-like lines
                if has_tab_page and is_lesson_heading:
                    continue
                if text and len(text) < 200:
                    print(f"  [P|{style[:15]}] {text[:100]}")
                    
        elif isinstance(block, Table) and not in_toc and start_lesson <= current_lesson <= end_lesson:
            rows = block.rows
            nr = len(rows)
            if nr > 0:
                print(f"  [TABLE rows={nr}]")
                for i, row in enumerate(rows[:5]):
                    cells = [c.text.replace('\n', ' ').strip()[:30] for c in row.cells]
                    distinct = list(dict.fromkeys([x for x in cells if x]))
                    if distinct:
                        print(f"    row{i+1}: {distinct}")

import sys
sys.stdout.reconfigure(encoding='utf-8')

inspect_doc_content(
    r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx",
    "N5", start_lesson=1, end_lesson=2
)
inspect_doc_content(
    r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx",
    "N4", start_lesson=1, end_lesson=2
)
