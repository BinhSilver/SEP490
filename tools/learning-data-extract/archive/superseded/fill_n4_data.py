import pandas as pd
import re

excel_path = r'C:\Users\LAPTOP LENOVO\Downloads\jlas-learning-path-n5-n4-data-sample.xlsx'

toc_text = """BÀI 1: Giải thích cho em ngữ pháp này được không?
BÀI 2: Cái này có thể ăn được không?
BÀI 3: Bữa trưa ăn ở đâu?
BÀI 4: Tự động từ và tha động từ
BÀI 5: Dự định
BÀI 6: Khuyên nhủ
BÀI 7: Im đê...
BÀI 8: Làm theo thầy nói
BÀI 9: Mùa xuân đến, hoa anh đào nở
BÀI 10: Cách sử dụng của ように
BÀI 11: Em bị (được)
BÀI 12: Trợ từ の
BÀI 13: Rất buồn vì không gặp được em
BÀI 14: Có nhớ là để đâu không?
BÀI 15: Biếu quà cho giám đốc
BÀI 16: Để trở thành Youtuber...
BÀI 17: Hôm qua uống quá nhiều
BÀI 18: Vừa về đến nhà
BÀI 19: Nghe nói đã có người yêu
BÀI 20: Anh sẽ không làm em khóc
BÀI 21: Cuộc đời mỗi con người
BÀI 22: Chuyện kể về nàng Murasaki"""

lessons = []
for line in toc_text.split('\n'):
    match = re.match(r'BÀI (\d+): (.+)', line)
    if match:
        num = int(match.group(1))
        title = match.group(2).strip()
        lessons.append((num, title))

with pd.ExcelFile(excel_path) as xls:
    sheets = {sheet_name: pd.read_excel(xls, sheet_name=sheet_name) for sheet_name in xls.sheet_names}

# Add N4 Unit
df_unit = sheets['LP_UNIT']
df_unit.loc[len(df_unit)] = ['N4-U01', 'N4-CORE', 'N4 Bài 1–22', 'Nhóm bài 1-22 (N4)', 2, 'N4-TONG-HOP', 'Bài 1–22', '']

# Add N4 Lessons
df_lesson = sheets['LP_LESSON']
for num, title in lessons:
    # lesson_code*, unit_code*, source_book_code*, source_lesson_ref*, title*, description, order_index*, estimated_minutes*, lesson_type*, primary_skill*, knowledge_tag_code*, situation_tag, is_review*, status*, can_do_outcome, notes
    df_lesson.loc[len(df_lesson)] = [
        f'N4-L{num:02d}', 'N4-U01', 'N4-TONG-HOP', f'Bài {num}', title, f'Bài {num} N4', num, 45, 'CURRICULUM', 'VOCABULARY', f'JLPT_N4_LESSON_{num:02d}', '', 0, 'DRAFT', '', ''
    ]

with pd.ExcelWriter(excel_path) as writer:
    for sheet_name, df in sheets.items():
        df.to_excel(writer, sheet_name=sheet_name, index=False)

print("Added N4 lessons to the sample Excel file.")
