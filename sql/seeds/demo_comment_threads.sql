-- =====================================================================
-- J-LAS seed: tra loi + tha cam xuc mo phong cho binh luan video Shadowing (migration 056)
--
-- CHI DUNG CHO MOI TRUONG PHAT TRIEN / DEMO. KHONG chay tren production: day la hoat dong HU CAU.
--
-- Nguoi tham gia: cac hoc vien mau cua seed Shadowing (tools/demo_learners.py) - LEARNER, khong mat khau,
-- chua xac thuc, LOCAL, khong Google - va da co binh luan video. Tai khoan that luon co mat khau hoac
-- Google nen khong bao gio bi dung toi.
--
-- Nap:
--   - Tra loi: khoang 1/3 binh luan goc co 1 tra loi, 1/5 co 2 tra loi (tra loi thu hai goi @ten nguoi viet
--     binh luan goc). Thoi diem tra loi luon sau binh luan goc va truoc hien tai.
--   - Cam xuc: hau het binh luan goc co 1-9 nguoi tha, tra loi co 0-3; moi binh luan tron 2-3 loai cam xuc
--     (phan lon LIKE/LOVE/HAHA/CLAP, thinh thoang WOW/SAD) nen thanh tong hop hien nhieu bieu tuong.
--
-- Chay sau sql/migrations/056_comment_replies_reactions.sql va seed tuong tac Shadowing
-- (seed_data_shadowing_interactions.sql). Chay lai duoc: moi lan chay xoa tra loi + cam xuc cua hoc vien
-- mau roi nap lai. KHONG co lenh USE: chon schema truoc khi chay, vi du
--   mysql -u root -p --default-character-set=utf8mb4 jlas < sql/seeds/demo_comment_threads.sql
-- =====================================================================
SET NAMES utf8mb4;

DROP TEMPORARY TABLE IF EXISTS seed_learner;
CREATE TEMPORARY TABLE seed_learner (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    n  INT NOT NULL,
    KEY idx_seed_learner_n (n)
);
INSERT INTO seed_learner (id, n)
SELECT u.id, ROW_NUMBER() OVER (ORDER BY u.id)
FROM users u
JOIN roles r ON r.id = u.role_id
WHERE r.name = 'LEARNER' AND u.password_hash IS NULL AND u.google_id IS NULL AND u.login_type = 'LOCAL'
  AND u.is_verified = 0
  AND u.id IN (SELECT DISTINCT user_id FROM content_comment WHERE target_type = 'VIDEO');
SET @pool = (SELECT COUNT(*) FROM seed_learner);

-- 1) Xoa du lieu cua lan chay truoc (chi cua hoc vien mau) -----------------------------------------
DELETE FROM content_comment_reaction WHERE user_id IN (SELECT id FROM seed_learner);
DELETE FROM content_comment
WHERE target_type = 'VIDEO' AND parent_id IS NOT NULL AND user_id IN (SELECT id FROM seed_learner);

-- 2) Tra loi -----------------------------------------------------------------------------------------
INSERT INTO content_comment (target_type, target_id, parent_id, user_id, content, is_hidden, created_at, updated_at)
SELECT 'VIDEO', c.target_id, c.id, l.id,
       CONCAT(IF(k.k = 2, CONCAT('@', COALESCE(TRIM(SUBSTRING_INDEX(pu.full_name, ' (', 1)), 'bạn'), ' '), ''),
              ELT(1 + MOD(c.id * 7 + k.k * 5, 16),
                  'Mình cũng thấy vậy, nghe lại lần hai thì rõ hơn hẳn.',
                  'Cảm ơn bạn, để mình thử bật tốc độ 0.75 xem sao.',
                  'Đoạn đó mình phải tua lại mấy lần mới bắt được.',
                  'Bạn luyện bao lâu thì nói theo kịp vậy?',
                  'Chuẩn luôn, phần phụ đề song ngữ giúp nhiều lắm.',
                  'Mình đang học N3, thấy bài này vừa sức.',
                  'Thử tắt phụ đề tiếng Việt đi bạn, nhớ lâu hơn đó.',
                  'Đồng ý! Giọng nhân vật này dễ nghe nhất bộ.',
                  'Mình hay chép mấy câu hay vào sổ tay để ôn lại.',
                  'Hay quá, cảm ơn bạn đã chia sẻ mẹo này.',
                  'Mình hay vấp ở mấy chỗ nối âm, bạn có mẹo gì không?',
                  'Nghe chậm từng câu rồi mới nói theo cả đoạn là ổn nhất.',
                  'Câu này mình thu âm năm lần mới được điểm cao, haha.',
                  'Bài này hợp để luyện ngữ điệu thật sự.',
                  'Mình thấy phần cuối nói nhanh hơn hẳn phần đầu.',
                  'Ồ vậy hả, tối nay mình thử luyện lại xem.')),
       0, at.t, at.t
FROM content_comment c
JOIN users pu ON pu.id = c.user_id
JOIN (SELECT 1 AS k UNION ALL SELECT 2) k
  ON k.k <= CASE WHEN MOD(c.id, 5) = 0 THEN 2 WHEN MOD(c.id, 3) = 0 THEN 1 ELSE 0 END
JOIN seed_learner l ON l.n = 1 + MOD(c.id * 17 + k.k * 29, @pool)
JOIN LATERAL (
    SELECT GREATEST(c.created_at + INTERVAL k.k MINUTE,
                    LEAST(NOW(6) - INTERVAL 10 MINUTE,
                          c.created_at + INTERVAL (k.k * 3 + MOD(c.id * k.k, 40)) HOUR + INTERVAL MOD(c.id * 11, 60) MINUTE)) AS t
) at
WHERE c.target_type = 'VIDEO' AND c.parent_id IS NULL AND c.is_hidden = 0 AND l.id <> c.user_id;

-- 3) Cam xuc: moi binh luan co 2 loai chinh (a, b trong LIKE/LOVE/HAHA/CLAP) va thinh thoang mot loai phu
--    (c: WOW hoac SAD); nguoi thu j tha a, b hoac c nen moi binh luan tron 2-3 loai -----------------
INSERT IGNORE INTO content_comment_reaction (comment_id, user_id, reaction, created_at)
SELECT c.id, l.id,
       CASE WHEN j.j IN (3, 9) THEN IF(MOD(c.id, 3) = 0, 'SAD', 'WOW')
            WHEN MOD(j.j, 2) = 0 THEN ELT(1 + MOD(c.id + 1, 5), 'LOVE', 'LIKE', 'HAHA', 'LIKE', 'CLAP')
            ELSE ELT(1 + MOD(c.id, 5), 'LIKE', 'LOVE', 'LIKE', 'CLAP', 'HAHA') END,
       LEAST(NOW(6), c.created_at + INTERVAL (j.j * 2 + MOD(c.id, 30)) HOUR)
FROM content_comment c
JOIN (SELECT 1 AS j UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
      UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) j
  ON j.j <= CASE WHEN c.parent_id IS NOT NULL THEN MOD(c.id * 3, 4)
                 WHEN MOD(c.id, 7) = 0 THEN 0
                 ELSE 1 + MOD(c.id * 7, 9) END
JOIN seed_learner l ON l.n = 1 + MOD(c.id * 13 + j.j * 37, @pool)
WHERE c.target_type = 'VIDEO' AND c.is_hidden = 0 AND l.id <> c.user_id;

DROP TEMPORARY TABLE IF EXISTS seed_learner;
