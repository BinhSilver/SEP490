-- ============================================================================
-- SEEDSQLFLASHCARD: 10 bộ flashcard, tổng cộng 340 từ tiếng Nhật.
-- Số thẻ từng bộ: 20, 25, 30, 40, 45, 50, 55, 20, 25, 30 (trung bình 34 từ/bộ).
--
-- Cách chạy (MySQL 8.0.16+):
--   1. Chọn database đã chạy jlas_schema.sql và đã có tài khoản người dùng.
--   2. Sửa email mặc định bên dưới thành email tài khoản sở hữu các bộ thẻ.
--      Hoặc SET @flashcard_seed_owner_email = 'email-cua-ban'; trước khi SOURCE.
--   3. Chạy toàn bộ file, bao gồm CREATE PROCEDURE / CALL / DROP PROCEDURE.
--      mysql -u root -p --default-character-set=utf8mb4 ten_database < sql/seeds/seedsqlflashcard.sql
--      Trong MySQL Workbench: chọn schema, mở file và Execute All.
--
-- Dữ liệu được nhúng trực tiếp từ các JSON sẵn có; không cần nạp seed từ điển.
-- Mặt trước: từ tiếng Nhật. Mặt sau: cách đọc + nghĩa tiếng Việt.
-- Có câu ví dụ nếu nguồn có; không tự đặt ví dụ hay cấp JLPT cho từ chuyên đề.
-- N5/N4 lấy theo giáo trình nguồn; từ du lịch/đời sống/IT dùng CUSTOM.
-- Các từ không trùng cặp (từ, cách đọc) sau NFKC và katakana -> hiragana.
-- Ví dụ của 早い dùng từ 早く trong N5 bai-04.json để minh họa nghĩa "sớm".
--
-- Chạy lại tuần tự: chỉ thêm bộ/thẻ còn thiếu theo marker và vị trí, giữ nguyên
-- ID, nội dung đã sửa, tiến độ, lịch sử học, đánh giá và bộ thẻ khác.
-- Marker nhận diện: moderation_summary = SEEDSQLFLASHCARD_10_01 ... _10.
-- Giữ marker nếu muốn chạy lại; không chạy đồng thời file này ở nhiều cửa sổ.
-- Bộ được công khai (PUBLIC), không phải bản nháp, trạng thái kiểm duyệt SAFE.
-- Không tạo tài khoản hoặc dữ liệu tiến độ giả; thẻ mới bắt đầu ở trạng thái NEW.
--
-- Nguồn: data/seed_jlas/sekai/sekai-n5/json/, sekai-n4/json/;
--        data/seed_jlas/{travel,daily,it}_data.json.
-- Dữ liệu giáo trình do Sekai soạn; giữ thông tin nguồn/bản quyền trong JSON.
-- Các gói chuyên đề ghi nhận đối chiếu cách đọc với EDRDG/JMdict (CC BY-SA 4.0):
-- https://www.edrdg.org/edrdg/licence.html
-- ============================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET @flashcard_seed_owner_email = COALESCE(
    NULLIF(TRIM(@flashcard_seed_owner_email), ''),
    'demo@jlab.local'
);

