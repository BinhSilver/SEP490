-- Demo seed: 5 bo flashcard cho tai khoan huyphw2@gmail.com.
-- Chi chay tren database da co bang users va cac bang flashcard.
-- File co the chay lai: cac bo demo cu duoc nhan dien boi moderation_summary.

USE jlas;
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

START TRANSACTION;

SET @demo_email = 'huyphw2@gmail.com';
SET @demo_user_id = (
    SELECT id
    FROM users
    WHERE email COLLATE utf8mb4_unicode_ci = @demo_email COLLATE utf8mb4_unicode_ci
    LIMIT 1
);

SELECT @demo_user_id AS demo_user_id, @demo_email AS demo_email;

-- Thu muc rieng cho 5 bo demo.
INSERT INTO flashcard_folder (owner_id, name)
VALUES (@demo_user_id, 'Demo Nhat ngu')
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    updated_at = CURRENT_TIMESTAMP(6);
SET @demo_folder_id = LAST_INSERT_ID();

-- Xoa cac bo demo cu. Cac bang phu thuoc se tu dong cascade theo flashcard_set.
DELETE FROM flashcard_set
WHERE owner_id = @demo_user_id
  AND moderation_summary COLLATE utf8mb4_unicode_ci =
      'DEMO_FLASHCARD_5SETS' COLLATE utf8mb4_unicode_ci;

INSERT INTO flashcard_set (
    owner_id, folder_id, title, description, visibility, is_draft,
    moderation_status, moderation_checked_at, moderation_summary
)
VALUES (
    @demo_user_id, @demo_folder_id,
    'Hiragana N5 - Co ban',
    'Lam quen 46 am Hiragana thong dung cho nguoi moi bat dau.',
    'PUBLIC', 0, 'SAFE', CURRENT_TIMESTAMP(6), 'DEMO_FLASHCARD_5SETS'
);
SET @set_hiragana = LAST_INSERT_ID();

INSERT INTO flashcard_set (
    owner_id, folder_id, title, description, visibility, is_draft,
    moderation_status, moderation_checked_at, moderation_summary
)
VALUES (
    @demo_user_id, @demo_folder_id,
    'Katakana N5 - Co ban',
    'Bo the luyen doc Katakana va cac tu vay muon quen thuoc.',
    'PUBLIC', 0, 'SAFE', CURRENT_TIMESTAMP(6), 'DEMO_FLASHCARD_5SETS'
);
SET @set_katakana = LAST_INSERT_ID();

INSERT INTO flashcard_set (
    owner_id, folder_id, title, description, visibility, is_draft,
    moderation_status, moderation_checked_at, moderation_summary
)
VALUES (
    @demo_user_id, @demo_folder_id,
    'Dong tu N5 thiet yeu',
    'Nhung dong tu can thiet cho giao tiep va bai hoc N5.',
    'PUBLIC', 0, 'SAFE', CURRENT_TIMESTAMP(6), 'DEMO_FLASHCARD_5SETS'
);
SET @set_verbs = LAST_INSERT_ID();

INSERT INTO flashcard_set (
    owner_id, folder_id, title, description, visibility, is_draft,
    moderation_status, moderation_checked_at, moderation_summary
)
VALUES (
    @demo_user_id, @demo_folder_id,
    'Tinh tu N4 thuong gap',
    'Tinh tu mo ta nguoi, do vat va cam nhan trong tieng Nhat.',
    'PUBLIC', 0, 'SAFE', CURRENT_TIMESTAMP(6), 'DEMO_FLASHCARD_5SETS'
);
SET @set_adjectives = LAST_INSERT_ID();

INSERT INTO flashcard_set (
    owner_id, folder_id, title, description, visibility, is_draft,
    moderation_status, moderation_checked_at, moderation_summary
)
VALUES (
    @demo_user_id, @demo_folder_id,
    'Hoi thoai du lich Nhat Ban',
    'Cau giao tiep thuc te khi di lai, mua sam va goi mon o Nhat.',
    'PUBLIC', 0, 'SAFE', CURRENT_TIMESTAMP(6), 'DEMO_FLASHCARD_5SETS'
);
SET @set_travel = LAST_INSERT_ID();

