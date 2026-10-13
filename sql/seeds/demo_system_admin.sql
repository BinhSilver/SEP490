-- =====================================================================
-- J-LAS seed: tai khoan demo Quan tri he thong (SUPER_ADMIN) + du lieu de test (JLAS2-51)
--
-- CHI DUNG CHO MOI TRUONG PHAT TRIEN / DEMO. KHONG chay tren moi truong that
-- (mat khau cong khai trong file nay).
--
-- Dang nhap (form /login, truong Email), mat khau chung: SysAdmin@123
--   sysadmin@jlab.local          SUPER_ADMIN  vao /admin -> /admin/system/users
--   sa.free@jlab.local           LEARNER      goi FREE (de thu cap Premium tu dau)
--   sa.premium@jlab.local        LEARNER      MONTHLY con 20 ngay (de thu cap Premium cong don)
--   sa.expired@jlab.local        LEARNER      MONTHLY het han 3 ngay truoc (cap lai tinh tu hien tai)
--   sa.locked@jlab.local         LEARNER      dang bi khoa (de thu mo khoa / dang nhap bi chan)
--   sa.creator@jlab.local        CONTENT_CREATOR (Content Admin)
--   sa.moderator@jlab.local      MODERATOR
--
-- password_hash sinh bang BCryptPasswordEncoder() cua ung dung (spring-security-crypto, cost 10)
-- va da kiem tra matches("SysAdmin@123", hash) = true.
--
-- Chay SAU jlas_schema.sql (+ sql/migrations/028_system_admin_core.sql neu DB tao tu schema cu)
-- va cac seed khac. KHONG co lenh USE: chon schema truoc khi chay, vi du
--   mysql -u root -p --default-character-set=utf8mb4 <ten_db> < sql/seeds/demo_system_admin.sql
-- Chay lai duoc: moi lan chay dat lai vai tro, mat khau, trang thai Premium/khoa cua cac tai khoan tren;
-- khong dung toi tai khoan khac.
-- =====================================================================
SET NAMES utf8mb4;
SET @pwd = '$2a$10$yrDCDiLvOCDGGxVtwayGlexqXVOsjMUAtxAQSNPtKAMlykbppPnXa';

INSERT IGNORE INTO roles (name, description) VALUES
    ('LEARNER',         'Learner'),
    ('CONTENT_CREATOR', 'Content Admin: soan va quan ly noi dung hoc'),
    ('MODERATOR',       'Kiem duyet noi dung cong dong, tu khoa cam, khang nghi, dong gop tu dien'),
    ('ADMIN',           'Quan tri: toan bo quyen kiem duyet va che tai nguoi dung'),
    ('SUPER_ADMIN',     'System Admin');

INSERT IGNORE INTO premium_plans (name, description, price, duration_days, is_active) VALUES
    ('FREE',    'Free tier',       0.00, 36500, 1),
    ('MONTHLY', 'Monthly premium', 9.99, 30,    1),
    ('YEARLY',  'Yearly premium',  99.99, 365,  1);

INSERT INTO users (role_id, premium_plan_id, email, username, password_hash, login_type, full_name,
                   japanese_level, premium_expired_at, credit_balance, is_verified, locked_at, locked_reason) VALUES
  ((SELECT id FROM roles WHERE name = 'SUPER_ADMIN'), NULL,
   'sysadmin@jlab.local', 'sysadmin', @pwd, 'LOCAL', 'Quản trị hệ thống (demo)', NULL, NULL, 0, 1, NULL, NULL),
  ((SELECT id FROM roles WHERE name = 'LEARNER'), (SELECT id FROM premium_plans WHERE name = 'FREE'),
   'sa.free@jlab.local', 'sa_free', @pwd, 'LOCAL', 'Phạm Thu Hà (Free)', 'N5', NULL, 20, 1, NULL, NULL),
  ((SELECT id FROM roles WHERE name = 'LEARNER'), (SELECT id FROM premium_plans WHERE name = 'MONTHLY'),
   'sa.premium@jlab.local', 'sa_premium', @pwd, 'LOCAL', 'Đỗ Quang Huy (Premium)', 'N4',
   DATE_ADD(NOW(6), INTERVAL 20 DAY), 150, 1, NULL, NULL),
  ((SELECT id FROM roles WHERE name = 'LEARNER'), (SELECT id FROM premium_plans WHERE name = 'MONTHLY'),
   'sa.expired@jlab.local', 'sa_expired', @pwd, 'LOCAL', 'Vũ Ngọc Lan (Premium hết hạn)', 'N4',
   DATE_SUB(NOW(6), INTERVAL 3 DAY), 5, 1, NULL, NULL),
  ((SELECT id FROM roles WHERE name = 'LEARNER'), (SELECT id FROM premium_plans WHERE name = 'FREE'),
   'sa.locked@jlab.local', 'sa_locked', @pwd, 'LOCAL', 'Hoàng Minh Tuấn (đang bị khoá)', 'N5', NULL, 0, 1,
   NOW(6), 'Dữ liệu demo: tài khoản bị khoá sẵn để kiểm tra mở khoá'),
  ((SELECT id FROM roles WHERE name = 'CONTENT_CREATOR'), NULL,
   'sa.creator@jlab.local', 'sa_creator', @pwd, 'LOCAL', 'Nguyễn Bảo Anh (Content Admin)', NULL, NULL, 0, 1, NULL, NULL),
  ((SELECT id FROM roles WHERE name = 'MODERATOR'), NULL,
   'sa.moderator@jlab.local', 'sa_moderator', @pwd, 'LOCAL', 'Trịnh Gia Bảo (Kiểm duyệt)', NULL, NULL, 0, 1, NULL, NULL)
ON DUPLICATE KEY UPDATE
  role_id = VALUES(role_id), premium_plan_id = VALUES(premium_plan_id), password_hash = VALUES(password_hash),
  login_type = VALUES(login_type), full_name = VALUES(full_name), premium_expired_at = VALUES(premium_expired_at),
  is_verified = VALUES(is_verified), locked_at = VALUES(locked_at), locked_reason = VALUES(locked_reason);

-- The pre-locked account gets the audit row a real lock would have written (once).
INSERT INTO admin_audit_log (actor_user_id, action, target_type, target_id, reason, result, changes_json)
SELECT a.id, 'USER_LOCK', 'USER', CAST(t.id AS CHAR), t.locked_reason, 'SUCCESS',
       '{"before":{"status":"ACTIVE"},"after":{"status":"LOCKED"}}'
  FROM users a JOIN users t ON t.email = 'sa.locked@jlab.local'
 WHERE a.email = 'sysadmin@jlab.local'
   AND NOT EXISTS (SELECT 1 FROM admin_audit_log l
                    WHERE l.action = 'USER_LOCK' AND l.target_type = 'USER'
                      AND l.target_id = CAST(t.id AS CHAR) COLLATE utf8mb4_unicode_ci);

SELECT u.email, r.name AS role, p.name AS plan, u.premium_expired_at, u.locked_at IS NOT NULL AS locked
  FROM users u JOIN roles r ON r.id = u.role_id LEFT JOIN premium_plans p ON p.id = u.premium_plan_id
 WHERE u.email LIKE 'sa.%@jlab.local' OR u.email = 'sysadmin@jlab.local'
 ORDER BY u.email;
