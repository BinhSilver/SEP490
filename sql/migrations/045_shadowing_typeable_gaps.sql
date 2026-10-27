-- [JLAS-W0177] Câu Shadowing điền chỗ trống phải gõ được bằng kana/romaji từ âm thanh nghe được.
-- Bộ sinh câu offline (tools/shadowing_seed/expand_questions.py) từng chọn chỗ trống cắt ngang một từ katakana
-- (フェルン → 「ルン」, フリーレン → 「フリー」) hoặc chứa ký hiệu không đọc được (「フリーレン「〇〇の」).
-- Seed đã sinh lại; tệp này sửa đúng 16 câu đổi đáp án và loại 4 câu không còn chỗ trống hợp lệ
-- trên DB đã nhập seed cũ. Mỗi lệnh khớp theo đáp án cũ nên DB mới hoặc đã sửa sẽ không bị đụng; chạy lại an toàn.
-- Câu bị loại chuyển REJECTED (giữ ID cho lịch sử luyện tập, lần nhập seed sau không tạo lại).
SET NAMES utf8mb4;
START TRANSACTION;

-- wbQgGx27AkQ:gap:0036: 「ファースト」 (EASY) → 「じゃあちょっとレディーファーストで。」 (HARD)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=wbQgGx27AkQ','https://youtu.be/wbQgGx27AkQ','https://youtube.com/shorts/wbQgGx27AkQ','https://www.youtube.com/shorts/wbQgGx27AkQ')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=36
SET b.level_code='HARD', b.answer_text='じゃあちょっとレディーファーストで。',
    b.prompt_text='音声の一文を日本語で書いてください。',
    b.explanation='Chép lại toàn bộ câu: 「じゃあちょっとレディーファーストで。」. Nghĩa của câu: Vậy xin mời bạn nữ trả lời trước.',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','HARD','|','音声の一文を日本語で書いてください。','|','じゃあちょっとレディーファーストで。','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'ファースト';

-- wbQgGx27AkQ:gap:0138: 「アラビアン」 (EASY) → 「感じ」 (EASY)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=wbQgGx27AkQ','https://youtu.be/wbQgGx27AkQ','https://youtube.com/shorts/wbQgGx27AkQ','https://www.youtube.com/shorts/wbQgGx27AkQ')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=138
SET b.level_code='EASY', b.answer_text='感じ',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'アラビアンナイトみたいな＿＿＿で、'),
    b.explanation='Cụm cần nghe và điền: 「感じ」. Nghĩa của câu: Nó mang màu sắc Nghìn lẻ một đêm,',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','EASY','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'アラビアンナイトみたいな＿＿＿で、'),'|','感じ','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'アラビアン';

-- z6O3dfDSHmA:gap:0066: 「シャー」 (EASY) → 「いい」 (EASY)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=z6O3dfDSHmA','https://youtu.be/z6O3dfDSHmA','https://youtube.com/shorts/z6O3dfDSHmA','https://www.youtube.com/shorts/z6O3dfDSHmA')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=66
SET b.level_code='EASY', b.answer_text='いい',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'シャーペンで＿＿＿っすか?'),
    b.explanation='Cụm cần nghe và điền: 「いい」. Nghĩa của câu: Bút chì kim được không ạ?',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','EASY','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'シャーペンで＿＿＿っすか?'),'|','いい','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'シャー';

-- -dUOzVp6-Y0:gap:0192: 「カスタム」 (EASY) → 「マーク」 (EASY)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=-dUOzVp6-Y0','https://youtu.be/-dUOzVp6-Y0','https://youtube.com/shorts/-dUOzVp6-Y0','https://www.youtube.com/shorts/-dUOzVp6-Y0')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=192
SET b.level_code='EASY', b.answer_text='マーク',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'俺はバリカスタム よ649Z＿＿＿II様だ!!'),
    b.explanation='Cụm cần nghe và điền: 「マーク」. Nghĩa của câu: Ta là Baricustom, kiểu 649Z Mark II!',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','EASY','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'俺はバリカスタム よ649Z＿＿＿II様だ!!'),'|','マーク','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'カスタム';

