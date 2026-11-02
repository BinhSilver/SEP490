-- [JLAS2-37] J-LAS Community Hub demo fixtures, MySQL 8.0.21+, UTF-8.
-- SYNTHETIC DEMO DATA: rating, binh luan va bao cao duoi day la du lieu mau de demo/kiem thu, khong phai
-- hoat dong that cua nguoi hoc. Khong nap tren production.
-- Can co truoc: jlas_schema.sql (hoac migration 065_community_hub.sql) va cac tai khoan seed trong
-- sql/seeds/jlas_seed_all.sql: demo@jlab.local, content@jlab.local, admin@jlab.local,
-- learner.clean@jlas.local. Tai khoan nao khong co thi cac dong lien quan tu bo qua.
-- Khong co dinh id. Chay lai an toan: bo flashcard mau nhan dien theo (owner, title); rating upsert theo
-- UNIQUE(noi dung, user); binh luan theo (noi dung, user, body); bao cao theo UNIQUE(reporter, doi tuong).
-- Cuoi file tinh lai rating_avg/rating_count/comment_count/report_count tu bang con.
--
-- Du lieu tao ra:
--   * 4 bo flashcard mau tieu de "Cộng đồng: ...": 3 PUBLIC (N3, N2, N1) + 1 PRIVATE (kiem tra khong hien o feed).
--   * Rating va binh luan tren cac bo mau va toi da 3 bo PUBLIC co san khac.
--   * Bo "Cộng đồng: Từ vựng N1 chuyên ngành" o trang thai TEMPORARILY_HIDDEN (1 bao cao OPEN).
--   * 1 binh luan TEMPORARILY_HIDDEN cua learner.clean@jlas.local (1 bao cao OPEN).

SET NAMES utf8mb4;

SET @cm_owner_a := (SELECT id FROM users WHERE email = 'demo@jlab.local' LIMIT 1);
SET @cm_owner_b := (SELECT id FROM users WHERE email = 'content@jlab.local' LIMIT 1);
SET @cm_learner := (SELECT id FROM users WHERE email = 'learner.clean@jlas.local' LIMIT 1);
SET @cm_admin   := (SELECT id FROM users WHERE email = 'admin@jlab.local' LIMIT 1);

-- ── 1. Bo flashcard mau (chi tao khi chu so huu chua co bo song cung tieu de) ─────────────────────────────
DROP TEMPORARY TABLE IF EXISTS tmp_cm_set;
CREATE TEMPORARY TABLE tmp_cm_set (
    owner_id    BIGINT UNSIGNED NULL,
    title       VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    visibility  VARCHAR(20) NOT NULL,
    level       VARCHAR(10) NOT NULL
) ENGINE=MEMORY DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tmp_cm_set (owner_id, title, description, visibility, level) VALUES
    (@cm_owner_b, 'Cộng đồng: 30 Kanji N3 thường gặp', 'Kanji N3 hay xuất hiện trong đề đọc hiểu, kèm âm On/Kun và ví dụ.', 'PUBLIC', 'N3'),
    (@cm_owner_a, 'Cộng đồng: Ngữ pháp N2 qua ví dụ', 'Mẫu ngữ pháp N2 dễ nhầm lẫn, mỗi thẻ một câu ví dụ ngắn.', 'PUBLIC', 'N2'),
    (@cm_owner_b, 'Cộng đồng: Từ vựng N1 chuyên ngành', 'Từ vựng N1 về kinh tế và xã hội (bộ mẫu đang bị báo cáo để demo kiểm duyệt).', 'PUBLIC', 'N1'),
    (@cm_owner_a, 'Cộng đồng: Bộ thẻ riêng tư', 'Bộ thẻ PRIVATE: không được xuất hiện trong feed cộng đồng.', 'PRIVATE', 'N5');

INSERT INTO flashcard_set (owner_id, title, description, visibility, is_draft, moderation_status)
SELECT t.owner_id, t.title, t.description, t.visibility, 0, 'SAFE'
FROM tmp_cm_set t
WHERE t.owner_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM flashcard_set s
                  WHERE s.owner_id = t.owner_id AND s.title = t.title AND s.deleted_at IS NULL);

