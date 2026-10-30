import pandas as pd
import os

template_path = r'C:\Users\LAPTOP LENOVO\Downloads\jlas-learning-path-n5-n4-data-template.xlsx'
output_path = r'C:\Users\LAPTOP LENOVO\Downloads\jlas-learning-path-n5-n4-data-sample.xlsx'

# Load all sheets
with pd.ExcelFile(template_path) as xls:
    sheets = {sheet_name: pd.read_excel(xls, sheet_name=sheet_name) for sheet_name in xls.sheet_names}

# 1. Update BOOK_CATALOG
df = sheets['BOOK_CATALOG']
df.loc[len(df)] = ['N5-TONG-HOP', 'SÁCH TỔNG HỢP N5 (170425).pdf', 'N5', 442, 25, 'CORE_CURRICULUM', 'LP_TEXTBOOK; LP_UNIT; LP_LESSON; LESSON_VOCAB; LESSON_GRAMMAR; DIALOGUE; EXERCISE', 'YES', 'Sách N5']
df.loc[len(df)] = ['N4-TONG-HOP', 'SÁCH TỔNG HỢP N4 - Lớp CẤP TỐC.pdf', 'N4', 442, 25, 'CORE_CURRICULUM', 'LP_TEXTBOOK; LP_UNIT; LP_LESSON; LESSON_VOCAB; LESSON_GRAMMAR; DIALOGUE; EXERCISE', 'YES', 'Sách N4']
df.loc[len(df)] = ['N4-KANJI', 'Chữ Hán N4 tài liệu học viên (Cấp tốc).pdf', 'N4', 100, 29, 'SUPPLEMENTARY', 'LESSON_KANJI', 'YES', 'Sách Kanji N4']
df.loc[len(df)] = ['N4-CHOUKAI', 'N4_Choukai Tasuku 25.pdf', 'N4', 100, 25, 'LISTENING_SUPPLEMENT', 'COMPREHENSION', 'YES', 'Nghe hiểu N4']

# 2. Update LP_TEXTBOOK
df = sheets['LP_TEXTBOOK']
df.loc[len(df)] = ['N5-CORE', 'N5-TONG-HOP', 'Giáo trình Learning Path N5', 'Trục 25 bài N5', 'N5', 'DRAFT', 1, 1, 1, 442, '']
df.loc[len(df)] = ['N4-CORE', 'N4-TONG-HOP', 'Giáo trình Learning Path N4', 'Trục 25 bài N4', 'N4', 'DRAFT', 1, 1, 1, 442, '']

# 3. Update LP_UNIT
df = sheets['LP_UNIT']
df.loc[len(df)] = ['N5-U01', 'N5-CORE', 'N5 Bài 1–8', 'Nhóm bài 1-8', 1, 'N5-TONG-HOP', 'Bài 1–8', '']

# 4. Update LP_LESSON
df = sheets['LP_LESSON']
df.loc[len(df)] = ['N5-L01', 'N5-U01', 'N5-TONG-HOP', 'Bài 1', 'Xin chào! Tôi là Dũng Mori', 'Bài 1 N5', 1, 45, 'CURRICULUM', 'VOCABULARY', 'JLPT_N5_LESSON_01', '', 0, 'DRAFT', 'Giới thiệu bản thân', '']
df.loc[len(df)] = ['N5-L02', 'N5-U01', 'N5-TONG-HOP', 'Bài 2', 'Đó là cặp của tôi', 'Bài 2 N5', 2, 45, 'CURRICULUM', 'VOCABULARY', 'JLPT_N5_LESSON_02', '', 0, 'DRAFT', 'Chỉ vật', '']