-- XtyYeTm1diw:gap:0006: 「スカイ」 (EASY) → 「つい」 (EASY)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=XtyYeTm1diw','https://youtu.be/XtyYeTm1diw','https://youtube.com/shorts/XtyYeTm1diw','https://www.youtube.com/shorts/XtyYeTm1diw')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=6
SET b.level_code='EASY', b.answer_text='つい',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'1つ目のニュースはスカイツリーに＿＿＿て。'),
    b.explanation='Cụm cần nghe và điền: 「つい」. Nghĩa của câu: Tin thứ nhất là về tháp Skytree.',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','EASY','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'1つ目のニュースはスカイツリーに＿＿＿て。'),'|','つい','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'スカイ';

-- XtyYeTm1diw:gap:0009: 「ツリー」 (EASY) → 「まず」 (EASY)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=XtyYeTm1diw','https://youtu.be/XtyYeTm1diw','https://youtube.com/shorts/XtyYeTm1diw','https://www.youtube.com/shorts/XtyYeTm1diw')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=9
SET b.level_code='EASY', b.answer_text='まず',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'＿＿＿スカイツリーについてです。'),
    b.explanation='Cụm cần nghe và điền: 「まず」. Nghĩa của câu: Trước tiên là tin về tháp Skytree.',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','EASY','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'＿＿＿スカイツリーについてです。'),'|','まず','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'ツリー';

-- XV6g5Y7gNCs:gap:0006: 「ウイルス」 (EASY) → 「うつる」 (EASY)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=XV6g5Y7gNCs','https://youtu.be/XV6g5Y7gNCs','https://youtube.com/shorts/XV6g5Y7gNCs','https://www.youtube.com/shorts/XV6g5Y7gNCs')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=6
SET b.level_code='EASY', b.answer_text='うつる',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'政府は新型コロナウイルスが＿＿＿人が少なくなってきたことや、円が安くなっていることで、旅行に来る人が増えると考えています。'),
    b.explanation='Cụm cần nghe và điền: 「うつる」. Nghĩa của câu: Chính phủ cho rằng số khách du lịch sẽ tăng vì số ca lây nhiễm COVID-19 đang giảm và đồng yên đang mất giá.',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','EASY','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'政府は新型コロナウイルスが＿＿＿人が少なくなってきたことや、円が安くなっていることで、旅行に来る人が増えると考えています。'),'|','うつる','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'ウイルス';

-- P04pWou9oE4:gap:0096: 「キャスト」 (EASY) → 「撮っ」 (EASY)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=P04pWou9oE4','https://youtu.be/P04pWou9oE4','https://youtube.com/shorts/P04pWou9oE4','https://www.youtube.com/shorts/P04pWou9oE4')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=96
SET b.level_code='EASY', b.answer_text='撮っ',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'ポッドキャストを＿＿＿て、'),
    b.explanation='Cụm cần nghe và điền: 「撮っ」. Nghĩa của câu: thu podcast,',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','EASY','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'ポッドキャストを＿＿＿て、'),'|','撮っ','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'キャスト';

-- 7VCvA0DjkLc:gap:0084: 「ショット」 (EASY) → 「白銀」 (EASY)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=7VCvA0DjkLc','https://youtu.be/7VCvA0DjkLc','https://youtube.com/shorts/7VCvA0DjkLc','https://www.youtube.com/shorts/7VCvA0DjkLc')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=84
SET b.level_code='EASY', b.answer_text='白銀',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'＿＿＿君と四宮さんのツーショットが撮りたい。'),
    b.explanation='Cụm cần nghe và điền: 「白銀」. Nghĩa của câu: Tôi muốn chụp ảnh riêng Shirogane và Shinomiya đứng cạnh nhau.',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','EASY','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'＿＿＿君と四宮さんのツーショットが撮りたい。'),'|','白銀','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'ショット';

-- 7VCvA0DjkLc:gap:0108: 「白銀」 (EASY) → 「御行」 (EASY)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=7VCvA0DjkLc','https://youtu.be/7VCvA0DjkLc','https://youtube.com/shorts/7VCvA0DjkLc','https://www.youtube.com/shorts/7VCvA0DjkLc')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=108
SET b.level_code='EASY', b.answer_text='御行',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'私は白銀＿＿＿が好き。'),
    b.explanation='Cụm cần nghe và điền: 「御行」. Nghĩa của câu: Tôi thích Shirogane Miyuki.',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','EASY','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'私は白銀＿＿＿が好き。'),'|','御行','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY '白銀';

