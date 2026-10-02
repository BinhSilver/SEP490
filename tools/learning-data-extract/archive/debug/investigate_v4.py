"""
Deep investigation of N5 document structure:
1. What's in the grammar tables (block 174+)?
2. Why L03, L13, L18, L23 have 0 vocab?
3. Kana reading structure (separate paragraphs before kanji?)
4. N4 multi-line vocab format
5. Kanji book compound structure
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

def clean(t):
    t = str(t or '').replace('\u3000', ' ')
    t = re.sub(r'\s{2,}', ' ', t)
    return t.strip()

def dump_blocks(blocks, start, end, label=""):
    print(f"\n--- {label} (blocks {start}-{end}) ---")
    for i in range(start, min(end, len(blocks))):
        block = blocks[i]
        if isinstance(block, Paragraph):
            text = clean(block.text)
            style = block.style.name if block.style else "?"
            if text:
                print(f"  {i:04d} [P|{style[:10]}] {repr(text[:100])}")
        elif isinstance(block, Table):
            rows = block.rows
            print(f"  {i:04d} [TABLE nr={len(rows)}]")
            for ri, row in enumerate(rows[:10]):
                cells = [clean(c.text)[:30] for c in row.cells]
                unique = list(dict.fromkeys([x for x in cells if x]))
                if unique:
                    print(f"       r{ri}: {unique}")

# ── Investigation 1: N5 grammar table structure ──────────────────────────────
print("\n" + "="*60)
print("1. N5 GRAMMAR TABLE STRUCTURE (blocks 150-240)")
print("="*60)
doc_n5 = docx.Document(r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx")
blocks_n5 = list(iter_blocks(doc_n5))
dump_blocks(blocks_n5, 150, 240, "N5 early content")

# ── Investigation 2: Vocab section header variation ──────────────────────────
print("\n" + "="*60)
print("2. N5 SECTION HEADERS - find all vocab/grammar headings after block 150")
print("="*60)
SECTION_KW = ['Từ vựng', 'Từ vfing', 'ことば', 'Ngữ pháp', '文法', 'Hội thoại', '会話', 'Luyện tập', 'Chữ Hán']
for i, block in enumerate(blocks_n5[150:], start=150):
    if isinstance(block, Paragraph):
        text = clean(block.text)
        for kw in SECTION_KW:
            if kw in text and len(text) < 50 and not re.search(r'\t\d+', text):
                print(f"  {i:04d}: {repr(text[:60])}")
                break

# ── Investigation 3: Around the boundary of L03 (what blocks appear there?) ──
print("\n" + "="*60)
print("3. N5 LESSON BOUNDARY ANALYSIS")
print("   Looking at vocab section events and their positions")
print("="*60)
vocab_events = []
for i, block in enumerate(blocks_n5[150:], start=150):
    if isinstance(block, Paragraph):
        text = clean(block.text)
        if any(k in text for k in ['Từ vựng', 'Từ vfing', 'ことば']) and len(text) < 50 and not re.search(r'\t\d+', text):
            vocab_events.append(i)
            print(f"  Vocab section at block {i}: {repr(text[:50])}")

print(f"\n  Total vocab sections found: {len(vocab_events)}")

# Show what comes 5 blocks BEFORE each gap (where L03, L13, L18, L23 should start)
# Vocab events: events 1,2 → L01,L02; event 3 → L03 etc.
# Actually events map to lessons based on count
print("\n  Vocab event positions:")
for idx, pos in enumerate(vocab_events[:30], 1):
    print(f"  Event {idx} → block {pos}")

# ── Investigation 4: N5 grammar tables specifically ──────────────────────────
print("\n" + "="*60)
print("4. ALL GRAMMAR TABLES IN N5 (have ①②③ pattern)")
print("="*60)
for i, block in enumerate(blocks_n5[150:], start=150):
    if isinstance(block, Table):
        for row in block.rows:
            cells = [clean(c.text) for c in row.cells]
            full = ' '.join(cells)
            if re.search(r'[①-⑩]', full):
                print(f"\n  Block {i} [TABLE nr={len(block.rows)}]:")
                for ri, row in enumerate(block.rows[:8]):
                    cells = [clean(c.text)[:40] for c in row.cells]
                    unique = list(dict.fromkeys([x for x in cells if x]))
                    if unique:
                        print(f"    r{ri}: {unique}")
                break

# ── Investigation 5: Kana reading structure ──────────────────────────────────
print("\n" + "="*60)
print("5. KANA READING STRUCTURE - how kana appears before kanji vocab")
print("   (look at N5 L01 vocab area)")
print("="*60)
dump_blocks(blocks_n5, 183, 240, "N5 L01 vocab area in detail")

# ── Investigation 6: N4 multi-line vocab ─────────────────────────────────────
print("\n" + "="*60)
print("6. N4 MULTI-LINE VOCAB (blocks 120-170)")
print("="*60)
doc_n4 = docx.Document(r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx")
blocks_n4 = list(iter_blocks(doc_n4))
dump_blocks(blocks_n4, 120, 175, "N4 L01 content")

# ── Investigation 7: Kanji book compound structure ───────────────────────────
print("\n" + "="*60)
print("7. KANJI BOOK - first few tables to understand compound structure")
print("="*60)
doc_k = docx.Document(r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\Chữ Hán N4 tài liệu học viên (Cấp tốc).docx")
blocks_k = list(iter_blocks(doc_k))
count = 0
for i, block in enumerate(blocks_k[:200]):
    if isinstance(block, Table) and count < 5:
        print(f"\n  Block {i} [TABLE nr={len(block.rows)}]:")
        for ri, row in enumerate(block.rows[:8]):
            cells = [clean(c.text)[:40] for c in row.cells]
            unique = list(dict.fromkeys([x for x in cells if x]))
            if unique:
                print(f"    r{ri}: {unique}")
        count += 1
    elif isinstance(block, Paragraph):
        text = clean(block.text)
        if text and count < 2:
            print(f"  {i}: [P] {repr(text[:80])}")