INSERT INTO flashcard_card (
    set_id, front, back, reading, meaning, han_viet, jlpt_level, word_type,
    example_ja, example_vi, position
)
VALUES
    (@set_hiragana, 'あ', 'a', 'あ', 'Am a', NULL, 'N5', 'Am Hiragana', 'あさです。', 'Buoi sang.', 1),
    (@set_hiragana, 'い', 'i', 'い', 'Am i', NULL, 'N5', 'Am Hiragana', 'いえへ かえります。', 'Toi ve nha.', 2),
    (@set_hiragana, 'う', 'u', 'う', 'Am u', NULL, 'N5', 'Am Hiragana', 'うみが すきです。', 'Toi thich bien.', 3),
    (@set_hiragana, 'え', 'e', 'え', 'Am e', NULL, 'N5', 'Am Hiragana', 'えきは どこですか。', 'Ga o dau?', 4),
    (@set_hiragana, 'お', 'o', 'お', 'Am o', NULL, 'N5', 'Am Hiragana', 'おはようございます。', 'Chao buoi sang.', 5),
    (@set_hiragana, 'か', 'ka', 'か', 'Am ka', NULL, 'N5', 'Am Hiragana', 'かさを もちます。', 'Mang theo o.', 6),
    (@set_hiragana, 'き', 'ki', 'き', 'Am ki', NULL, 'N5', 'Am Hiragana', 'きょうは あついです。', 'Hom nay nong.', 7),
    (@set_hiragana, 'く', 'ku', 'く', 'Am ku', NULL, 'N5', 'Am Hiragana', 'くるまが あります。', 'Co mot chiec o to.', 8),

    (@set_katakana, 'ア', 'a', 'ア', 'Am a', NULL, 'N5', 'Am Katakana', 'アメリカへ いきます。', 'Toi di My.', 1),
    (@set_katakana, 'イ', 'i', 'イ', 'Am i', NULL, 'N5', 'Am Katakana', 'イタリアの りょうりです。', 'Mon an Y cua Italy.', 2),
    (@set_katakana, 'ウ', 'u', 'ウ', 'Am u', NULL, 'N5', 'Am Katakana', 'ウイルスに きをつけます。', 'Hay can than virus.', 3),
    (@set_katakana, 'エ', 'e', 'エ', 'Am e', NULL, 'N5', 'Am Katakana', 'エレベーターは あちらです。', 'Thang may o phia kia.', 4),
    (@set_katakana, 'オ', 'o', 'オ', 'Am o', NULL, 'N5', 'Am Katakana', 'オレンジを たべます。', 'Toi an cam.', 5),
    (@set_katakana, 'カ', 'ka', 'カ', 'Am ka', NULL, 'N5', 'Am Katakana', 'カメラを かいました。', 'Toi da mua may anh.', 6),
    (@set_katakana, 'キ', 'ki', 'キ', 'Am ki', NULL, 'N5', 'Am Katakana', 'キロメートルを はかります。', 'Do kilomet.', 7),
    (@set_katakana, 'ク', 'ku', 'ク', 'Am ku', NULL, 'N5', 'Am Katakana', 'クラスは 3かいです。', 'Lop hoc o tang 3.', 8),

    (@set_verbs, '食べる', 'an', 'たべる', 'An', 'Thuc', 'N5', 'Dong tu', '毎朝、パンを食べます。', 'Moi sang toi an banh mi.', 1),
    (@set_verbs, '飲む', 'uong', 'のむ', 'Uong', 'Am', 'N5', 'Dong tu', '水を飲んでください。', 'Hay uong nuoc.', 2),
    (@set_verbs, '行く', 'di', 'いく', 'Di', 'Hanh', 'N5', 'Dong tu', '学校へ行きます。', 'Toi di den truong.', 3),
    (@set_verbs, '来る', 'den', 'くる', 'Den', 'Lai', 'N5', 'Dong tu', '友だちが来ます。', 'Ban toi se den.', 4),
    (@set_verbs, '見る', 'xem, nhin', 'みる', 'Xem, nhin', 'Kien', 'N5', 'Dong tu', '映画を見ます。', 'Toi xem phim.', 5),
    (@set_verbs, '読む', 'doc', 'よむ', 'Doc', 'Doc', 'N5', 'Dong tu', '本を読みます。', 'Toi doc sach.', 6),
    (@set_verbs, '書く', 'viet', 'かく', 'Viet', 'Thu', 'N5', 'Dong tu', '名前を書きます。', 'Toi viet ten.', 7),
    (@set_verbs, '話す', 'noi', 'はなす', 'Noi, noi chuyen', 'Thoai', 'N5', 'Dong tu', '日本語を話します。', 'Toi noi tieng Nhat.', 8),

    (@set_adjectives, '大きい', 'lon', 'おおきい', 'Lon', 'Dai', 'N4', 'Tinh tu i', '大きい犬がいます。', 'Co mot con cho lon.', 1),
    (@set_adjectives, '小さい', 'nho', 'ちいさい', 'Nho', 'Tieu', 'N4', 'Tinh tu i', '小さいかばんです。', 'Day la chiec tui nho.', 2),
    (@set_adjectives, '新しい', 'moi', 'あたらしい', 'Moi', 'Tan', 'N4', 'Tinh tu i', '新しい本を買いました。', 'Toi da mua sach moi.', 3),
    (@set_adjectives, '古い', 'cu', 'ふるい', 'Cu', 'Co', 'N4', 'Tinh tu i', '古い町を歩きます。', 'Toi di bo trong pho co.', 4),
    (@set_adjectives, '高い', 'cao, dat', 'たかい', 'Cao, dat', 'Cao', 'N4', 'Tinh tu i', 'この店は少し高いです。', 'Cua hang nay hoi dat.', 5),
    (@set_adjectives, '安い', 're', 'やすい', 'Re', 'An', 'N4', 'Tinh tu i', 'このりんごは安いです。', 'Tao nay re.', 6),
    (@set_adjectives, '面白い', 'thu vi', 'おもしろい', 'Thu vi', 'Dien Bach', 'N4', 'Tinh tu i', '面白い映画でした。', 'Do la bo phim thu vi.', 7),
    (@set_adjectives, '難しい', 'kho', 'むずかしい', 'Kho', 'Nan', 'N4', 'Tinh tu i', 'この問題は難しいです。', 'Cau hoi nay kho.', 8),

    (@set_travel, 'こんにちは', 'xin chao', 'こんにちは', 'Xin chao', NULL, 'N5', 'Chao hoi', 'こんにちは、お元気ですか。', 'Xin chao, ban khoe khong?', 1),
    (@set_travel, 'ありがとうございます', 'cam on rat nhieu', 'ありがとうございます', 'Cam on rat nhieu', NULL, 'N5', 'Bieu cam', 'ありがとうございます。助かりました。', 'Cam on, ban da giup toi.', 2),
    (@set_travel, 'すみません', 'xin loi, xin phep', 'すみません', 'Xin loi, xin phep', NULL, 'N5', 'Bieu cam', 'すみません、駅はどこですか。', 'Xin loi, ga o dau?', 3),
    (@set_travel, '駅はどこですか', 'Ga o dau?', 'えきはどこですか', 'Hoi duong den ga', NULL, 'N5', 'Cau hoi', '駅はどこですか。', 'Ga o dau?', 4),
    (@set_travel, 'これをください', 'Cho toi cai nay', 'これをください', 'Cho toi cai nay', NULL, 'N5', 'Cau yeu cau', 'これをください。', 'Cho toi cai nay.', 5),
    (@set_travel, 'お願いします', 'Lam on, nho ban', 'おねがいします', 'Lam on, nho ban', NULL, 'N5', 'Cau yeu cau', 'よろしくお願いします。', 'Rat mong ban giup do.', 6),
    (@set_travel, 'いくらですか', 'Bao nhieu tien?', 'いくらですか', 'Hoi gia', NULL, 'N5', 'Cau hoi', 'これはいくらですか。', 'Cai nay bao nhieu tien?', 7),
    (@set_travel, 'トイレはどこですか', 'Nha ve sinh o dau?', 'トイレはどこですか', 'Hoi vi tri nha ve sinh', NULL, 'N5', 'Cau hoi', 'トイレはどこですか。', 'Nha ve sinh o dau?', 8);

