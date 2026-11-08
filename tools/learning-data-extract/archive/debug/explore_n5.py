import docx

doc_path = r"C:\Users\LAPTOP LENOVO\Downloads\SÁCH TỔNG HỢP N5 (170425).docx"
doc = docx.Document(doc_path)

with open('n5_doc_sample.txt', 'w', encoding='utf-8') as f:
    f.write("--- PARAGRAPHS ---\n")
    for i, p in enumerate(doc.paragraphs[:50]):
        if p.text.strip():
            f.write(f"[{i}] {p.text}\n")
    
    f.write("\n--- TABLES ---\n")
    for i, table in enumerate(doc.tables[:2]):
        f.write(f"Table {i}:\n")
        for row in table.rows[:5]:
            cells = [cell.text.replace('\n', ' ').strip() for cell in row.cells]
            f.write(f"  Row: {cells}\n")