-- CEqNH6KZ4yQ:gap:0008: 「オール」 (EASY) → 「勝て」 (EASY)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=CEqNH6KZ4yQ','https://youtu.be/CEqNH6KZ4yQ','https://youtube.com/shorts/CEqNH6KZ4yQ','https://www.youtube.com/shorts/CEqNH6KZ4yQ')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=8
SET b.level_code='EASY', b.answer_text='勝て',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'オールマイト!＿＿＿!'),
    b.explanation='Cụm cần nghe và điền: 「勝て」. Nghĩa của câu: All Might! Thắng đi!',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','EASY','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'オールマイト!＿＿＿!'),'|','勝て','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'オール';

-- CEqNH6KZ4yQ:gap:0012: 「フォー」 (EASY) → 「オール」 (EASY)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=CEqNH6KZ4yQ','https://youtu.be/CEqNH6KZ4yQ','https://youtube.com/shorts/CEqNH6KZ4yQ','https://www.youtube.com/shorts/CEqNH6KZ4yQ')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=12
SET b.level_code='EASY', b.answer_text='オール',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'＿＿＿・フォー・ワン。'),
    b.explanation='Cụm cần nghe và điền: 「オール」. Nghĩa của câu: All For One ạ.',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','EASY','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'＿＿＿・フォー・ワン。'),'|','オール','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'フォー';

-- lx4CQ-4hEVM:gap:0001: 「フリーレン「〇〇の」 (MEDIUM) → 「フリーレン」 (MEDIUM)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=lx4CQ-4hEVM','https://youtu.be/lx4CQ-4hEVM','https://youtube.com/shorts/lx4CQ-4hEVM','https://www.youtube.com/shorts/lx4CQ-4hEVM')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=1
SET b.level_code='MEDIUM', b.answer_text='フリーレン',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'葬送の＿＿＿＿＿「〇〇の魔法」。'),
    b.explanation='Cụm cần nghe và điền: 「フリーレン」. Nghĩa của câu: Frieren: Các phép thuật.',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','MEDIUM','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'葬送の＿＿＿＿＿「〇〇の魔法」。'),'|','フリーレン','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='MEDIUM' AND BINARY b.answer_text=BINARY 'フリーレン「〇〇の';

-- X3Y9esqDspY:gap:0001: 「フリーレン「〇〇の」 (MEDIUM) → 「フリーレン」 (MEDIUM)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=X3Y9esqDspY','https://youtu.be/X3Y9esqDspY','https://youtube.com/shorts/X3Y9esqDspY','https://www.youtube.com/shorts/X3Y9esqDspY')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=1
SET b.level_code='MEDIUM', b.answer_text='フリーレン',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'葬送の＿＿＿＿＿「〇〇の魔法」。'),
    b.explanation='Cụm cần nghe và điền: 「フリーレン」. Nghĩa của câu: Frieren: Các phép thuật.',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','MEDIUM','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'葬送の＿＿＿＿＿「〇〇の魔法」。'),'|','フリーレン','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='MEDIUM' AND BINARY b.answer_text=BINARY 'フリーレン「〇〇の';

-- venWKTSh3cs:gap:0001: 「フリーレン「〇〇の」 (MEDIUM) → 「フリーレン」 (MEDIUM)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=venWKTSh3cs','https://youtu.be/venWKTSh3cs','https://youtube.com/shorts/venWKTSh3cs','https://www.youtube.com/shorts/venWKTSh3cs')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=1
SET b.level_code='MEDIUM', b.answer_text='フリーレン',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'葬送の＿＿＿＿＿「〇〇の魔法」。'),
    b.explanation='Cụm cần nghe và điền: 「フリーレン」. Nghĩa của câu: Frieren: Các phép thuật.',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','MEDIUM','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'葬送の＿＿＿＿＿「〇〇の魔法」。'),'|','フリーレン','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='MEDIUM' AND BINARY b.answer_text=BINARY 'フリーレン「〇〇の';