# 5. Update LESSON_VOCAB
vocab_data = [
    ('わたし', '私', 'tôi'),
    ('わたしたち', '私たち', 'chúng tôi, chúng ta'),
    ('かれ', '彼', 'anh ấy, bạn trai'),
    ('かのじょ', '彼女', 'cô ấy, bạn gái'),
    ('みなさん', '皆さん', 'các anh chị, các ông bà, các bạn, quý vị'),
    ('あなた', '', 'anh/chị, ông/bà, bạn'),
    ('あのひと', 'あの人', 'người đó, anh kia, chị kia'),
    ('きょうし', '教師', 'giáo viên'),
    ('せんせい', '先生', 'thầy/cô'),
    ('がくせい', '学生', 'học sinh')
]
df = sheets['LESSON_VOCAB']
for i, (expr, kanji, meaning) in enumerate(vocab_data):
    # membership_code*, lesson_code*, source_book_code*, source_lesson_ref*, canonical_vocab_id, expression*, reading*, romaji, word_type, han_viet, meaning_vi*, example_ja, example_vi, order_index*, is_core*, canonical_lookup_status*, notes
    df.loc[len(df)] = [f'N5-L01-V{i+1:03d}', 'N5-L01', 'N5-TONG-HOP', 'Bài 1', '', kanji if kanji else expr, expr, '', '', '', meaning, '', '', i+1, 1, 'NEEDS_LOOKUP', '']

# 6. Update LESSON_GRAMMAR
df = sheets['LESSON_GRAMMAR']
grammar_data = [
    ('N1 は N2 です', 'Danh từ + は + Danh từ + です', 'N1 là N2', 'Câu khẳng định danh từ'),
    ('N1 は N2 ではありません/ じゃありません', 'Danh từ + は + Danh từ + ではありません', 'N1 không phải là N2', 'Câu phủ định danh từ'),
    ('N1 は N2 ですか', 'Danh từ + は + Danh từ + ですか', 'N1 có phải là N2 không?', 'Câu nghi vấn'),
    ('N1 は Từ để hỏi ですか', 'Danh từ + は + Từ để hỏi + ですか', 'N1 là cái gì/ai...?', 'Câu hỏi có từ để hỏi'),
    ('N1 も N2 です', 'Danh từ + も + Danh từ + です', 'N1 cũng là N2', 'Trợ từ も'),
    ('N1 の N2', 'Danh từ + の + Danh từ', 'N2 của N1', 'Trợ từ の')
]
for i, (pattern, formula, meaning, context) in enumerate(grammar_data):
    # grammar_code*, lesson_code*, source_book_code*, source_lesson_ref*, canonical_grammar_id, knowledge_tag_code, pattern*, connection_formula, meaning_vi*, context_note, example_ja, example_vi, practice_prompt, order_index*, canonical_lookup_status*, notes
    df.loc[len(df)] = [f'N5-L01-G{i+1:03d}', 'N5-L01', 'N5-TONG-HOP', 'Bài 1', '', '', pattern, formula, meaning, context, '', '', '', i+1, 'NEEDS_LOOKUP', '']

# 7. Update DIALOGUE
df = sheets['DIALOGUE']
dialogue_data = [
    ('A', 'はじめまして、 わたしは むらかみです。', 'Hân hạnh được gặp mặt, tôi là Murakami.'),
    ('B', 'はじめまして、 わたしは しまかわです。', 'Hân hạnh được gặp mặt, tôi là Shimakawa.'),
    ('A', 'どうぞ、 よろしく おねがいします。', 'Rất mong nhận được sự giúp đỡ.'),
    ('B', 'どうぞ、 よろしく おねがいします。', 'Rất mong nhận được sự giúp đỡ.')
]
for i, (speaker, ja, vi) in enumerate(dialogue_data):
    # dialogue_code*, lesson_code, topic_code, destination_scope*, source_book_code*, source_lesson_ref*, title*, turn_index*, speaker*, text_ja*, reading_or_romaji, translation_vi*, register*, is_key_phrase*, can_do_outcome, audio_url, source_page, notes
    df.loc[len(df)] = [f'N5-L01-D001', 'N5-L01', '', 'JLPT_LESSON', 'N5-TONG-HOP', 'Bài 1', 'Kaiwa 1', i+1, speaker, ja, '', vi, 'POLITE', 1, 'Chào hỏi', '', '', '']

with pd.ExcelWriter(output_path) as writer:
    for sheet_name, df in sheets.items():
        df.to_excel(writer, sheet_name=sheet_name, index=False)

print(f"Sample data written to {output_path}")