-- Tao tien do mau: 3 the dau da hoc, 2 the dang hoc, cac the con lai moi.
INSERT INTO flashcard_card_progress (
    user_id, card_id, status, review_count, last_quality,
    easiness_factor, repetition_count, interval_days,
    last_reviewed_at, next_review_at, is_starred
)
SELECT
    @demo_user_id,
    card.id,
    CASE
        WHEN card.position <= 3 THEN 'MASTERED'
        WHEN card.position <= 5 THEN 'LEARNING'
        ELSE 'NEW'
    END,
    CASE WHEN card.position <= 5 THEN 2 ELSE 0 END,
    CASE WHEN card.position <= 3 THEN 5 WHEN card.position <= 5 THEN 2 ELSE NULL END,
    2.50,
    CASE WHEN card.position <= 3 THEN 3 WHEN card.position <= 5 THEN 1 ELSE 0 END,
    CASE WHEN card.position <= 3 THEN 6 WHEN card.position <= 5 THEN 1 ELSE 0 END,
    CASE WHEN card.position <= 5 THEN DATE_SUB(NOW(6), INTERVAL 1 DAY) ELSE NULL END,
    CASE WHEN card.position <= 3 THEN DATE_ADD(NOW(6), INTERVAL 5 DAY)
         WHEN card.position <= 5 THEN DATE_ADD(NOW(6), INTERVAL 1 DAY)
         ELSE NOW(6) END,
    CASE WHEN card.position = 1 THEN 1 ELSE 0 END
