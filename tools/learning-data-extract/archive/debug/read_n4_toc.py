import fitz

pdf_path = r"G:\My Drive\GoodNotes\N4\SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.pdf"
doc = fitz.open(pdf_path)

with open('n4_toc.txt', 'w', encoding='utf-8') as f:
    for i in range(10): # Check first 10 pages
        page = doc.load_page(i)
        text = page.get_text()
        f.write(f"--- PAGE {i} ---\n{text}\n")
