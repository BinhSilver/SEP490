-- Demo/dev only — NEVER run on production.
-- Trí nhớ dài hạn có sẵn cho "Trò chuyện AI" (フリートーク) của tài khoản demo, như thể người học đã trò chuyện
-- hai phiên trước đó. Cần migrations sql/migrations/010_kaiwa_memory.sql và 017_ai_call_memory_bridge.sql.
--
-- Cách dữ liệu được dùng (kaiwa/memory): phiên mới tìm các dòng kaiwa_memory của người học và đưa vào ngữ cảnh AI.
-- - content viết bằng tiếng Việt, evidence là nguyên văn câu người học nói (giống MemoryWorker tự trích).
-- - embedding để NULL: MemoryWorker tự tạo vector trong vài phút sau khi app chạy (cần OPENROUTER_API_KEY).
-- - Phiên mẫu có revision = processed_revision = 0 nên MemoryWorker không trích lại.
--
-- Chạy lại được: thay phiên mẫu cũ của tài khoản này và ghi đè các memory cùng key.
-- Đổi tài khoản: sửa email ở dòng SET @uid. Báo lỗi "Column 'user_id' cannot be null" nghĩa là không tìm thấy email.

-- Email viết thẳng (không qua biến) để tự theo collation của users.email.
SET @uid := (SELECT id FROM users WHERE email = 'huyphw4@gmail.com');

START TRANSACTION;

-- Xoá phiên mẫu cũ; memory nguồn từ phiên đó bị xoá theo (FK ON DELETE CASCADE).
DELETE FROM kaiwa_free_session WHERE user_id = @uid AND summary LIKE '[demo-seed]%';

-- Phiên 1: giới thiệu bản thân, mục tiêu học (5 ngày trước).
SET @s1_at := NOW(6) - INTERVAL 5 DAY;
INSERT INTO kaiwa_free_session (user_id, status, summary, recent_turns, revision, processed_revision,
                                created_at, updated_at, ended_at)
VALUES (@uid, 'COMPLETED',
        '[demo-seed] Người học tự giới thiệu: tên Huy, sinh viên năm cuối ngành CNTT ở Hà Nội. Mục tiêu đỗ JLPT N3 tháng 12/2026, yếu nghe hiểu và trợ từ は/が, muốn được sửa lỗi trợ từ. Thích anime, đang xem Frieren.',
        JSON_ARRAY(), 0, 0, @s1_at, @s1_at + INTERVAL 20 MINUTE, @s1_at + INTERVAL 20 MINUTE);
SET @s1 := LAST_INSERT_ID();

-- Phiên 2: kế hoạch du lịch, định hướng nghề nghiệp, phỏng vấn sắp tới (2 ngày trước).
SET @s2_at := NOW(6) - INTERVAL 2 DAY;
INSERT INTO kaiwa_free_session (user_id, status, summary, recent_turns, revision, processed_revision,
                                created_at, updated_at, ended_at)
VALUES (@uid, 'COMPLETED',
        '[demo-seed] Người học kể dự định đi Tokyo cùng bạn vào tháng 3/2027, muốn làm kỹ sư phần mềm ở công ty Nhật sau khi tốt nghiệp. Tuần sau có phỏng vấn thực tập ở công ty Nhật và hẹn sẽ kể lại kết quả.',
        JSON_ARRAY(), 0, 0, @s2_at, @s2_at + INTERVAL 15 MINUTE, @s2_at + INTERVAL 15 MINUTE);
SET @s2 := LAST_INSERT_ID();

INSERT INTO kaiwa_memory (user_id, memory_key, kind, topic, content, importance, source_session_id,
                          evidence_turn_id, evidence, status, observed_at, expires_at, embedding, embedding_model)