FROM flashcard_card card
WHERE card.set_id IN (@set_hiragana, @set_katakana, @set_verbs, @set_adjectives, @set_travel);

-- Trang thai, danh gia va thoi diem truy cap de thu cac bo loc trong thu vien.
INSERT INTO flashcard_user_set_state (
    user_id, set_id, is_favorite, rating, first_completed_at, last_accessed_at
)
VALUES
    (@demo_user_id, @set_hiragana, 1, 5, DATE_SUB(NOW(6), INTERVAL 6 DAY), DATE_SUB(NOW(6), INTERVAL 1 DAY)),
    (@demo_user_id, @set_katakana, 1, 4, DATE_SUB(NOW(6), INTERVAL 5 DAY), DATE_SUB(NOW(6), INTERVAL 2 DAY)),
    (@demo_user_id, @set_verbs, 0, 5, DATE_SUB(NOW(6), INTERVAL 4 DAY), DATE_SUB(NOW(6), INTERVAL 3 DAY)),
    (@demo_user_id, @set_adjectives, 0, 4, DATE_SUB(NOW(6), INTERVAL 3 DAY), DATE_SUB(NOW(6), INTERVAL 4 DAY)),
    (@demo_user_id, @set_travel, 1, 5, DATE_SUB(NOW(6), INTERVAL 2 DAY), NOW(6));

-- Lich su hoc mau de cot "Hoc nhieu nhat" co du lieu hien thi.
INSERT INTO flashcard_study_session (
    user_id, set_id, session_type, mode, status, started_at, finished_at
)
VALUES
    (@demo_user_id, @set_hiragana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 10 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 10 DAY), INTERVAL 8 MINUTE)),
    (@demo_user_id, @set_hiragana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 7 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 7 DAY), INTERVAL 9 MINUTE)),
    (@demo_user_id, @set_hiragana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 6 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 6 DAY), INTERVAL 7 MINUTE)),
    (@demo_user_id, @set_hiragana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 3 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 3 DAY), INTERVAL 8 MINUTE)),
    (@demo_user_id, @set_hiragana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 1 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 1 DAY), INTERVAL 10 MINUTE)),
    (@demo_user_id, @set_katakana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 9 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 9 DAY), INTERVAL 8 MINUTE)),
    (@demo_user_id, @set_katakana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 5 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 5 DAY), INTERVAL 10 MINUTE)),
    (@demo_user_id, @set_katakana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 2 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 2 DAY), INTERVAL 8 MINUTE)),
    (@demo_user_id, @set_katakana, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 1 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 1 DAY), INTERVAL 9 MINUTE)),
    (@demo_user_id, @set_verbs, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 8 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 8 DAY), INTERVAL 12 MINUTE)),
    (@demo_user_id, @set_verbs, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 4 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 4 DAY), INTERVAL 11 MINUTE)),
    (@demo_user_id, @set_verbs, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 2 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 2 DAY), INTERVAL 10 MINUTE)),
    (@demo_user_id, @set_adjectives, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 7 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 7 DAY), INTERVAL 9 MINUTE)),
    (@demo_user_id, @set_adjectives, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 3 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 3 DAY), INTERVAL 10 MINUTE)),
    (@demo_user_id, @set_travel, 'FLASHCARD', 'ALL', 'COMPLETED', DATE_SUB(NOW(6), INTERVAL 1 DAY), DATE_ADD(DATE_SUB(NOW(6), INTERVAL 1 DAY), INTERVAL 12 MINUTE));

COMMIT;

-- Ket qua mong doi: 5 dong bo va 40 dong the.
SELECT id, title, visibility, moderation_status,
       (SELECT COUNT(*) FROM flashcard_card card WHERE card.set_id = fs.id) AS total_cards
FROM flashcard_set fs
WHERE fs.owner_id = @demo_user_id
  AND fs.moderation_summary COLLATE utf8mb4_unicode_ci =
      'DEMO_FLASHCARD_5SETS' COLLATE utf8mb4_unicode_ci
ORDER BY fs.id;
