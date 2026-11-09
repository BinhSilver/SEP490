-- Dua video Shadowing cua seed len trang thai san sang va bo 5 muc mau khoi thu vien cong khai.
-- Chay lai an toan: moi lenh deu loc theo trang thai hien tai hoac theo (chu so huu, tieu de).
--
-- Seed Shadowing nap video o DRAFT/PRIVATE (tools/shadowing_seed/seed_template.sql, @shadowing_seed_publish mac dinh 0),
-- nen thu vien cong khai gan nhu trong. Chi dung den video co dau MANUAL_APPROVAL ma seed ghi vao moderation_summary;
-- video nguoi dung tu dang khong bi anh huong. Video chua co phu de giu nguyen vi khong hoc duoc.
SET NAMES utf8mb4;

START TRANSACTION;

UPDATE video v
SET v.status = 'READY', v.visibility = 'PUBLIC', v.processing_step = 'DONE', v.progress_percent = 100
WHERE v.moderation_summary LIKE 'MANUAL_APPROVAL:%'
  AND v.moderation_status = 'SAFE'
  AND v.status = 'DRAFT'
  AND EXISTS (SELECT 1 FROM subtitle s WHERE s.video_id = v.id);

-- Danh sach chi cong khai khi moi video trong do deu la video seed da cong khai.
UPDATE shadowing_series s
SET s.visibility = 'PUBLIC'
WHERE s.visibility = 'PRIVATE'
  AND EXISTS (SELECT 1 FROM shadowing_series_video sv WHERE sv.series_id = s.id)
  AND NOT EXISTS (SELECT 1 FROM shadowing_series_video sv JOIN video v ON v.id = sv.video_id
                  WHERE sv.series_id = s.id
                    AND (v.status <> 'READY' OR v.visibility <> 'PUBLIC' OR v.moderation_status <> 'SAFE'
                         OR v.moderation_summary IS NULL OR v.moderation_summary NOT LIKE 'MANUAL_APPROVAL:%'));

-- Chu so huu noi dung he thong hien ten "He thong" tren thu vien.
UPDATE users SET full_name = 'Hệ thống' WHERE email = 'content@jlab.local' AND full_name = 'Demo Content Admin';

-- 5 muc mau cua seed Community Hub (video 7 giay, bo 3 the), khong con duoc tao tu seed.
DROP TEMPORARY TABLE IF EXISTS tmp_sample_video;
CREATE TEMPORARY TABLE tmp_sample_video (id BIGINT UNSIGNED PRIMARY KEY) ENGINE=MEMORY;
INSERT INTO tmp_sample_video (id)
SELECT v.id FROM video v JOIN users u ON u.id = v.owner_id
WHERE (u.email = 'content@jlab.local' AND v.title = 'Cộng đồng: Hội thoại mua sắm ở konbini')
   OR (u.email = 'demo@jlab.local' AND v.title = 'Cộng đồng: Hỏi đường ở nhà ga');

DROP TEMPORARY TABLE IF EXISTS tmp_sample_set;
CREATE TEMPORARY TABLE tmp_sample_set (id BIGINT UNSIGNED PRIMARY KEY) ENGINE=MEMORY;
INSERT INTO tmp_sample_set (id)
SELECT s.id FROM flashcard_set s JOIN users u ON u.id = s.owner_id
WHERE s.deleted_at IS NULL
  AND ((u.email = 'content@jlab.local' AND s.title IN ('Cộng đồng: 30 Kanji N3 thường gặp', 'Cộng đồng: Từ vựng N1 chuyên ngành'))
    OR (u.email = 'demo@jlab.local' AND s.title = 'Cộng đồng: Ngữ pháp N2 qua ví dụ'));

DROP TEMPORARY TABLE IF EXISTS tmp_sample_content;
CREATE TEMPORARY TABLE tmp_sample_content (id BIGINT UNSIGNED PRIMARY KEY) ENGINE=MEMORY;
INSERT INTO tmp_sample_content (id)
SELECT cc.id FROM community_content cc JOIN tmp_sample_video t ON cc.content_type = 'SHADOWING_VIDEO' AND cc.content_id = t.id;
INSERT INTO tmp_sample_content (id)
SELECT cc.id FROM community_content cc JOIN tmp_sample_set t ON cc.content_type = 'FLASHCARD_SET' AND cc.content_id = t.id;

DELETE r FROM community_report r JOIN community_comment x ON r.target_type = 'COMMENT' AND r.target_id = x.id
    JOIN tmp_sample_content t ON t.id = x.community_content_id;
DELETE r FROM community_report r JOIN tmp_sample_content t ON r.target_type = 'CONTENT' AND r.target_id = t.id;
DELETE x FROM community_comment x JOIN tmp_sample_content t ON t.id = x.community_content_id;
DELETE r FROM community_rating r JOIN tmp_sample_content t ON t.id = r.community_content_id;
DELETE cc FROM community_content cc JOIN tmp_sample_content t ON t.id = cc.id;

DELETE h FROM user_view_history h JOIN tmp_sample_video t ON h.item_type = 'SHADOWING_VIDEO' AND h.item_id = t.id;
DELETE h FROM user_view_history h JOIN tmp_sample_set t ON h.item_type = 'FLASHCARD_SET' AND h.item_id = t.id;
DELETE sv FROM shadowing_series_video sv JOIN tmp_sample_video t ON t.id = sv.video_id;
-- Cac bang con con lai cua video va bo the tu xoa theo ON DELETE CASCADE.
DELETE v FROM video v JOIN tmp_sample_video t ON t.id = v.id;

-- game_match khong cho xoa cung: bo the da co tran dau thi chi an di, giong cach seed flashcard giao trinh go bo cu.
UPDATE flashcard_set s JOIN tmp_sample_set t ON t.id = s.id
SET s.deleted_at = CURRENT_TIMESTAMP(6)
WHERE EXISTS (SELECT 1 FROM game_match m WHERE m.flashcard_set_id = s.id);
DELETE s FROM flashcard_set s JOIN tmp_sample_set t ON t.id = s.id
WHERE NOT EXISTS (SELECT 1 FROM game_match m WHERE m.flashcard_set_id = s.id);

DROP TEMPORARY TABLE IF EXISTS tmp_sample_content;
DROP TEMPORARY TABLE IF EXISTS tmp_sample_set;
DROP TEMPORARY TABLE IF EXISTS tmp_sample_video;

COMMIT;