VALUES
    (@uid, 'profile.name', 'PROFILE', 'Tên', 'Tên người học là Huy.', 5, @s1,
     NULL, 'はじめまして、フイです。', 'ACTIVE', @s1_at, NULL, NULL, NULL),
    (@uid, 'profile.home', 'PROFILE', 'Nơi sống', 'Người học sống ở Hà Nội, Việt Nam.', 4, @s1,
     NULL, 'ベトナムのハノイに住んでいます。', 'ACTIVE', @s1_at, NULL, NULL, NULL),
    (@uid, 'profile.occupation', 'PROFILE', 'Nghề nghiệp',
     'Người học là sinh viên năm cuối ngành Công nghệ thông tin.', 4, @s1,
     NULL, '大学四年生で、ITを勉強しています。', 'ACTIVE', @s1_at, NULL, NULL, NULL),
    (@uid, 'learning.jlpt_goal', 'GOAL', 'JLPT', 'Muốn đỗ JLPT N3 ở kỳ thi tháng 12/2026.', 5, @s1,
     NULL, '12月のJLPTでN3に合格したいです。', 'ACTIVE', @s1_at, NULL, NULL, NULL),
    (@uid, 'learning.correction_style', 'PREFERENCE', 'Cách sửa lỗi',
     'Thấy khó nhất là nghe hiểu và phân biệt trợ từ は/が; muốn AI sửa lỗi trợ từ khi trò chuyện.', 4, @s1,
     NULL, '聞き取りと「は」と「が」が苦手です。間違えたら直してください。', 'ACTIVE', @s1_at, NULL, NULL, NULL),
    (@uid, 'interest.anime', 'PREFERENCE', 'Sở thích', 'Thích xem anime, gần đây đang xem Frieren.', 3, @s1,
     NULL, 'アニメが好きで、最近はフリーレンを見ています。', 'ACTIVE', @s1_at, NULL, NULL, NULL),
    (@uid, 'event.tokyo_trip', 'EVENT', 'Du lịch Nhật', 'Dự định đi du lịch Tokyo cùng bạn vào tháng 3/2027.', 4, @s2,
     NULL, '来年の三月に友達と東京へ旅行に行く予定です。', 'ACTIVE', @s2_at, NULL, NULL, NULL),
    (@uid, 'career.goal', 'GOAL', 'Nghề nghiệp',
     'Sau khi tốt nghiệp muốn làm kỹ sư phần mềm ở công ty Nhật.', 4, @s2,
     NULL, '卒業したら日本の会社でエンジニアとして働きたいです。', 'ACTIVE', @s2_at, NULL, NULL, NULL),
    (@uid, 'pending.internship_interview', 'PENDING', 'Phỏng vấn thực tập',
     'Tuần sau có buổi phỏng vấn thực tập ở một công ty Nhật; đã hẹn sẽ kể lại kết quả cho AI.', 4, @s2,
     NULL, '来週、日本の会社のインターンの面接があります。結果をまた話しますね。', 'ACTIVE', @s2_at,
     NOW(6) + INTERVAL 30 DAY, NULL, NULL),
    -- Tóm tắt từng phiên (MemoryWorker lưu với key "session.<id>", importance 2).
    (@uid, CONCAT('session.', @s1), 'SUMMARY', 'Tóm tắt phiên',
     'Huy tự giới thiệu (sinh viên CNTT năm cuối ở Hà Nội), mục tiêu JLPT N3 tháng 12/2026, nhờ sửa lỗi trợ từ は/が, thích anime Frieren.',
     2, @s1, NULL, NULL, 'ACTIVE', @s1_at, NULL, NULL, NULL),
    (@uid, CONCAT('session.', @s2), 'SUMMARY', 'Tóm tắt phiên',
     'Huy kể kế hoạch đi Tokyo tháng 3/2027, muốn làm kỹ sư ở công ty Nhật, tuần sau phỏng vấn thực tập và hẹn kể lại kết quả.',
     2, @s2, NULL, NULL, 'ACTIVE', @s2_at, NULL, NULL, NULL)
AS seeded
ON DUPLICATE KEY UPDATE
    kind = seeded.kind, topic = seeded.topic, content = seeded.content, importance = seeded.importance,
    source_session_id = seeded.source_session_id, evidence_turn_id = NULL, evidence = seeded.evidence,
    status = 'ACTIVE', observed_at = seeded.observed_at, expires_at = seeded.expires_at,
    embedding = NULL, embedding_model = NULL, embedding_retry_at = NULL, updated_at = NOW(6);

COMMIT;

SELECT memory_key, kind, content FROM kaiwa_memory WHERE user_id = @uid AND status = 'ACTIVE' ORDER BY kind, memory_key;
