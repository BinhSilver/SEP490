"""
Search for lesson titles in the document content to identify lesson boundaries.
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

def search_titles(path, name):
    doc = docx.Document(path)
    print(f"\n--- {name} ---")
    
    # 1. Extract titles from TOC
    titles = {}
    blocks = []
    toc_end = 0
    for i, child in enumerate(doc.element.body.iterchildren()):
        if isinstance(child, CT_P):
            p = Paragraph(child, doc)
            text = clean(p.text)
            blocks.append(text)
            m = re.match(r'(?:BÀI|Bài)\s+(\d+)[:：]?\s*(.*?)\s*\t\d+', text)
            if m:
                titles[int(m.group(1))] = m.group(2).strip().lower()
            if re.search(r'\t\d+\s*$', text):
                toc_end = i
        else:
            blocks.append("[TABLE]")

    print(f"TOC end at block {toc_end}. Found {len(titles)} titles.")
    
    # 2. Search for titles in content
    content_blocks = blocks[toc_end+1:]
    
    for num, title in titles.items():
        # Titles might be split across lines or uppercase, so we search for a substring
        # Let's take the first 10 characters of the title
        short_title = title[:15]
        found = False
        for j, text in enumerate(content_blocks):
            if short_title in text.lower():
                print(f"Lesson {num}: Found '{short_title}' at block {toc_end + 1 + j}")
                found = True
                break
        if not found:
            print(f"Lesson {num}: Not found '{short_title}'")

search_titles(r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx", "N5")
search_titles(r"C:\Users\LAPTOP LENOVO\Downloads\pdf24_convertPdfTo (1)\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.docx", "N4")
