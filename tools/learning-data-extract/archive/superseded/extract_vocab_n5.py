import docx
import pandas as pd
import re
import os

n5_doc_path = r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx"
template_path = r"C:\Users\LAPTOP LENOVO\Downloads\jlas-learning-path-n5-n4-data-template.xlsx"
output_path = r"C:\Users\LAPTOP LENOVO\Downloads\jlas-learning-path-n5-n4-data-sample-with-vocab.xlsx"

doc = docx.Document(n5_doc_path)
with pd.ExcelFile(template_path) as xls:
    sheets = {sheet_name: pd.read_excel(xls, sheet_name=sheet_name) for sheet_name in xls.sheet_names}

vocab_df = sheets['LESSON_VOCAB']

current_lesson = 0
vocab_count = 1

for table in doc.tables:
    # A table might be vocab if it has 3 columns: hiragana, kanji, meaning
    # But sometimes word doc tables are weird.
    for row in table.rows:
        cells = [cell.text.replace('\n', ' ').strip() for cell in row.cells]
        # Remove empty cells
        cells = [c for c in cells if c]
        if len(cells) == 3:
            # It's likely a vocabulary row
            hiragana, kanji, meaning = cells
            # Skip header rows
            if hiragana in ['ことば', 'Từ vựng', 'ひらがな']:
                continue
            
            # Simple heuristic to detect if we moved to a new lesson
            # In Word doc, it's hard without parsing paragraphs. Let's just dump them sequentially for now
            # To be more accurate, we should iterate over elements (paragraphs and tables) in order
            pass

def iter_block_items(parent):
    from docx.document import Document
    from docx.oxml.table import CT_Tbl
    from docx.oxml.text.paragraph import CT_P
    from docx.table import _Cell, Table
    from docx.text.paragraph import Paragraph

    if isinstance(parent, Document):
        parent_elm = parent.element.body
    elif isinstance(parent, _Cell):
        parent_elm = parent._tc
    else:
        raise ValueError("something's not right")

    for child in parent_elm.iterchildren():
        if isinstance(child, CT_P):
            yield Paragraph(child, parent)
        elif isinstance(child, CT_Tbl):
            yield Table(child, parent)

current_lesson = 0
vocab_count = 1

for block in iter_block_items(doc):
    if isinstance(block, docx.text.paragraph.Paragraph):
        text = block.text.strip()
        match = re.search(r'BÀI (\d+):', text, re.IGNORECASE)
        if match:
            current_lesson = int(match.group(1))
            vocab_count = 1
    elif isinstance(block, docx.table.Table):
        if current_lesson > 0:
            for row in block.rows:
                cells = [cell.text.replace('\n', ' ').strip() for cell in row.cells]
                # Sometimes Word merges cells or adds empty ones
                # We need exactly 3 distinct texts for a typical vocab table: hiragana, kanji, vi
                distinct_cells = []
                for c in cells:
                    if c and c not in distinct_cells:
                        distinct_cells.append(c)
                
                if len(distinct_cells) >= 3:
                    hiragana = distinct_cells[0]
                    kanji = distinct_cells[1]
                    meaning = distinct_cells[2]
                    
                    if hiragana in ['ことば', 'Từ vựng', 'ひらがな', 'Từ vfing']:
                        continue
                        
                    vocab_df.loc[len(vocab_df)] = [
                        f'N5-L{current_lesson:02d}-V{vocab_count:03d}', 
                        f'N5-L{current_lesson:02d}', 
                        'N5-TONG-HOP', 
                        f'Bài {current_lesson}', 
                        '', 
                        kanji if kanji and kanji != '-' else hiragana, 
                        hiragana, 
                        '', '', '', meaning, '', '', vocab_count, 1, 'NEEDS_LOOKUP', ''
                    ]
                    vocab_count += 1

with pd.ExcelWriter(output_path) as writer:
    for sheet_name, df in sheets.items():
        df.to_excel(writer, sheet_name=sheet_name, index=False)

print(f"Extraction complete! Saved to {output_path}")