DROP PROCEDURE IF EXISTS _seed_seedsqlflashcard_10;
DELIMITER $$
CREATE PROCEDURE _seed_seedsqlflashcard_10()
BEGIN
    DECLARE v_owner_id BIGINT UNSIGNED;
    DECLARE v_folder_id BIGINT UNSIGNED;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        DROP TEMPORARY TABLE IF EXISTS tmp_seedsqlflashcard_cards;
        DROP TEMPORARY TABLE IF EXISTS tmp_seedsqlflashcard_sets;
        RESIGNAL;
    END;

    SELECT MAX(id) INTO v_owner_id
    FROM users
    WHERE email COLLATE utf8mb4_unicode_ci =
          @flashcard_seed_owner_email COLLATE utf8mb4_unicode_ci;

    IF v_owner_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Khong tim thay tai khoan. Sua @flashcard_seed_owner_email thanh email da ton tai.';
    END IF;

    DROP TEMPORARY TABLE IF EXISTS tmp_seedsqlflashcard_cards;
    DROP TEMPORARY TABLE IF EXISTS tmp_seedsqlflashcard_sets;

    CREATE TEMPORARY TABLE tmp_seedsqlflashcard_sets (
        seed_key VARCHAR(64) NOT NULL PRIMARY KEY,
        title VARCHAR(100) NOT NULL,
        description VARCHAR(500) NOT NULL,
        expected_count INT UNSIGNED NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    CREATE TEMPORARY TABLE tmp_seedsqlflashcard_cards (
        seed_key VARCHAR(64) NOT NULL,
        position INT UNSIGNED NOT NULL,
        front VARCHAR(500) NOT NULL,
        reading VARCHAR(255) NOT NULL,
        meaning VARCHAR(500) NOT NULL,
        jlpt_level VARCHAR(10) NOT NULL,
        example_ja TEXT NULL,
        example_vi TEXT NULL,
        PRIMARY KEY (seed_key, position)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    INSERT INTO tmp_seedsqlflashcard_sets (seed_key, title, description, expected_count) VALUES
        ('SEEDSQLFLASHCARD_10_01', 'Giới thiệu bản thân và nghề nghiệp', 'Từ vựng về giới thiệu bản thân và nghề nghiệp. Có cách đọc và nghĩa tiếng Việt. Nguồn: bai-01.json.', 20),
        ('SEEDSQLFLASHCARD_10_02', 'Đồ dùng học tập và vật dụng hằng ngày', 'Từ vựng về đồ dùng học tập và vật dụng hằng ngày. Có cách đọc và nghĩa tiếng Việt. Nguồn: bai-02.json.', 25),
        ('SEEDSQLFLASHCARD_10_03', 'Thời gian và sinh hoạt hằng ngày', 'Từ vựng về thời gian và sinh hoạt hằng ngày. Có cách đọc và nghĩa tiếng Việt. Nguồn: bai-04.json.', 30),
        ('SEEDSQLFLASHCARD_10_04', 'Đi lại và phương tiện giao thông', 'Từ vựng về đi lại và phương tiện giao thông. Có cách đọc và nghĩa tiếng Việt. Nguồn: bai-05.json, travel_data.json.', 40),
        ('SEEDSQLFLASHCARD_10_05', 'Ăn uống, sinh hoạt và mua sắm', 'Từ vựng về ăn uống, sinh hoạt và mua sắm. Có cách đọc và nghĩa tiếng Việt. Nguồn: bai-06.json, daily_data.json.', 45),
        ('SEEDSQLFLASHCARD_10_06', 'Miêu tả sự vật và so sánh', 'Từ vựng về miêu tả sự vật và so sánh. Có cách đọc và nghĩa tiếng Việt. Nguồn: bai-08.json, bai-12.json.', 50),
        ('SEEDSQLFLASHCARD_10_07', 'Sức khỏe, sự cố và thủ tục hằng ngày', 'Từ vựng về sức khỏe, sự cố và thủ tục hằng ngày. Có cách đọc và nghĩa tiếng Việt. Nguồn: bai-26.json, bai-32.json.', 55),
        ('SEEDSQLFLASHCARD_10_08', 'Du lịch: sân bay và tàu điện', 'Từ vựng về du lịch: sân bay và tàu điện. Có cách đọc và nghĩa tiếng Việt. Nguồn: travel_data.json.', 20),
        ('SEEDSQLFLASHCARD_10_09', 'Đời sống: nhà ở và thủ tục hành chính', 'Từ vựng về đời sống: nhà ở và thủ tục hành chính. Có cách đọc và nghĩa tiếng Việt. Nguồn: daily_data.json.', 25),
        ('SEEDSQLFLASHCARD_10_10', 'IT: lập trình và phát triển phần mềm', 'Từ vựng IT: lập trình và phát triển phần mềm. Có cách đọc và nghĩa tiếng Việt. Nguồn: it_data.json.', 30);

    -- Bộ 01: Giới thiệu bản thân và nghề nghiệp - 20 từ.
    -- Nguồn: data/seed_jlas/sekai/sekai-n5/json/bai-01.json
    INSERT INTO tmp_seedsqlflashcard_cards
        (seed_key, position, front, reading, meaning, jlpt_level, example_ja, example_vi) VALUES
        ('SEEDSQLFLASHCARD_10_01', 1, '私', 'わたし', 'tôi', 'N5', 'わたしは グエンです。', 'Tôi là Nguyễn.'),
        ('SEEDSQLFLASHCARD_10_01', 2, 'あなた', 'あなた', 'bạn, anh, chị', 'N5', 'あなたは がくせいですか。', 'Bạn là sinh viên phải không?'),
        ('SEEDSQLFLASHCARD_10_01', 3, '先生', 'せんせい', 'giáo viên, thầy cô', 'N5', 'やまださんは にほんごの せんせいです。', 'Chị Yamada là giáo viên tiếng Nhật.'),
        ('SEEDSQLFLASHCARD_10_01', 4, '学生', 'がくせい', 'học sinh, sinh viên', 'N5', 'わたしは だいがくの がくせいです。', 'Tôi là sinh viên đại học.'),
        ('SEEDSQLFLASHCARD_10_01', 5, '会社員', 'かいしゃいん', 'nhân viên công ty', 'N5', 'ちちは かいしゃいんです。', 'Bố tôi là nhân viên công ty.'),
        ('SEEDSQLFLASHCARD_10_01', 6, '銀行員', 'ぎんこういん', 'nhân viên ngân hàng', 'N5', 'あねは ぎんこういんです。', 'Chị tôi là nhân viên ngân hàng.'),
        ('SEEDSQLFLASHCARD_10_01', 7, '医者', 'いしゃ', 'bác sĩ', 'N5', 'リンさんは いしゃですか。', 'Chị Linh là bác sĩ phải không?'),
        ('SEEDSQLFLASHCARD_10_01', 8, '研究者', 'けんきゅうしゃ', 'người làm nghiên cứu', 'N5', 'かれは だいがくの けんきゅうしゃです。', 'Anh ấy là người làm nghiên cứu ở trường đại học.'),
        ('SEEDSQLFLASHCARD_10_01', 9, '会社', 'かいしゃ', 'công ty', 'N5', 'わたしの かいしゃは とうきょうです。', 'Công ty của tôi ở Tokyo.'),
        ('SEEDSQLFLASHCARD_10_01', 10, '大学', 'だいがく', 'trường đại học', 'N5', 'この だいがくは ゆうめいです。', 'Trường đại học này nổi tiếng.'),
        ('SEEDSQLFLASHCARD_10_01', 11, '病院', 'びょういん', 'bệnh viện', 'N5', 'はははびょういんの かんごしです。', 'Mẹ tôi là y tá ở bệnh viện.'),
        ('SEEDSQLFLASHCARD_10_01', 12, '名前', 'なまえ', 'tên', 'N5', 'おなまえは。', 'Bạn tên là gì?'),
        ('SEEDSQLFLASHCARD_10_01', 13, '国', 'くに', 'nước, quốc gia', 'N5', 'おくには どちらですか。', 'Bạn ở nước nào?'),
        ('SEEDSQLFLASHCARD_10_01', 14, '日本', 'にほん', 'Nhật Bản', 'N5', 'やまださんは にほんじんです。', 'Chị Yamada là người Nhật.'),
        ('SEEDSQLFLASHCARD_10_01', 15, 'ベトナム', 'ベトナム', 'Việt Nam', 'N5', 'わたしは ベトナムじんです。', 'Tôi là người Việt Nam.'),
        ('SEEDSQLFLASHCARD_10_01', 16, 'アメリカ', 'アメリカ', 'Mỹ', 'N5', 'マイクさんは アメリカじんです。', 'Anh Mike là người Mỹ.'),
        ('SEEDSQLFLASHCARD_10_01', 17, '韓国', 'かんこく', 'Hàn Quốc', 'N5', 'キムさんも かんこくじんです。', 'Anh Kim cũng là người Hàn Quốc.'),
        ('SEEDSQLFLASHCARD_10_01', 18, '中国', 'ちゅうごく', 'Trung Quốc', 'N5', 'ワンさんは ちゅうごくの けんきゅうしゃです。', 'Anh Vương là người làm nghiên cứu ở Trung Quốc.'),
        ('SEEDSQLFLASHCARD_10_01', 19, '誰', 'だれ', 'ai', 'N5', 'あのひとは だれですか。', 'Người kia là ai vậy?'),
        ('SEEDSQLFLASHCARD_10_01', 20, '何歳', 'なんさい', 'mấy tuổi, bao nhiêu tuổi', 'N5', 'おこさんは なんさいですか。', 'Con của anh mấy tuổi rồi?');

    -- Bộ 02: Đồ dùng học tập và vật dụng hằng ngày - 25 từ.
    -- Nguồn: data/seed_jlas/sekai/sekai-n5/json/bai-02.json
    INSERT INTO tmp_seedsqlflashcard_cards
        (seed_key, position, front, reading, meaning, jlpt_level, example_ja, example_vi) VALUES
        ('SEEDSQLFLASHCARD_10_02', 1, 'これ', 'これ', 'cái này (thứ ở gần người nói)', 'N5', 'これは ほん です。', 'Cái này là sách.'),
        ('SEEDSQLFLASHCARD_10_02', 2, 'それ', 'それ', 'cái đó (thứ ở gần người nghe)', 'N5', 'それは ノート ですか。', 'Cái đó có phải là vở không?'),
        ('SEEDSQLFLASHCARD_10_02', 3, 'あれ', 'あれ', 'cái kia (thứ ở xa cả hai)', 'N5', 'あれは がっこう です。', 'Cái kia là trường học.'),
        ('SEEDSQLFLASHCARD_10_02', 4, 'どれ', 'どれ', 'cái nào', 'N5', 'グエンさんの かばんは どれですか。', 'Cặp của anh Nguyễn là cái nào?'),
        ('SEEDSQLFLASHCARD_10_02', 5, '何', 'なん', 'cái gì', 'N5', 'それは なんですか。', 'Cái đó là cái gì vậy?'),
        ('SEEDSQLFLASHCARD_10_02', 6, 'ちょっと', 'ちょっと', 'một chút, hơi', 'N5', 'あ、ちょっと。これは わたしの じゃ ありません。', 'À khoan. Cái này không phải của tôi.'),
        ('SEEDSQLFLASHCARD_10_02', 7, '本', 'ほん', 'sách', 'N5', 'これは にほんご の ほん です。', 'Cái này là sách tiếng Nhật.'),
        ('SEEDSQLFLASHCARD_10_02', 8, '辞書', 'じしょ', 'từ điển', 'N5', 'この じしょは だれ の じしょ ですか。', 'Cuốn từ điển này là từ điển của ai?'),
        ('SEEDSQLFLASHCARD_10_02', 9, '雑誌', 'ざっし', 'tạp chí', 'N5', 'これは にほんご の ざっし です。', 'Cái này là tạp chí tiếng Nhật.'),
        ('SEEDSQLFLASHCARD_10_02', 10, '新聞', 'しんぶん', 'báo', 'N5', 'これは きょう の しんぶん です。', 'Cái này là báo hôm nay.'),
        ('SEEDSQLFLASHCARD_10_02', 11, 'ノート', 'ノート', 'vở, sổ ghi', 'N5', 'この ノートは あたらしい です。', 'Cuốn vở này mới.'),
        ('SEEDSQLFLASHCARD_10_02', 12, '鉛筆', 'えんぴつ', 'bút chì', 'N5', 'これは えんぴつ じゃ ありません。', 'Cái này không phải là bút chì.'),
        ('SEEDSQLFLASHCARD_10_02', 13, 'ボールペン', 'ボールペン', 'bút bi', 'N5', 'この ボールペンは あたらしい です。', 'Cây bút bi này mới.'),
        ('SEEDSQLFLASHCARD_10_02', 14, '手帳', 'てちょう', 'sổ tay', 'N5', 'この てちょうは あたらしい です。', 'Cuốn sổ tay này mới.'),
        ('SEEDSQLFLASHCARD_10_02', 15, 'かばん', 'かばん', 'cặp, túi xách', 'N5', 'この かばんは あたらしい です。', 'Cái túi xách này mới.'),
        ('SEEDSQLFLASHCARD_10_02', 16, '鍵', 'かぎ', 'chìa khoá', 'N5', 'これは がっこう の かぎ です。', 'Cái này là chìa khóa của trường.'),
        ('SEEDSQLFLASHCARD_10_02', 17, '時計', 'とけい', 'đồng hồ', 'N5', 'その とけいは わたし の です。', 'Chiếc đồng hồ đó là của tôi.'),
        ('SEEDSQLFLASHCARD_10_02', 18, '傘', 'かさ', 'ô, dù', 'N5', 'これは かさ じゃ ありません。', 'Cái này không phải là ô.'),
        ('SEEDSQLFLASHCARD_10_02', 19, '靴', 'くつ', 'giày', 'N5', 'これは がっこう の くつ です。', 'Cái này là giày của trường.'),
        ('SEEDSQLFLASHCARD_10_02', 20, 'お金', 'おかね', 'tiền', 'N5', 'これは わたし の おかね です。', 'Cái này là tiền của tôi.'),
        ('SEEDSQLFLASHCARD_10_02', 21, '電話', 'でんわ', 'điện thoại', 'N5', 'この でんわは あたらしい です。', 'Chiếc điện thoại này mới.'),
        ('SEEDSQLFLASHCARD_10_02', 22, 'カメラ', 'カメラ', 'máy ảnh', 'N5', 'この カメラは あたらしい です。', 'Chiếc máy ảnh này mới.'),
        ('SEEDSQLFLASHCARD_10_02', 23, '机', 'つくえ', 'bàn học, bàn làm việc', 'N5', 'これは がっこう の つくえ です。', 'Cái này là bàn của trường.'),
        ('SEEDSQLFLASHCARD_10_02', 24, '椅子', 'いす', 'ghế', 'N5', 'この いすは あたらしい です。', 'Chiếc ghế này mới.'),
        ('SEEDSQLFLASHCARD_10_02', 25, 'テレビ', 'テレビ', 'ti vi', 'N5', 'これは がっこう の テレビ です。', 'Cái này là tivi của trường.');

    -- Bộ 03: Thời gian và sinh hoạt hằng ngày - 30 từ.
    -- Nguồn: data/seed_jlas/sekai/sekai-n5/json/bai-04.json
    INSERT INTO tmp_seedsqlflashcard_cards
        (seed_key, position, front, reading, meaning, jlpt_level, example_ja, example_vi) VALUES
        ('SEEDSQLFLASHCARD_10_03', 1, '朝', 'あさ', 'buổi sáng', 'N5', 'あさの じゅぎょうは くじからです。', 'Tiết học buổi sáng bắt đầu từ 9 giờ.'),
        ('SEEDSQLFLASHCARD_10_03', 2, '昼', 'ひる', 'buổi trưa', 'N5', 'ひるの やすみは じゅうにじからです。', 'Giờ nghỉ trưa bắt đầu từ 12 giờ.'),
        ('SEEDSQLFLASHCARD_10_03', 3, '夜', 'よる', 'buổi tối', 'N5', 'よるの べんきょうは はちじからです。', 'Việc học buổi tối từ 8 giờ.'),
        ('SEEDSQLFLASHCARD_10_03', 4, '毎日', 'まいにち', 'hằng ngày', 'N5', 'まいにち べんきょうしますか。', 'Hằng ngày bạn có học bài không?'),
        ('SEEDSQLFLASHCARD_10_03', 5, '昨日', 'きのう', 'hôm qua', 'N5', 'きのう はたらきませんでした。', 'Hôm qua tôi đã không làm việc.'),
        ('SEEDSQLFLASHCARD_10_03', 6, '明日', 'あした', 'ngày mai', 'N5', 'あした はたらきますか。', 'Ngày mai bạn có làm việc không?'),
        ('SEEDSQLFLASHCARD_10_03', 7, '何時', 'なんじ', 'mấy giờ', 'N5', 'ひるやすみは なんじ ですか。', 'Giờ nghỉ trưa là mấy giờ?'),
        ('SEEDSQLFLASHCARD_10_03', 8, '何曜日', 'なんようび', 'thứ mấy', 'N5', 'かいぎは なんようび ですか。', 'Cuộc họp vào thứ mấy ạ?'),
        ('SEEDSQLFLASHCARD_10_03', 9, '起きます', 'おきます', 'thức dậy', 'N5', 'あさ ろくじに おきますか。', 'Buổi sáng bạn thức dậy lúc 6 giờ phải không?'),
        ('SEEDSQLFLASHCARD_10_03', 10, '寝ます', 'ねます', 'đi ngủ', 'N5', 'まいにち じゅうじに ねますか。', 'Hằng ngày bạn đi ngủ lúc 10 giờ phải không?'),
        ('SEEDSQLFLASHCARD_10_03', 11, '働きます', 'はたらきます', 'làm việc', 'N5', 'げつようびに はたらきますか。', 'Thứ Hai bạn có làm việc không?'),
        ('SEEDSQLFLASHCARD_10_03', 12, '休みます', 'やすみます', 'nghỉ', 'N5', 'どようびに やすみますか。', 'Thứ Bảy bạn có nghỉ không?'),
        ('SEEDSQLFLASHCARD_10_03', 13, '始まります', 'はじまります', 'bắt đầu', 'N5', 'じゅぎょうは くじに はじまりますか。', 'Tiết học bắt đầu lúc 9 giờ phải không?'),
        ('SEEDSQLFLASHCARD_10_03', 14, '終わります', 'おわります', 'kết thúc', 'N5', 'しごとは ごじに おわりますか。', 'Công việc kết thúc lúc 5 giờ phải không?'),
        ('SEEDSQLFLASHCARD_10_03', 15, 'べんきょうします', 'べんきょうします', 'học bài', 'N5', 'よるに べんきょうします。', 'Buổi tối tôi học bài.'),
        ('SEEDSQLFLASHCARD_10_03', 16, '食べます', 'たべます', 'ăn', 'N5', 'しちじに ばんごはんを たべますか。', 'Bạn ăn bữa tối lúc 7 giờ phải không?'),
        ('SEEDSQLFLASHCARD_10_03', 17, '半', 'はん', 'rưỡi', 'N5', 'いまは しちじ はん です。', 'Bây giờ là 7 giờ rưỡi.'),
        ('SEEDSQLFLASHCARD_10_03', 18, '午前', 'ごぜん', 'buổi sáng (trước trưa)', 'N5', 'ごぜんの かいぎは じゅういちじからです。', 'Cuộc họp buổi sáng từ 11 giờ.'),
        ('SEEDSQLFLASHCARD_10_03', 19, '午後', 'ごご', 'buổi chiều (sau trưa)', 'N5', 'ごごの じゅぎょうは にじからです。', 'Tiết học buổi chiều từ 2 giờ.'),
        ('SEEDSQLFLASHCARD_10_03', 20, '今', 'いま', 'bây giờ', 'N5', 'いまは なんじ ですか。', 'Bây giờ là mấy giờ?'),
        ('SEEDSQLFLASHCARD_10_03', 21, '月曜日', 'げつようび', 'thứ hai', 'N5', 'げつようびの かいぎは くじからです。', 'Cuộc họp thứ Hai từ 9 giờ.'),
        ('SEEDSQLFLASHCARD_10_03', 22, '火曜日', 'かようび', 'thứ ba', 'N5', 'かようびの じゅぎょうは じゅういちじからです。', 'Tiết học thứ Ba từ 11 giờ.'),
        ('SEEDSQLFLASHCARD_10_03', 23, '水曜日', 'すいようび', 'thứ tư', 'N5', 'すいようびの ざんぎょうは ありません。', 'Việc tăng ca thứ Tư thì không có.'),
        ('SEEDSQLFLASHCARD_10_03', 24, '木曜日', 'もくようび', 'thứ năm', 'N5', 'もくようびの しごとは よんじまでです。', 'Công việc thứ Năm đến 4 giờ.'),
        ('SEEDSQLFLASHCARD_10_03', 25, '金曜日', 'きんようび', 'thứ sáu', 'N5', 'きんようびの よるは ひまです。', 'Buổi tối thứ Sáu thì rảnh rỗi.'),
        ('SEEDSQLFLASHCARD_10_03', 26, '土曜日', 'どようび', 'thứ bảy', 'N5', 'どようびの やすみは あさからです。', 'Ngày nghỉ thứ Bảy từ buổi sáng.'),
        ('SEEDSQLFLASHCARD_10_03', 27, '日曜日', 'にちようび', 'chủ nhật', 'N5', 'にちようびの ばんごはんは しちじからです。', 'Bữa tối Chủ nhật từ 7 giờ.'),
        ('SEEDSQLFLASHCARD_10_03', 28, '授業', 'じゅぎょう', 'buổi học, tiết học', 'N5', 'じゅぎょうは くじ はん です。', 'Tiết học là 9 giờ rưỡi.'),
        ('SEEDSQLFLASHCARD_10_03', 29, '会議', 'かいぎ', 'cuộc họp', 'N5', 'かいぎは なんじ ですか。', 'Cuộc họp là mấy giờ?'),
        ('SEEDSQLFLASHCARD_10_03', 30, '仕事', 'しごと', 'công việc', 'N5', 'しごとは くじから ごじまで です。', 'Công việc từ 9 giờ đến 5 giờ.');

    -- Bộ 04: Đi lại và phương tiện giao thông - 40 từ.
    -- Nguồn: data/seed_jlas/sekai/sekai-n5/json/bai-05.json
    -- Nguồn: data/seed_jlas/travel_data.json
    INSERT INTO tmp_seedsqlflashcard_cards
        (seed_key, position, front, reading, meaning, jlpt_level, example_ja, example_vi) VALUES
        ('SEEDSQLFLASHCARD_10_04', 1, '行きます', 'いきます', 'đi (tới đâu đó)', 'N5', 'としょかんへ いきます。', 'Tôi đi đến thư viện.'),
        ('SEEDSQLFLASHCARD_10_04', 2, '来ます', 'きます', 'đến (chỗ người nói)', 'N5', 'ともだちが とうきょうへ きます。', 'Bạn tôi đến Tokyo.'),
        ('SEEDSQLFLASHCARD_10_04', 3, '帰ります', 'かえります', 'về (nhà, quê, nước)', 'N5', 'こんしゅう おおさかへ かえります。', 'Tuần này tôi về Osaka.'),
        ('SEEDSQLFLASHCARD_10_04', 4, '出ます', 'でます', 'rời khỏi, ra khỏi', 'N5', 'えきから くうこうまで バスが でます。', 'Có xe buýt chạy từ ga ra sân bay.'),
        ('SEEDSQLFLASHCARD_10_04', 5, '着きます', 'つきます', 'tới nơi', 'N5', 'くうこうに つきます。', 'Tôi tới sân bay.'),
        ('SEEDSQLFLASHCARD_10_04', 6, '乗ります', 'のります', 'lên xe, đi bằng xe', 'N5', 'えきで でんしゃに のります。', 'Tôi lên tàu ở nhà ga.'),
        ('SEEDSQLFLASHCARD_10_04', 7, 'いつ', 'いつ', 'khi nào', 'N5', 'いつ とうきょうへ いきますか。', 'Khi nào bạn đi Tokyo?'),
        ('SEEDSQLFLASHCARD_10_04', 8, '東京', 'とうきょう', 'Tokyo', 'N5', 'らいしゅう とうきょうへ いきます。', 'Tuần sau tôi đi Tokyo.'),
        ('SEEDSQLFLASHCARD_10_04', 9, '大阪', 'おおさか', 'Osaka', 'N5', 'こんしゅう おおさかへ いきます。', 'Tuần này tôi đi Osaka.'),
        ('SEEDSQLFLASHCARD_10_04', 10, '空港', 'くうこう', 'sân bay', 'N5', 'バスで くうこうへ いきます。', 'Tôi đi xe buýt đến sân bay.'),
        ('SEEDSQLFLASHCARD_10_04', 11, '海', 'うみ', 'biển', 'N5', 'なつやすみに うみへ いきます。', 'Kỳ nghỉ hè tôi đi biển.'),
        ('SEEDSQLFLASHCARD_10_04', 12, '山', 'やま', 'núi', 'N5', 'ひとりで やまへ いきます。', 'Tôi đi núi một mình.'),
        ('SEEDSQLFLASHCARD_10_04', 13, 'お寺', 'おてら', 'chùa', 'N5', 'おおさかの おてらへ いきます。', 'Tôi đi đến ngôi chùa ở Osaka.'),
        ('SEEDSQLFLASHCARD_10_04', 14, '図書館', 'としょかん', 'thư viện', 'N5', 'じてんしゃで としょかんへ いきます。', 'Tôi đi xe đạp đến thư viện.'),
        ('SEEDSQLFLASHCARD_10_04', 15, '美術館', 'びじゅつかん', 'bảo tàng mỹ thuật', 'N5', 'ゆうめいな びじゅつかんへ いきます。', 'Tôi đi đến bảo tàng mỹ thuật nổi tiếng.'),
        ('SEEDSQLFLASHCARD_10_04', 16, '電車', 'でんしゃ', 'tàu điện', 'N5', 'でんしゃで とうきょうへ いきます。', 'Tôi đi Tokyo bằng tàu điện.'),
        ('SEEDSQLFLASHCARD_10_04', 17, '地下鉄', 'ちかてつ', 'tàu điện ngầm', 'N5', 'ちかてつで びじゅつかんへ いきます。', 'Tôi đi bảo tàng mỹ thuật bằng tàu điện ngầm.'),
        ('SEEDSQLFLASHCARD_10_04', 18, 'バス', 'バス', 'xe buýt', 'N5', 'バスで うみへ いきます。', 'Tôi đi biển bằng xe buýt.'),
        ('SEEDSQLFLASHCARD_10_04', 19, 'タクシー', 'タクシー', 'xe taxi', 'N5', 'タクシーで くうこうへ いきます。', 'Tôi đi sân bay bằng xe taxi.'),
        ('SEEDSQLFLASHCARD_10_04', 20, '自転車', 'じてんしゃ', 'xe đạp', 'N5', 'じてんしゃで おてらへ いきます。', 'Tôi đi chùa bằng xe đạp.'),
        ('SEEDSQLFLASHCARD_10_04', 21, 'バイク', 'バイク', 'xe máy', 'N5', 'バイクで やまへ いきます。', 'Tôi đi núi bằng xe máy.'),
        ('SEEDSQLFLASHCARD_10_04', 22, '新幹線', 'しんかんせん', 'tàu cao tốc', 'N5', 'しんかんせんで とうきょうへ いきます。', 'Tôi đi Tokyo bằng tàu cao tốc.'),
        ('SEEDSQLFLASHCARD_10_04', 23, '飛行機', 'ひこうき', 'máy bay', 'N5', 'ひこうきで とうきょうへ いきます。', 'Tôi đi Tokyo bằng máy bay.'),
        ('SEEDSQLFLASHCARD_10_04', 24, '先週', 'せんしゅう', 'tuần trước', 'N5', 'せんしゅう どこも いきませんでした。', 'Tuần trước tôi đã không đi đâu cả.'),
        ('SEEDSQLFLASHCARD_10_04', 25, '今週', 'こんしゅう', 'tuần này', 'N5', 'こんしゅう うみへ いきます。', 'Tuần này tôi đi biển.'),
        ('SEEDSQLFLASHCARD_10_04', 26, '来週', 'らいしゅう', 'tuần sau', 'N5', 'らいしゅう やまへ いきます。', 'Tuần sau tôi đi núi.'),
        ('SEEDSQLFLASHCARD_10_04', 27, '先月', 'せんげつ', 'tháng trước', 'N5', 'せんげつ うみへ いきませんでした。', 'Tháng trước tôi đã không đi biển.'),
        ('SEEDSQLFLASHCARD_10_04', 28, '来月', 'らいげつ', 'tháng sau', 'N5', 'らいげつは どこへも いきません。', 'Tháng sau tôi không đi đâu cả.'),
        ('SEEDSQLFLASHCARD_10_04', 29, '今年', 'ことし', 'năm nay', 'N5', 'ことし とうきょうへ いきます。', 'Năm nay tôi đi Tokyo.'),
        ('SEEDSQLFLASHCARD_10_04', 30, '夏', 'なつ', 'mùa hè', 'N5', 'なつ やまへ いきます。', 'Mùa hè tôi đi núi.'),
        ('SEEDSQLFLASHCARD_10_04', 31, '速い', 'はやい', 'nhanh', 'N5', 'しんかんせんは はやいですか。', 'Tàu cao tốc có nhanh không ạ?'),
        ('SEEDSQLFLASHCARD_10_04', 32, '遠い', 'とおい', 'xa', 'N5', 'とうきょうから おおさかまで とおいです。', 'Từ Tokyo đến Osaka thì xa.'),
        ('SEEDSQLFLASHCARD_10_04', 33, '近い', 'ちかい', 'gần', 'N5', 'としょかんから びじゅつかんまで ちかいです。', 'Từ thư viện đến bảo tàng mỹ thuật thì gần.'),
        ('SEEDSQLFLASHCARD_10_04', 34, '有名', 'ゆうめい', 'nổi tiếng', 'N5', 'ゆうめいな おてらへ いきます。', 'Tôi đi đến chùa nổi tiếng.'),
        ('SEEDSQLFLASHCARD_10_04', 35, '運賃', 'うんちん', 'tiền vé', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_04', 36, '片道', 'かたみち', 'một chiều', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_04', 37, '往復', 'おうふく', 'khứ hồi', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_04', 38, '乗り換え', 'のりかえ', 'đổi tàu', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_04', 39, '各駅停車', 'かくえきていしゃ', 'tàu thường dừng mọi ga', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_04', 40, '快速', 'かいそく', 'tàu nhanh', 'CUSTOM', NULL, NULL);

    -- Bộ 05: Ăn uống, sinh hoạt và mua sắm - 45 từ.
    -- Nguồn: data/seed_jlas/daily_data.json
    -- Nguồn: data/seed_jlas/sekai/sekai-n5/json/bai-06.json
    INSERT INTO tmp_seedsqlflashcard_cards
        (seed_key, position, front, reading, meaning, jlpt_level, example_ja, example_vi) VALUES
        ('SEEDSQLFLASHCARD_10_05', 1, 'ごはん', 'ごはん', 'cơm, bữa cơm', 'N5', 'ごはんを たべます。', 'Tôi ăn cơm.'),
        ('SEEDSQLFLASHCARD_10_05', 2, 'パン', 'パン', 'bánh mì', 'N5', 'パンを かいます。', 'Tôi mua bánh mì.'),
        ('SEEDSQLFLASHCARD_10_05', 3, 'お茶', 'おちゃ', 'trà', 'N5', 'おちゃを のみます。', 'Tôi uống trà.'),
        ('SEEDSQLFLASHCARD_10_05', 4, 'コーヒー', 'コーヒー', 'cà phê', 'N5', 'コーヒーを のみます。', 'Tôi uống cà phê.'),
        ('SEEDSQLFLASHCARD_10_05', 5, '牛乳', 'ぎゅうにゅう', 'sữa bò', 'N5', 'ぎゅうにゅうを のみます。', 'Tôi uống sữa bò.'),
        ('SEEDSQLFLASHCARD_10_05', 6, '飲みます', 'のみます', 'uống', 'N5', 'つめたい おちゃを のみます。', 'Tôi uống trà lạnh.'),
        ('SEEDSQLFLASHCARD_10_05', 7, '作ります', 'つくります', 'làm, nấu', 'N5', 'べんとうを つくります。', 'Tôi làm cơm hộp.'),
        ('SEEDSQLFLASHCARD_10_05', 8, '買います', 'かいます', 'mua', 'N5', 'くだものを かいます。', 'Tôi mua trái cây.'),
        ('SEEDSQLFLASHCARD_10_05', 9, '読みます', 'よみます', 'đọc', 'N5', 'てがみを よみます。', 'Tôi đọc thư.'),
        ('SEEDSQLFLASHCARD_10_05', 10, '見ます', 'みます', 'xem, nhìn', 'N5', 'てがみを みます。', 'Tôi xem lá thư.'),
        ('SEEDSQLFLASHCARD_10_05', 11, '聞きます', 'ききます', 'nghe', 'N5', 'おんがくを ききます。', 'Tôi nghe nhạc.'),
        ('SEEDSQLFLASHCARD_10_05', 12, '書きます', 'かきます', 'viết', 'N5', 'てがみを かきます。', 'Tôi viết thư.'),
        ('SEEDSQLFLASHCARD_10_05', 13, '会います', 'あいます', 'gặp', 'N5', 'しょくどうで ともだちに あいます。', 'Tôi gặp bạn ở nhà ăn.'),
        ('SEEDSQLFLASHCARD_10_05', 14, 'します', 'します', 'làm (việc gì đó)', 'N5', 'そうじを します。', 'Tôi dọn dẹp.'),
        ('SEEDSQLFLASHCARD_10_05', 15, '野菜', 'やさい', 'rau', 'N5', 'やさいを たべます。', 'Tôi ăn rau.'),
        ('SEEDSQLFLASHCARD_10_05', 16, '肉', 'にく', 'thịt', 'N5', 'にくを かいます。', 'Tôi mua thịt.'),
        ('SEEDSQLFLASHCARD_10_05', 17, '魚', 'さかな', 'cá', 'N5', 'さかなを たべます。', 'Tôi ăn cá.'),
        ('SEEDSQLFLASHCARD_10_05', 18, '卵', 'たまご', 'trứng', 'N5', 'たまごを たべます。', 'Tôi ăn trứng.'),
        ('SEEDSQLFLASHCARD_10_05', 19, '果物', 'くだもの', 'trái cây', 'N5', 'くだものを たべます。', 'Tôi ăn trái cây.'),
        ('SEEDSQLFLASHCARD_10_05', 20, '弁当', 'べんとう', 'cơm hộp', 'N5', 'べんとうを たべます。', 'Tôi ăn cơm hộp.'),
        ('SEEDSQLFLASHCARD_10_05', 21, 'ラーメン', 'ラーメン', 'mì ramen', 'N5', 'ラーメンを たべます。', 'Tôi ăn mì ramen.'),
        ('SEEDSQLFLASHCARD_10_05', 22, 'おにぎり', 'おにぎり', 'cơm nắm', 'N5', 'おにぎりを たべます。', 'Tôi ăn cơm nắm.'),
        ('SEEDSQLFLASHCARD_10_05', 23, '食堂', 'しょくどう', 'nhà ăn', 'N5', 'しょくどうで ごはんを たべます。', 'Tôi ăn cơm ở nhà ăn.'),
        ('SEEDSQLFLASHCARD_10_05', 24, '喫茶店', 'きっさてん', 'quán cà phê', 'N5', 'きっさてんで コーヒーを のみます。', 'Tôi uống cà phê ở quán cà phê.'),
        ('SEEDSQLFLASHCARD_10_05', 25, 'いざかや', 'いざかや', 'quán nhậu kiểu Nhật', 'N5', 'いざかやで さかなを たべます。', 'Tôi ăn cá ở quán nhậu.'),
        ('SEEDSQLFLASHCARD_10_05', 26, '台所', 'だいどころ', 'bếp', 'N5', 'だいどころで べんとうを つくります。', 'Tôi làm cơm hộp ở bếp.'),
        ('SEEDSQLFLASHCARD_10_05', 27, '一緒に', 'いっしょに', 'cùng nhau', 'N5', 'いっしょに ラーメンを たべませんか。', 'Cùng ăn mì ramen nhé?'),
        ('SEEDSQLFLASHCARD_10_05', 28, 'ええ', 'ええ', 'ừ, vâng (đáp thân mật)', 'N5', 'ええ、たべましょう。', 'Vâng, cùng ăn thôi.'),
        ('SEEDSQLFLASHCARD_10_05', 29, 'おいしい', 'おいしい', 'ngon', 'N5', 'おいしい ラーメンを たべます。', 'Tôi ăn mì ramen ngon.'),
        ('SEEDSQLFLASHCARD_10_05', 30, '冷たい', 'つめたい', 'lạnh (đồ uống, đồ vật)', 'N5', 'つめたい コーヒーを のみます。', 'Tôi uống cà phê lạnh.'),
        ('SEEDSQLFLASHCARD_10_05', 31, '温かい', 'あたたかい', 'ấm', 'N5', 'あたたかい おちゃを のみます。', 'Tôi uống trà ấm.'),
        ('SEEDSQLFLASHCARD_10_05', 32, '音楽', 'おんがく', 'âm nhạc', 'N5', 'きっさてんで おんがくを ききます。', 'Tôi nghe nhạc ở quán cà phê.'),
        ('SEEDSQLFLASHCARD_10_05', 33, '手紙', 'てがみ', 'lá thư', 'N5', 'きっさてんで てがみを かきます。', 'Tôi viết thư ở quán cà phê.'),
        ('SEEDSQLFLASHCARD_10_05', 34, '掃除', 'そうじ', 'việc dọn dẹp', 'N5', 'だいどころの そうじを します。', 'Tôi dọn dẹp căn bếp.'),
        ('SEEDSQLFLASHCARD_10_05', 35, '洗濯', 'せんたく', 'việc giặt giũ', 'N5', 'せんたくを します。', 'Tôi giặt giũ.'),
        ('SEEDSQLFLASHCARD_10_05', 36, '二つ', 'ふたつ', 'hai cái', 'N5', 'パンを ふたつ かいます。', 'Tôi mua hai cái bánh mì.'),
        ('SEEDSQLFLASHCARD_10_05', 37, '体', 'からだ', 'cơ thể', 'N5', 'からだに いい やさいを たべます。', 'Tôi ăn rau tốt cho cơ thể.'),
        ('SEEDSQLFLASHCARD_10_05', 38, '献立', 'こんだて', 'thực đơn bữa ăn', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_05', 39, '材料', 'ざいりょう', 'nguyên liệu', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_05', 40, '賞味期限', 'しょうみきげん', 'hạn sử dụng ngon nhất', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_05', 41, '消費期限', 'しょうひきげん', 'hạn dùng an toàn', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_05', 42, '特売', 'とくばい', 'hàng khuyến mãi', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_05', 43, '半額', 'はんがく', 'giảm nửa giá', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_05', 44, '惣菜', 'そうざい', 'đồ ăn làm sẵn', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_05', 45, '冷凍', 'れいとう', 'đông lạnh', 'CUSTOM', NULL, NULL);

    -- Bộ 06: Miêu tả sự vật và so sánh - 50 từ.
    -- Nguồn: data/seed_jlas/sekai/sekai-n5/json/bai-08.json
    -- Nguồn: data/seed_jlas/sekai/sekai-n5/json/bai-12.json
    INSERT INTO tmp_seedsqlflashcard_cards
        (seed_key, position, front, reading, meaning, jlpt_level, example_ja, example_vi) VALUES
        ('SEEDSQLFLASHCARD_10_06', 1, '大きい', 'おおきい', 'to, lớn', 'N5', 'がっこうは おおきいです。', 'Trường học thì lớn.'),
        ('SEEDSQLFLASHCARD_10_06', 2, '小さい', 'ちいさい', 'nhỏ, bé', 'N5', 'へやは ちいさいです。', 'Căn phòng thì nhỏ.'),
        ('SEEDSQLFLASHCARD_10_06', 3, '高い', 'たかい', 'đắt; cao', 'N5', 'たかい ホテルは どうですか。', 'Khách sạn đắt tiền thì thế nào?'),
        ('SEEDSQLFLASHCARD_10_06', 4, '安い', 'やすい', 'rẻ', 'N5', 'ほんは やすいです。', 'Sách thì rẻ.'),
        ('SEEDSQLFLASHCARD_10_06', 5, 'おもしろい', 'おもしろい', 'hay, thú vị', 'N5', 'おもしろい えいがは どうですか。', 'Bộ phim hay thì thế nào?'),
        ('SEEDSQLFLASHCARD_10_06', 6, '難しい', 'むずかしい', 'khó', 'N5', 'かんじは とても むずかしいです。', 'Chữ Hán rất khó.'),
        ('SEEDSQLFLASHCARD_10_06', 7, 'やさしい', 'やさしい', 'dễ; hiền', 'N5', 'ひとは やさしいです。', 'Con người thì hiền lành.'),
        ('SEEDSQLFLASHCARD_10_06', 8, '新しい', 'あたらしい', 'mới', 'N5', 'あたらしい がっこうです。', 'Là ngôi trường mới.'),
        ('SEEDSQLFLASHCARD_10_06', 9, '古い', 'ふるい', 'cũ', 'N5', 'この ほんは ふるくないです。', 'Quyển sách này không cũ.'),
        ('SEEDSQLFLASHCARD_10_06', 10, 'いい', 'いい', 'tốt, hay', 'N5', 'いい てんきです。', 'Trời đẹp.'),
        ('SEEDSQLFLASHCARD_10_06', 11, 'きれい', 'きれい', 'đẹp; sạch', 'N5', 'へやは きれいです。', 'Căn phòng thì sạch đẹp.'),
        ('SEEDSQLFLASHCARD_10_06', 12, '静か', 'しずか', 'yên tĩnh', 'N5', 'しずかな へやは どうですか。', 'Căn phòng yên tĩnh thì thế nào?'),
        ('SEEDSQLFLASHCARD_10_06', 13, 'にぎやか', 'にぎやか', 'nhộn nhịp, đông vui', 'N5', 'まちは にぎやかです。', 'Thành phố thì nhộn nhịp.'),
        ('SEEDSQLFLASHCARD_10_06', 14, '親切', 'しんせつ', 'tử tế, chu đáo', 'N5', 'あの ひとは しんせつです。', 'Người kia tử tế.'),
        ('SEEDSQLFLASHCARD_10_06', 15, '便利', 'べんり', 'tiện lợi', 'N5', 'この まちは とても べんりです。', 'Thành phố này rất tiện.'),
        ('SEEDSQLFLASHCARD_10_06', 16, 'ひま', 'ひま', 'rảnh rỗi', 'N5', 'きょうは ひまじゃ ありません。', 'Hôm nay tôi không rảnh.'),
        ('SEEDSQLFLASHCARD_10_06', 17, '大変', 'たいへん', 'vất vả', 'N5', 'しごとは たいへんです。', 'Công việc vất vả.'),
        ('SEEDSQLFLASHCARD_10_06', 18, '町', 'まち', 'thành phố, phố xá', 'N5', 'まちは しずかです。', 'Thành phố thì yên tĩnh.'),
        ('SEEDSQLFLASHCARD_10_06', 19, '部屋', 'へや', 'căn phòng', 'N5', 'へやは とても しずかです。', 'Căn phòng rất yên tĩnh.'),
        ('SEEDSQLFLASHCARD_10_06', 20, '学校', 'がっこう', 'trường học', 'N5', 'がっこうは あたらしいです。', 'Trường mới xây.'),
        ('SEEDSQLFLASHCARD_10_06', 21, '公園', 'こうえん', 'công viên', 'N5', 'こうえんは あまり おおきくないです。', 'Công viên không lớn lắm.'),
        ('SEEDSQLFLASHCARD_10_06', 22, '料理', 'りょうり', 'món ăn', 'N5', 'りょうりは あまり やすくないです。', 'Món ăn không rẻ lắm.'),
        ('SEEDSQLFLASHCARD_10_06', 23, '映画', 'えいが', 'bộ phim', 'N5', 'えいがは あまり たのしくないです。', 'Bộ phim không vui lắm.'),
        ('SEEDSQLFLASHCARD_10_06', 24, 'とても', 'とても', 'rất', 'N5', 'うみは とても きれいです。', 'Biển rất đẹp.'),
        ('SEEDSQLFLASHCARD_10_06', 25, 'あまり', 'あまり', 'không ... lắm', 'N5', 'しごとは あまり たいへんじゃ ありません。', 'Công việc không vất vả lắm.'),
        ('SEEDSQLFLASHCARD_10_06', 26, '少し', 'すこし', 'một ít', 'N5', 'この りょうりは すこし からいです。', 'Món này hơi cay.'),
        ('SEEDSQLFLASHCARD_10_06', 27, 'どんな', 'どんな', 'thế nào, kiểu gì', 'N5', 'どんな まちですか。', 'Đó là thành phố như thế nào?'),
        ('SEEDSQLFLASHCARD_10_06', 28, '楽しい', 'たのしい', 'vui', 'N5', 'しごとは たのしいです。', 'Công việc thì vui.'),
        ('SEEDSQLFLASHCARD_10_06', 29, '寒い', 'さむい', 'lạnh (trời)', 'N5', 'きょうは さむいです。', 'Hôm nay lạnh.'),
        ('SEEDSQLFLASHCARD_10_06', 30, '暑い', 'あつい', 'nóng (trời)', 'N5', 'ベトナムは とても あついです。', 'Việt Nam rất nóng.'),
        ('SEEDSQLFLASHCARD_10_06', 31, '広い', 'ひろい', 'rộng', 'N5', 'ひろい へやです。', 'Là căn phòng rộng.'),
        ('SEEDSQLFLASHCARD_10_06', 32, 'からい', 'からい', 'cay', 'N5', 'この りょうりは からくないです。', 'Món này không cay.'),
        ('SEEDSQLFLASHCARD_10_06', 33, '元気', 'げんき', 'khoẻ, tươi tỉnh', 'N5', 'あの ひとは げんきです。', 'Người kia khoẻ.'),
        ('SEEDSQLFLASHCARD_10_06', 34, 'ホテル', 'ホテル', 'khách sạn', 'N5', 'ホテルは きれいです。', 'Khách sạn thì sạch đẹp.'),
        ('SEEDSQLFLASHCARD_10_06', 35, '天気', 'てんき', 'thời tiết', 'N5', 'きょうの てんきは いいです。', 'Thời tiết hôm nay đẹp.'),
        ('SEEDSQLFLASHCARD_10_06', 36, '人', 'ひと', 'người', 'N5', 'あの ひとは ゆうめいです。', 'Người kia nổi tiếng.'),
        ('SEEDSQLFLASHCARD_10_06', 37, '食べ物', 'たべもの', 'đồ ăn', 'N5', 'たべものは あまり やすくないです。', 'Đồ ăn không rẻ lắm.'),
        ('SEEDSQLFLASHCARD_10_06', 38, '飲み物', 'のみもの', 'đồ uống', 'N5', 'この のみものは つめたくないです。', 'Đồ uống này không lạnh.'),
        ('SEEDSQLFLASHCARD_10_06', 39, '冬', 'ふゆ', 'mùa đông', 'N5', 'なつと ふゆと どちらが すきですか。', 'Mùa hè với mùa đông anh thích mùa nào hơn?'),
        ('SEEDSQLFLASHCARD_10_06', 40, 'ハノイ', 'ハノイ', 'Hà Nội', 'N5', 'きのう ハノイは あつかったです。', 'Hôm qua Hà Nội đã nóng.'),
        ('SEEDSQLFLASHCARD_10_06', 41, '店', 'みせ', 'cửa hàng', 'N5', 'みせの なかで とうきょうの みせが いちばん しずかです。', 'Trong các cửa hàng, cửa hàng ở Tokyo là yên tĩnh nhất.'),
        ('SEEDSQLFLASHCARD_10_06', 42, '日本語', 'にほんご', 'tiếng Nhật', 'N5', 'にほんごは えいごより たのしいです。', 'Tiếng Nhật vui hơn tiếng Anh.'),
        ('SEEDSQLFLASHCARD_10_06', 43, '英語', 'えいご', 'tiếng Anh', 'N5', 'にほんごと えいごと どちらが たのしいですか。', 'Tiếng Nhật và tiếng Anh cái nào vui hơn?'),
        ('SEEDSQLFLASHCARD_10_06', 44, '早い', 'はやい', 'nhanh; sớm', 'N5', 'まいにち はやく おきますか。', 'Hằng ngày bạn thức dậy sớm phải không?'),
        ('SEEDSQLFLASHCARD_10_06', 45, 'おそい', 'おそい', 'chậm; muộn', 'N5', 'バスは でんしゃより おそいです。', 'Xe buýt chậm hơn tàu điện.'),
        ('SEEDSQLFLASHCARD_10_06', 46, '長い', 'ながい', 'dài', 'N5', 'きのうの かいぎは ながかったです。', 'Cuộc họp hôm qua dài.'),
        ('SEEDSQLFLASHCARD_10_06', 47, '短い', 'みじかい', 'ngắn', 'N5', 'この みちは その みちより みじかいです。', 'Con đường này ngắn hơn con đường đó.'),
        ('SEEDSQLFLASHCARD_10_06', 48, '強い', 'つよい', 'mạnh', 'N5', 'チームの なかで この チームが いちばん つよいです。', 'Trong các đội, đội này là mạnh nhất.'),
        ('SEEDSQLFLASHCARD_10_06', 49, '季節', 'きせつ', 'mùa trong năm', 'N5', 'きせつの なかで なつが いちばん すきですか。', 'Trong các mùa, bạn thích mùa hè nhất phải không?'),
        ('SEEDSQLFLASHCARD_10_06', 50, 'スポーツ', 'スポーツ', 'thể thao', 'N5', 'スポーツの なかで やきゅうが いちばん たのしいです。', 'Trong các môn thể thao, bóng chày là vui nhất.');

    -- Bộ 07: Sức khỏe, sự cố và thủ tục hằng ngày - 55 từ.
    -- Nguồn: data/seed_jlas/sekai/sekai-n4/json/bai-26.json
    -- Nguồn: data/seed_jlas/sekai/sekai-n4/json/bai-32.json
    INSERT INTO tmp_seedsqlflashcard_cards
        (seed_key, position, front, reading, meaning, jlpt_level, example_ja, example_vi) VALUES
        ('SEEDSQLFLASHCARD_10_07', 1, '用事', 'ようじ', 'việc bận, việc riêng', 'N4', 'きょうは ようじが あるんです。', 'Hôm nay tôi có việc bận.'),
        ('SEEDSQLFLASHCARD_10_07', 2, '都合', 'つごう', 'sự tiện, thu xếp được', 'N4', 'きょうは つごうが わるいんです。', 'Hôm nay tôi không tiện thời gian.'),
        ('SEEDSQLFLASHCARD_10_07', 3, '急用', 'きゅうよう', 'việc gấp', 'N4', 'きょうは きゅうようが あるんです。', 'Hôm nay tôi có việc gấp.'),
        ('SEEDSQLFLASHCARD_10_07', 4, '事故', 'じこ', 'tai nạn', 'N4', 'じこが あったんです。', 'Đã xảy ra tai nạn.'),
        ('SEEDSQLFLASHCARD_10_07', 5, '故障', 'こしょう', 'hỏng hóc', 'N4', 'コピーきが こしょうなんです。', 'Máy photo bị hỏng.'),
        ('SEEDSQLFLASHCARD_10_07', 6, '残業', 'ざんぎょう', 'tăng ca', 'N4', 'きょうは ざんぎょうするんです。', 'Hôm nay tôi tăng ca.'),
        ('SEEDSQLFLASHCARD_10_07', 7, '遅刻', 'ちこく', 'đi muộn', 'N4', 'じこで ちこくしたんです。', 'Vì tai nạn nên tôi bị đi muộn.'),
        ('SEEDSQLFLASHCARD_10_07', 8, '熱', 'ねつ', 'sốt', 'N4', 'ねつが あるんです。', 'Tôi bị sốt.'),
        ('SEEDSQLFLASHCARD_10_07', 9, '風邪', 'かぜ', 'cảm', 'N4', 'かぜを ひいたんです。', 'Tôi bị cảm.'),
        ('SEEDSQLFLASHCARD_10_07', 10, '頭', 'あたま', 'đầu', 'N4', 'あたまが いたいんです。', 'Tôi đau đầu.'),
        ('SEEDSQLFLASHCARD_10_07', 11, 'おなか', 'おなか', 'bụng', 'N4', 'おなかが いたいんです。', 'Tôi bị đau bụng.'),
        ('SEEDSQLFLASHCARD_10_07', 12, '歯', 'は', 'răng', 'N4', 'はが いたいんです。', 'Tôi đau răng.'),
        ('SEEDSQLFLASHCARD_10_07', 13, '気分', 'きぶん', 'người, thể trạng trong người', 'N4', 'きぶんが よくないんです。', 'Tôi thấy trong người không ổn.'),
        ('SEEDSQLFLASHCARD_10_07', 14, 'けが', 'けが', 'vết thương, bị thương', 'N4', 'けがを したんです。', 'Tôi bị thương rồi.'),
        ('SEEDSQLFLASHCARD_10_07', 15, 'パスポート', 'パスポート', 'hộ chiếu', 'N4', 'パスポートが いるんですか。', 'Có cần hộ chiếu không ạ?'),
        ('SEEDSQLFLASHCARD_10_07', 16, 'ビザ', 'ビザ', 'thị thực', 'N4', 'ビザが いるんですか。', 'Có cần thị thực (visa) không ạ?'),
        ('SEEDSQLFLASHCARD_10_07', 17, '書類', 'しょるい', 'giấy tờ', 'N4', 'この しょるいを かくんですか。', 'Phải viết giấy tờ này ạ?'),
        ('SEEDSQLFLASHCARD_10_07', 18, '申込書', 'もうしこみしょ', 'đơn đăng ký', 'N4', 'もうしこみしょは どこに だしたら いいですか。', 'Đơn đăng ký nộp ở đâu thì được ạ?'),
        ('SEEDSQLFLASHCARD_10_07', 19, '印鑑', 'いんかん', 'con dấu tên', 'N4', 'いんかんが いるんですか。', 'Có cần con dấu không ạ?'),
        ('SEEDSQLFLASHCARD_10_07', 20, '住所', 'じゅうしょ', 'địa chỉ', 'N4', 'じゅうしょを かえるんです。', 'Tôi đổi địa chỉ.'),
        ('SEEDSQLFLASHCARD_10_07', 21, 'コピー', 'コピー', 'bản sao', 'N4', 'これを コピーしたら いいですか。', 'Photo cái này là được phải không ạ?'),
        ('SEEDSQLFLASHCARD_10_07', 22, '役所', 'やくしょ', 'cơ quan hành chính phường xã', 'N4', 'やくしょへ いくんです。', 'Tôi đi đến cơ quan hành chính.'),
        ('SEEDSQLFLASHCARD_10_07', 23, '銀行', 'ぎんこう', 'ngân hàng', 'N4', 'ぎんこうへ いきたいんです。', 'Tôi muốn đi ngân hàng.'),
        ('SEEDSQLFLASHCARD_10_07', 24, '郵便局', 'ゆうびんきょく', 'bưu điện', 'N4', 'ゆうびんきょくへ いきたいんです。', 'Tôi muốn đi bưu điện.'),
        ('SEEDSQLFLASHCARD_10_07', 25, '交番', 'こうばん', 'chốt cảnh sát', 'N4', 'こうばんへ いくんです。', 'Tôi đi đến chốt cảnh sát.'),
        ('SEEDSQLFLASHCARD_10_07', 26, '手伝います', 'てつだいます', 'giúp một tay', 'N4', 'ちょっと てつだって ほしいんですが、いいですか。', 'Tôi muốn nhờ bạn giúp một tay một chút được không?'),
        ('SEEDSQLFLASHCARD_10_07', 27, '教えます', 'おしえます', 'chỉ cho, dạy cho', 'N4', 'じゅうしょを おしえて ほしいんですが。', 'Tôi muốn nhờ bạn chỉ cho địa chỉ với.'),
        ('SEEDSQLFLASHCARD_10_07', 28, '貸します', 'かします', 'cho mượn', 'N4', 'かさを かして ほしいんですが。', 'Tôi muốn anh cho mượn cái ô.'),
        ('SEEDSQLFLASHCARD_10_07', 29, '待ちます', 'まちます', 'chờ', 'N4', 'すこし まって ほしいんですが。', 'Tôi muốn nhờ bạn đợi một chút.'),
        ('SEEDSQLFLASHCARD_10_07', 30, '予約', 'よやく', 'đặt chỗ trước', 'N4', 'よやくは どこで したら いいですか。', 'Đặt chỗ thì đặt ở đâu thì được ạ?'),
        ('SEEDSQLFLASHCARD_10_07', 31, '連絡', 'れんらく', 'liên lạc, báo tin', 'N4', 'れんらくは だれに したら いいですか。', 'Báo tin thì báo cho ai thì được ạ?'),
        ('SEEDSQLFLASHCARD_10_07', 32, '相談', 'そうだん', 'chuyện muốn bàn, hỏi ý', 'N4', 'ちょっと そうだんが あるんですが。', 'Tôi có chút chuyện muốn bàn bạc hỏi ý kiến.'),
        ('SEEDSQLFLASHCARD_10_07', 33, 'どうして', 'どうして', 'tại sao', 'N4', 'きのうは どうして やすんだんですか。', 'Hôm qua sao anh nghỉ vậy?'),
        ('SEEDSQLFLASHCARD_10_07', 34, '何か', 'なにか', 'chuyện gì đó', 'N4', 'なにか あったんですか。', 'Có chuyện gì xảy ra à?'),
        ('SEEDSQLFLASHCARD_10_07', 35, 'どのぐらい', 'どのぐらい', 'chừng bao lâu', 'N4', 'てつづきは どのぐらい かかるんですか。', 'Thủ tục mất chừng bao lâu ạ?'),
        ('SEEDSQLFLASHCARD_10_07', 36, 'どう', 'どう', 'thế nào', 'N4', 'この きかいは どう つかったら いいですか。', 'Máy này dùng thế nào thì được ạ?'),
        ('SEEDSQLFLASHCARD_10_07', 37, '手続き', 'てつづき', 'thủ tục', 'N4', 'てつづきは いつ したら いいですか。', 'Thủ tục thì làm lúc nào thì được ạ?'),
        ('SEEDSQLFLASHCARD_10_07', 38, '実は', 'じつは', 'thật ra là', 'N4', 'じつは ねつが あるんです。', 'Thật ra là tôi bị sốt.'),
        ('SEEDSQLFLASHCARD_10_07', 39, 'それで', 'それで', 'vì thế', 'N4', 'それで びょういんへ いくんです。', 'Vì thế nên tôi mới đi bệnh viện.'),
        ('SEEDSQLFLASHCARD_10_07', 40, 'たぶん', 'たぶん', 'chắc là', 'N4', 'たぶん でんしゃが おくれて いるんです。', 'Chắc là tàu đang trễ ạ.'),
        ('SEEDSQLFLASHCARD_10_07', 41, 'すぐ', 'すぐ', 'ngay', 'N4', 'すぐ れんらくするんです。', 'Tôi sẽ liên lạc ngay.'),
        ('SEEDSQLFLASHCARD_10_07', 42, '後で', 'あとで', 'lát nữa', 'N4', 'あとで ぎんこうへ いくんです。', 'Lát nữa tôi đi ngân hàng.'),
        ('SEEDSQLFLASHCARD_10_07', 43, '時間', 'じかん', 'thời gian', 'N4', 'じかんが ないんです。', 'Tôi không có thời gian.'),
        ('SEEDSQLFLASHCARD_10_07', 44, '申し込みます', 'もうしこみます', 'đăng ký, nộp đơn', 'N4', 'きょう もうしこむんです。', 'Hôm nay tôi nộp đơn đăng ký.'),
        ('SEEDSQLFLASHCARD_10_07', 45, '疲れた', 'つかれた', 'mệt', 'N4', 'つかれた ときは はやく ねた ほうが いいです。', 'Khi mệt thì nên ngủ sớm.'),
        ('SEEDSQLFLASHCARD_10_07', 46, '雨', 'あめ', 'mưa', 'N4', 'ゆうがたは あめが ふるでしょう。', 'Chiều tối chắc là mưa sẽ rơi.'),
        ('SEEDSQLFLASHCARD_10_07', 47, '雪', 'ゆき', 'tuyết', 'N4', 'ゆうがたは ゆきが ふるでしょう。', 'Chiều tối chắc là tuyết sẽ rơi.'),
        ('SEEDSQLFLASHCARD_10_07', 48, '台風', 'たいふう', 'bão', 'N4', 'たいふうで でんしゃが おくれるかもしれません。', 'Vì bão nên có lẽ tàu điện sẽ trễ.'),
        ('SEEDSQLFLASHCARD_10_07', 49, '道', 'みち', 'đường sá', 'N4', 'たいふうの ひは みちが こむでしょう。', 'Ngày có bão thì đường sá chắc sẽ đông.'),
        ('SEEDSQLFLASHCARD_10_07', 50, '薬', 'くすり', 'thuốc', 'N4', 'くすりは のんだ ほうが いいですか。', 'Thuốc có nên uống không ạ?'),
        ('SEEDSQLFLASHCARD_10_07', 51, 'きっと', 'きっと', 'nhất định', 'N4', 'きっと あめが ふるでしょう。', 'Nhất định mưa sẽ rơi.'),
        ('SEEDSQLFLASHCARD_10_07', 52, 'もしかしたら', 'もしかしたら', 'biết đâu', 'N4', 'もしかしたら かいぎは ちゅうしに なるかもしれません。', 'Biết đâu cuộc họp có lẽ sẽ bị huỷ.'),
        ('SEEDSQLFLASHCARD_10_07', 53, '中止', 'ちゅうし', 'việc huỷ giữa chừng', 'N4', 'たいふうで かいぎは ちゅうしに なるかもしれません。', 'Vì bão nên cuộc họp có lẽ sẽ bị huỷ.'),
        ('SEEDSQLFLASHCARD_10_07', 54, '無理', 'むり', 'sự gắng quá sức', 'N4', 'むりは しない ほうが いいです。', 'Không nên gắng quá sức.'),
        ('SEEDSQLFLASHCARD_10_07', 55, '心配', 'しんぱい', 'lo lắng', 'N4', 'しんぱいしない ほうが いいと おもいます。', 'Tôi nghĩ là không nên lo lắng.');

    -- Bộ 08: Du lịch: sân bay và tàu điện - 20 từ.
    -- Nguồn: data/seed_jlas/travel_data.json
    INSERT INTO tmp_seedsqlflashcard_cards
        (seed_key, position, front, reading, meaning, jlpt_level, example_ja, example_vi) VALUES
        ('SEEDSQLFLASHCARD_10_08', 1, '搭乗', 'とうじょう', 'lên máy bay', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 2, '搭乗券', 'とうじょうけん', 'thẻ lên máy bay', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 3, '出発', 'しゅっぱつ', 'khởi hành', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 4, '到着', 'とうちゃく', 'đến nơi', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 5, '遅延', 'ちえん', 'trễ chuyến', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 6, '欠航', 'けっこう', 'huỷ chuyến', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 7, '手荷物', 'てにもつ', 'hành lý xách tay', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 8, '預ける', 'あずける', 'gửi (hành lý)', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 9, '受け取る', 'うけとる', 'nhận lại', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 10, '入国', 'にゅうこく', 'nhập cảnh', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 11, '出国', 'しゅっこく', 'xuất cảnh', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 12, '税関', 'ぜいかん', 'hải quan', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 13, '申告', 'しんこく', 'khai báo', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 14, '旅券', 'りょけん', 'hộ chiếu', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 15, '査証', 'さしょう', 'thị thực', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 16, '乗り継ぎ', 'のりつぎ', 'nối chuyến', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 17, '窓側', 'まどがわ', 'phía cửa sổ', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 18, '通路側', 'つうろがわ', 'phía lối đi', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 19, '保安検査', 'ほあんけんさ', 'kiểm tra an ninh', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_08', 20, '特急', 'とっきゅう', 'tàu tốc hành', 'CUSTOM', NULL, NULL);

    -- Bộ 09: Đời sống: nhà ở và thủ tục hành chính - 25 từ.
    -- Nguồn: data/seed_jlas/daily_data.json
    INSERT INTO tmp_seedsqlflashcard_cards
        (seed_key, position, front, reading, meaning, jlpt_level, example_ja, example_vi) VALUES
        ('SEEDSQLFLASHCARD_10_09', 1, '家賃', 'やちん', 'tiền thuê nhà', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 2, '敷金', 'しききん', 'tiền đặt cọc', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 3, '礼金', 'れいきん', 'tiền lễ cho chủ nhà', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 4, '大家', 'おおや', 'chủ nhà', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 5, '間取り', 'まどり', 'bố trí phòng', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 6, '日当たり', 'ひあたり', 'độ chiếu nắng', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 7, '騒音', 'そうおん', 'tiếng ồn', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 8, '片付ける', 'かたづける', 'dọn gọn', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 9, 'ごみ', 'ごみ', 'rác', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 10, '分別', 'ぶんべつ', 'phân loại (rác)', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 11, '資源ごみ', 'しげんごみ', 'rác tái chế', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 12, '粗大ごみ', 'そだいごみ', 'rác cồng kềnh', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 13, '電気代', 'でんきだい', 'tiền điện', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 14, '水道代', 'すいどうだい', 'tiền nước', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 15, '引っ越し', 'ひっこし', 'chuyển nhà', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 16, '住民票', 'じゅうみんひょう', 'giấy chứng nhận cư trú', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 17, '記入', 'きにゅう', 'điền vào', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 18, '身分証明書', 'みぶんしょうめいしょ', 'giấy tờ tuỳ thân', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 19, '口座', 'こうざ', 'tài khoản', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 20, '振込', 'ふりこみ', 'chuyển khoản', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 21, '引き出す', 'ひきだす', 'rút tiền', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 22, '残高', 'ざんだか', 'số dư', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 23, '通帳', 'つうちょう', 'sổ ngân hàng', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 24, '暗証番号', 'あんしょうばんごう', 'mã PIN', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_09', 25, '宅配便', 'たくはいびん', 'chuyển phát tận nhà', 'CUSTOM', NULL, NULL);

    -- Bộ 10: IT: lập trình và phát triển phần mềm - 30 từ.
    -- Nguồn: data/seed_jlas/it_data.json
    INSERT INTO tmp_seedsqlflashcard_cards
        (seed_key, position, front, reading, meaning, jlpt_level, example_ja, example_vi) VALUES
        ('SEEDSQLFLASHCARD_10_10', 1, '開発', 'かいはつ', 'phát triển', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 2, '実装', 'じっそう', 'hiện thực hóa, viết code', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 3, '処理', 'しょり', 'xử lý', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 4, '変数', 'へんすう', 'biến', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 5, '関数', 'かんすう', 'hàm', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 6, '引数', 'ひきすう', 'tham số (đối số)', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 7, '戻り値', 'もどりち', 'giá trị trả về', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 8, '配列', 'はいれつ', 'mảng', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 9, '条件分岐', 'じょうけんぶんき', 'rẽ nhánh điều kiện', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 10, '例外', 'れいがい', 'ngoại lệ', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 11, '例外処理', 'れいがいしょり', 'xử lý ngoại lệ', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 12, '共通化', 'きょうつうか', 'gom thành dùng chung', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 13, '修正', 'しゅうせい', 'sửa', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 14, '改修', 'かいしゅう', 'sửa đổi, nâng cấp', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 15, '追加', 'ついか', 'thêm', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 16, '削除', 'さくじょ', 'xóa', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 17, '更新', 'こうしん', 'cập nhật', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 18, '登録', 'とうろく', 'đăng ký, lưu mới', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 19, '検索', 'けんさく', 'tìm kiếm', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 20, '一覧', 'いちらん', 'danh sách', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 21, '詳細', 'しょうさい', 'chi tiết', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 22, '画面', 'がめん', 'màn hình', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 23, '設定', 'せってい', 'cài đặt, cấu hình', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 24, '環境構築', 'かんきょうこうちく', 'dựng môi trường', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 25, '採番', 'さいばん', 'đánh số (cấp ID)', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 26, '排他制御', 'はいたせいぎょ', 'kiểm soát tranh chấp (lock)', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 27, '非同期', 'ひどうき', 'bất đồng bộ', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 28, '文字化け', 'もじばけ', 'lỗi hiển thị ký tự', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 29, '文字コード', 'もじコード', 'mã ký tự (encoding)', 'CUSTOM', NULL, NULL),
        ('SEEDSQLFLASHCARD_10_10', 30, 'レビュー', 'レビュー', 'review', 'CUSTOM', NULL, NULL);

    -- Kiểm tra số thẻ trước khi ghi dữ liệu thật.
    IF EXISTS (
        SELECT s.seed_key
        FROM tmp_seedsqlflashcard_sets s
        LEFT JOIN tmp_seedsqlflashcard_cards c ON c.seed_key = s.seed_key
        GROUP BY s.seed_key, s.expected_count
        HAVING COUNT(c.position) <> s.expected_count
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'So the trong du lieu seed khong dung.';
    END IF;

    START TRANSACTION;

    INSERT INTO flashcard_folder (owner_id, name)
    VALUES (v_owner_id, 'Flashcard từ vựng - 10 bộ')
    ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id);
    SET v_folder_id = LAST_INSERT_ID();

    INSERT INTO flashcard_set (
        owner_id, folder_id, title, description, visibility, is_draft,
        moderation_status, moderation_checked_at, moderation_summary
    )
    SELECT v_owner_id, v_folder_id, s.title, s.description, 'PUBLIC', 0,
           'SAFE', CURRENT_TIMESTAMP(6), s.seed_key
    FROM tmp_seedsqlflashcard_sets s
    LEFT JOIN flashcard_set existing
        ON existing.owner_id = v_owner_id AND existing.moderation_summary = s.seed_key
    WHERE existing.id IS NULL
    ORDER BY s.seed_key;

    INSERT INTO flashcard_card (
        set_id, front, back, reading, meaning, jlpt_level, example_ja, example_vi, position
    )
    SELECT fs.id, c.front, CONCAT(c.reading, ' — ', c.meaning), c.reading, c.meaning,
           c.jlpt_level, c.example_ja, c.example_vi, c.position
    FROM tmp_seedsqlflashcard_cards c
    JOIN flashcard_set fs
        ON fs.owner_id = v_owner_id AND fs.moderation_summary = c.seed_key
    LEFT JOIN flashcard_card existing ON existing.set_id = fs.id AND existing.position = c.position
    WHERE existing.id IS NULL AND fs.deleted_at IS NULL
    ORDER BY c.seed_key, c.position;

    COMMIT;

    -- Kết quả: 10 dòng, total_cards lần lượt 20,25,30,40,45,50,55,20,25,30.
    SELECT fs.id AS set_id, @flashcard_seed_owner_email AS owner_email,
           fs.title, s.expected_count, COUNT(c.id) AS total_cards,
           fs.visibility, fs.is_draft, fs.deleted_at
    FROM tmp_seedsqlflashcard_sets s
    JOIN flashcard_set fs
        ON fs.owner_id = v_owner_id AND fs.moderation_summary = s.seed_key
    LEFT JOIN flashcard_card c ON c.set_id = fs.id
    GROUP BY s.seed_key, fs.id, fs.title, s.expected_count,
             fs.visibility, fs.is_draft, fs.deleted_at
    ORDER BY s.seed_key;

    DROP TEMPORARY TABLE tmp_seedsqlflashcard_cards;
    DROP TEMPORARY TABLE tmp_seedsqlflashcard_sets;
END$$
DELIMITER ;

CALL _seed_seedsqlflashcard_10();
DROP PROCEDURE IF EXISTS _seed_seedsqlflashcard_10;