-- venWKTSh3cs:gap:0021: 「フリー」 (EASY) → 「魔法」 (EASY)
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=venWKTSh3cs','https://youtu.be/venWKTSh3cs','https://youtube.com/shorts/venWKTSh3cs','https://www.youtube.com/shorts/venWKTSh3cs')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=21
SET b.level_code='EASY', b.answer_text='魔法',
    b.prompt_text=CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'フリーレンが＿＿＿で牽制しながら'),
    b.explanation='Cụm cần nghe và điền: 「魔法」. Nghĩa của câu: Frieren dùng phép kiềm chế đối thủ,',
    b.content_hash=SHA2(CONCAT('listening-v3-evidence|GAP|','EASY','|',CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),'フリーレンが＿＿＿で牽制しながら'),'|','魔法','|',b.source_hash),256)
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'フリー';

-- -dUOzVp6-Y0:gap:0022: 「マン」 không còn chỗ trống hợp lệ
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=-dUOzVp6-Y0','https://youtu.be/-dUOzVp6-Y0','https://youtube.com/shorts/-dUOzVp6-Y0','https://www.youtube.com/shorts/-dUOzVp6-Y0')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=22
SET b.status='REJECTED'
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'マン';

-- -dUOzVp6-Y0:gap:0079: 「ボール」 không còn chỗ trống hợp lệ
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=-dUOzVp6-Y0','https://youtu.be/-dUOzVp6-Y0','https://youtube.com/shorts/-dUOzVp6-Y0','https://www.youtube.com/shorts/-dUOzVp6-Y0')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=79
SET b.status='REJECTED'
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'ボール';

-- Hi16mimGe0I:gap:0008: 「レター」 không còn chỗ trống hợp lệ
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=Hi16mimGe0I','https://youtu.be/Hi16mimGe0I','https://youtube.com/shorts/Hi16mimGe0I','https://www.youtube.com/shorts/Hi16mimGe0I')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=8
SET b.status='REJECTED'
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'レター';

-- lx4CQ-4hEVM:gap:0011: 「ルン」 không còn chỗ trống hợp lệ
UPDATE shadowing_question_bank b
JOIN video v ON v.id=b.video_id AND v.source_url IN ('https://www.youtube.com/watch?v=lx4CQ-4hEVM','https://youtu.be/lx4CQ-4hEVM','https://youtube.com/shorts/lx4CQ-4hEVM','https://www.youtube.com/shorts/lx4CQ-4hEVM')
JOIN subtitle a ON a.id=b.subtitle_id AND a.video_id=b.video_id AND a.sort_order=11
SET b.status='REJECTED'
WHERE b.kind='GAP' AND b.status<>'REJECTED' AND b.level_code='EASY' AND BINARY b.answer_text=BINARY 'ルン';