-- Moi bo mau 3 the cung cap do (chi them khi bo chua co the) de feed suy ra jlpt_level.
INSERT INTO flashcard_card (set_id, front, back, reading, meaning, jlpt_level, position)
SELECT s.id, c.front, c.back, c.reading, c.back, t.level, c.position
FROM tmp_cm_set t
JOIN flashcard_set s ON s.owner_id = t.owner_id AND s.title = t.title AND s.deleted_at IS NULL
JOIN (SELECT 'N3' AS level, 1 AS position, '経済' AS front, 'kinh tế' AS back, 'けいざい' AS reading
      UNION ALL SELECT 'N3', 2, '技術', 'kỹ thuật', 'ぎじゅつ'
      UNION ALL SELECT 'N3', 3, '環境', 'môi trường', 'かんきょう'
      UNION ALL SELECT 'N2', 1, '〜にもかかわらず', 'mặc dù ... nhưng', NULL
      UNION ALL SELECT 'N2', 2, '〜に沿って', 'theo, dọc theo', 'にそって'
      UNION ALL SELECT 'N2', 3, '〜をきっかけに', 'nhân dịp, nhờ ... mà', NULL
      UNION ALL SELECT 'N1', 1, '需要', 'nhu cầu', 'じゅよう'
      UNION ALL SELECT 'N1', 2, '格差', 'chênh lệch', 'かくさ'
      UNION ALL SELECT 'N1', 3, '施策', 'chính sách, biện pháp', 'しさく'
      UNION ALL SELECT 'N5', 1, '水', 'nước', 'みず'
      UNION ALL SELECT 'N5', 2, '山', 'núi', 'やま'
      UNION ALL SELECT 'N5', 3, '川', 'sông', 'かわ') c ON c.level = t.level
WHERE NOT EXISTS (SELECT 1 FROM flashcard_card fc WHERE fc.set_id = s.id);

-- ── 2. Danh sach noi dung duoc gan du lieu cong dong ──────────────────────────────────────────────────────
-- Bo mau PUBLIC + toi da 3 bo PUBLIC co san khac (uu tien du lieu that, id nho nhat de on dinh giua cac lan chay).
DROP TEMPORARY TABLE IF EXISTS tmp_cm_target;
CREATE TEMPORARY TABLE tmp_cm_target (
    set_id   BIGINT UNSIGNED PRIMARY KEY,
    owner_id BIGINT UNSIGNED NOT NULL,
    tag      VARCHAR(20) NOT NULL
) ENGINE=MEMORY DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tmp_cm_target (set_id, owner_id, tag)
SELECT s.id, s.owner_id,
       CASE t.level WHEN 'N3' THEN 'SAMPLE_N3' WHEN 'N2' THEN 'SAMPLE_N2' WHEN 'N1' THEN 'SAMPLE_N1' END
FROM tmp_cm_set t
JOIN flashcard_set s ON s.owner_id = t.owner_id AND s.title = t.title AND s.deleted_at IS NULL
WHERE t.visibility = 'PUBLIC';

INSERT INTO tmp_cm_target (set_id, owner_id, tag)
SELECT x.id, x.owner_id, 'EXISTING'
FROM (SELECT s.id, s.owner_id FROM flashcard_set s
      WHERE s.visibility = 'PUBLIC' AND s.is_draft = 0 AND s.deleted_at IS NULL
        AND s.moderation_status NOT IN ('BLOCKED','UNSAFE','REMOVED')
        AND s.title NOT LIKE 'Cộng đồng: %'
      ORDER BY s.id LIMIT 3) x;

INSERT INTO community_content (content_type, content_id)
SELECT 'FLASHCARD_SET', set_id FROM tmp_cm_target
ON DUPLICATE KEY UPDATE id = id;

-- ── 3. Danh gia (upsert; khong bao gio de chu so huu tu danh gia) ─────────────────────────────────────────
INSERT INTO community_rating (community_content_id, user_id, score)
SELECT cc.id, r.user_id, r.score
FROM tmp_cm_target t
JOIN community_content cc ON cc.content_type = 'FLASHCARD_SET' AND cc.content_id = t.set_id
JOIN (SELECT 'SAMPLE_N3' AS tag, @cm_learner AS user_id, 5 AS score
      UNION ALL SELECT 'SAMPLE_N3', @cm_admin, 4
      UNION ALL SELECT 'SAMPLE_N3', @cm_owner_a, 5
      UNION ALL SELECT 'SAMPLE_N2', @cm_learner, 4
      UNION ALL SELECT 'SAMPLE_N2', @cm_owner_b, 3
      UNION ALL SELECT 'SAMPLE_N1', @cm_learner, 2
      UNION ALL SELECT 'EXISTING', @cm_learner, 4
      UNION ALL SELECT 'EXISTING', @cm_owner_b, 5) r ON r.tag = t.tag
WHERE r.user_id IS NOT NULL AND r.user_id <> t.owner_id
ON DUPLICATE KEY UPDATE score = VALUES(score);