-- Bản phân tích offline của các video này liệt kê đúng các câu đang dùng, như khi nhập seed.
UPDATE shadowing_preparation_job j
JOIN (
    SELECT b.video_id,
        JSON_ARRAYAGG(JSON_OBJECT('kind',b.kind,'sourceIds',CAST(b.source_ids_json AS JSON),'focus',b.prompt_text,
            'answerQuote',b.answer_text,'contextIds',JSON_ARRAY(),
            'contextSummary',CONCAT('OFFLINE_AUTHORING: ',b.explanation))) plans_json,
        CONCAT_WS(',',IF(SUM(b.level_code='EASY')>0,'EASY',NULL),IF(SUM(b.level_code='MEDIUM')>0,'MEDIUM',NULL),
            IF(SUM(b.level_code='HARD')>0,'HARD',NULL)) levels_csv
    FROM shadowing_question_bank b JOIN video v ON v.id=b.video_id
    WHERE b.status='READY' AND ((v.source_url IN ('https://www.youtube.com/watch?v=-dUOzVp6-Y0','https://youtu.be/-dUOzVp6-Y0','https://youtube.com/shorts/-dUOzVp6-Y0','https://www.youtube.com/shorts/-dUOzVp6-Y0')) OR (v.source_url IN ('https://www.youtube.com/watch?v=7VCvA0DjkLc','https://youtu.be/7VCvA0DjkLc','https://youtube.com/shorts/7VCvA0DjkLc','https://www.youtube.com/shorts/7VCvA0DjkLc')) OR (v.source_url IN ('https://www.youtube.com/watch?v=CEqNH6KZ4yQ','https://youtu.be/CEqNH6KZ4yQ','https://youtube.com/shorts/CEqNH6KZ4yQ','https://www.youtube.com/shorts/CEqNH6KZ4yQ')) OR (v.source_url IN ('https://www.youtube.com/watch?v=Hi16mimGe0I','https://youtu.be/Hi16mimGe0I','https://youtube.com/shorts/Hi16mimGe0I','https://www.youtube.com/shorts/Hi16mimGe0I')) OR (v.source_url IN ('https://www.youtube.com/watch?v=P04pWou9oE4','https://youtu.be/P04pWou9oE4','https://youtube.com/shorts/P04pWou9oE4','https://www.youtube.com/shorts/P04pWou9oE4')) OR (v.source_url IN ('https://www.youtube.com/watch?v=X3Y9esqDspY','https://youtu.be/X3Y9esqDspY','https://youtube.com/shorts/X3Y9esqDspY','https://www.youtube.com/shorts/X3Y9esqDspY')) OR (v.source_url IN ('https://www.youtube.com/watch?v=XV6g5Y7gNCs','https://youtu.be/XV6g5Y7gNCs','https://youtube.com/shorts/XV6g5Y7gNCs','https://www.youtube.com/shorts/XV6g5Y7gNCs')) OR (v.source_url IN ('https://www.youtube.com/watch?v=XtyYeTm1diw','https://youtu.be/XtyYeTm1diw','https://youtube.com/shorts/XtyYeTm1diw','https://www.youtube.com/shorts/XtyYeTm1diw')) OR (v.source_url IN ('https://www.youtube.com/watch?v=lx4CQ-4hEVM','https://youtu.be/lx4CQ-4hEVM','https://youtube.com/shorts/lx4CQ-4hEVM','https://www.youtube.com/shorts/lx4CQ-4hEVM')) OR (v.source_url IN ('https://www.youtube.com/watch?v=venWKTSh3cs','https://youtu.be/venWKTSh3cs','https://youtube.com/shorts/venWKTSh3cs','https://www.youtube.com/shorts/venWKTSh3cs')) OR (v.source_url IN ('https://www.youtube.com/watch?v=wbQgGx27AkQ','https://youtu.be/wbQgGx27AkQ','https://youtube.com/shorts/wbQgGx27AkQ','https://www.youtube.com/shorts/wbQgGx27AkQ')) OR (v.source_url IN ('https://www.youtube.com/watch?v=z6O3dfDSHmA','https://youtu.be/z6O3dfDSHmA','https://youtube.com/shorts/z6O3dfDSHmA','https://www.youtube.com/shorts/z6O3dfDSHmA')))
    GROUP BY b.video_id
) p ON p.video_id=j.video_id
JOIN (
    SELECT c.video_id, GROUP_CONCAT(CONCAT(c.kind,':',c.n) ORDER BY c.kind SEPARATOR ',') targets_csv
    FROM (SELECT video_id, kind, COUNT(*) n FROM shadowing_question_bank WHERE status='READY'
          GROUP BY video_id, kind) c GROUP BY c.video_id
) t ON t.video_id=j.video_id
SET j.evidence_plans_json=p.plans_json, j.levels_csv=p.levels_csv, j.targets_csv=t.targets_csv,
    j.capacities_csv=t.targets_csv
WHERE j.status NOT IN ('RUNNING','QUEUED') AND j.analysis_version='evidence-plans-v1'
  AND JSON_LENGTH(j.evidence_plans_json)>0
  AND NOT EXISTS (SELECT 1 FROM JSON_TABLE(j.evidence_plans_json,'$[*]' COLUMNS (
      summary TEXT PATH '$.contextSummary')) old_plan
      WHERE old_plan.summary IS NULL OR old_plan.summary NOT LIKE 'OFFLINE_AUTHORING:%')
  AND NOT EXISTS (SELECT 1 FROM shadowing_question_bank x WHERE x.video_id=j.video_id AND x.status='DRAFT');

COMMIT;