-- ── 4. Binh luan (chi them neu cung user chua co binh luan cung noi dung tren cung noi dung) ──────────────
DROP TEMPORARY TABLE IF EXISTS tmp_cm_comment;
CREATE TEMPORARY TABLE tmp_cm_comment (
    tag     VARCHAR(20) NOT NULL,
    user_id BIGINT UNSIGNED NULL,
    body    VARCHAR(1000) NOT NULL,
    status  VARCHAR(24) NOT NULL,
    age_min INT NOT NULL
) ENGINE=MEMORY DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tmp_cm_comment (tag, user_id, body, status, age_min) VALUES
    ('SAMPLE_N3', @cm_learner, 'Bộ Kanji rất sát đề, mình ôn 3 ngày là nhớ được gần hết.', 'PUBLISHED', 300),
    ('SAMPLE_N3', @cm_admin,   'Nên thêm ví dụ câu cho từng chữ để dễ nhớ hơn.', 'PUBLISHED', 120),
    ('SAMPLE_N3', @cm_owner_a, 'Cảm ơn bạn đã chia sẻ, phần âm On rất rõ ràng.', 'PUBLISHED', 60),
    ('SAMPLE_N2', @cm_owner_b, 'Mẫu 〜をきっかけに giải thích dễ hiểu.', 'PUBLISHED', 240),
    ('SAMPLE_N2', @cm_learner, 'Bình luận mẫu đang bị ẩn do bị báo cáo (dùng để kiểm thử quyền xem).', 'TEMPORARILY_HIDDEN', 90),
    ('EXISTING',  @cm_learner, 'Bộ thẻ gọn, phù hợp ôn nhanh trước giờ học.', 'PUBLISHED', 30);

INSERT INTO community_comment (community_content_id, user_id, body, status, report_count, created_at, updated_at)
SELECT cc.id, c.user_id, c.body, c.status, 0,
       NOW(6) - INTERVAL c.age_min MINUTE, NOW(6) - INTERVAL c.age_min MINUTE
FROM tmp_cm_comment c
JOIN tmp_cm_target t ON t.tag = c.tag
JOIN community_content cc ON cc.content_type = 'FLASHCARD_SET' AND cc.content_id = t.set_id
WHERE c.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM community_comment x
                  WHERE x.community_content_id = cc.id AND x.user_id = c.user_id AND x.body = c.body);

-- ── 5. Bao cao + trang thai tam an ────────────────────────────────────────────────────────────────────────
-- Noi dung: bo N1 mau bi learner.clean bao cao vi pham ban quyen -> TEMPORARILY_HIDDEN.
INSERT IGNORE INTO community_report (reporter_id, target_type, target_id, reason, note, status)
SELECT @cm_learner, 'CONTENT', cc.id, 'COPYRIGHT', 'Nội dung có vẻ chép từ giáo trình có bản quyền (dữ liệu mẫu).', 'OPEN'
FROM tmp_cm_target t
JOIN community_content cc ON cc.content_type = 'FLASHCARD_SET' AND cc.content_id = t.set_id
WHERE t.tag = 'SAMPLE_N1' AND @cm_learner IS NOT NULL AND @cm_learner <> t.owner_id;

UPDATE community_content cc
JOIN tmp_cm_target t ON cc.content_type = 'FLASHCARD_SET' AND cc.content_id = t.set_id
SET cc.status = 'TEMPORARILY_HIDDEN'
WHERE t.tag = 'SAMPLE_N1'
  AND EXISTS (SELECT 1 FROM community_report r
              WHERE r.target_type = 'CONTENT' AND r.target_id = cc.id AND r.status = 'OPEN');

-- Binh luan an: bao cao boi content@jlab.local.
INSERT IGNORE INTO community_report (reporter_id, target_type, target_id, reason, note, status)
SELECT @cm_owner_b, 'COMMENT', x.id, 'SPAM', 'Bình luận mẫu bị báo cáo spam.', 'OPEN'
FROM community_comment x
WHERE @cm_owner_b IS NOT NULL AND x.user_id = @cm_learner AND x.deleted_at IS NULL
  AND x.body = 'Bình luận mẫu đang bị ẩn do bị báo cáo (dùng để kiểm thử quyền xem).';

-- ── 6. Tinh lai bo dem tu bang con (cho moi noi dung cong dong) ───────────────────────────────────────────
UPDATE community_comment x
SET x.report_count = (SELECT COUNT(*) FROM community_report r WHERE r.target_type = 'COMMENT' AND r.target_id = x.id);

UPDATE community_content cc
LEFT JOIN (SELECT community_content_id, COUNT(*) AS n, AVG(score) AS avg_score
           FROM community_rating GROUP BY community_content_id) r ON r.community_content_id = cc.id
LEFT JOIN (SELECT community_content_id, COUNT(*) AS n
           FROM community_comment WHERE status = 'PUBLISHED' AND deleted_at IS NULL
           GROUP BY community_content_id) c ON c.community_content_id = cc.id
LEFT JOIN (SELECT target_id, COUNT(*) AS n FROM community_report WHERE target_type = 'CONTENT'
           GROUP BY target_id) p ON p.target_id = cc.id
SET cc.rating_count = COALESCE(r.n, 0),
    cc.rating_avg = ROUND(COALESCE(r.avg_score, 0), 2),
    cc.comment_count = COALESCE(c.n, 0),
    cc.report_count = COALESCE(p.n, 0);

DROP TEMPORARY TABLE IF EXISTS tmp_cm_comment;
DROP TEMPORARY TABLE IF EXISTS tmp_cm_target;
DROP TEMPORARY TABLE IF EXISTS tmp_cm_set;
