-- =====================================================================
-- J-LAS seed: tai khoan demo bien soan noi dung (CONTENT_CREATOR)
--
-- CHI DUNG CHO MOI TRUONG PHAT TRIEN / DEMO. KHONG chay tren moi truong that
-- (mat khau cong khai trong file nay).
--
-- Dang nhap (form /login chi co truong Email, khong nhan username):
--   email: creator@jlab.local   mat khau: admin123@@
--   username = 'creator' chi luu trong DB (cot users.username), khong dung de dang nhap.
-- Vai CONTENT_CREATOR: mo duoc /textbooks/manage va API /api/dictionary/manage/**.
-- Tai khoan content@jlab.local (CONTENT_CREATOR, trong jlas_seed_all.sql) giu nguyen; file nay
-- khong sua tai khoan do.
--
-- password_hash sinh bang chinh BCryptPasswordEncoder() cua ung dung (SecurityConfig.passwordEncoder,
-- spring-security-crypto, cost 10) va da kiem tra matches("admin123@@", hash) = true.
--
-- Thu tu chay day du (KHONG co lenh USE: chon schema truoc khi chay):
--   1) jlas_schema.sql
--   2) migration: sql/migrations/018_content_source.sql -> sql/jlas_migrations_all.sql ->
--      sql/migrations/019_situation_tag_ba.sql -> sql/migrations/020_align_checks_credit_reference.sql
--      (018/019/020 chi can tren DB tao tu schema cu)
--   3) sql/seeds/seed_data_jlas.sql
--   4) cac seed khac: sql/seeds/jlas_seed_all.sql, sql/seeds/questionbank.sql, sql/seeds/ai_call_scoring_rules.sql ...
--   5) sql/seeds/demo_content_creator.sql (file nay)
--   6) sql/seeds/seed_data_vnjpclub.sql (cuoi cung)
--   mysql -u root -p --default-character-set=utf8mb4 <ten_db> < sql/seeds/demo_content_creator.sql
-- Chay lai duoc nhieu lan: INSERT IGNORE theo uk_role_name / uk_user_email / uk_user_username,
-- sau do chi cap nhat lai dung dong co email creator@jlab.local (vai, mat khau, xac minh).
-- =====================================================================
SET NAMES utf8mb4;

INSERT IGNORE INTO roles (name, description) VALUES
    ('CONTENT_CREATOR', 'Người biên soạn nội dung');

INSERT IGNORE INTO users
    (role_id, premium_plan_id, email, username, password_hash, login_type,
     full_name, gender, japanese_level, credit_balance, is_verified)
VALUES
    ((SELECT id FROM roles WHERE name = 'CONTENT_CREATOR'),
     (SELECT id FROM premium_plans WHERE name = 'FREE'),
     'creator@jlab.local', 'creator', '$2a$10$ZVQmGVcG9/vzxWNITc6uoeDbY2MMpPgiyjSx.GXlxuVB75eUmJSvC',
     'LOCAL', 'Demo Content Creator', 'OTHER', 'N3', 0, 1);

-- Neu tai khoan da co tu truoc (vd bi doi vai/mat khau khi thu), dua ve trang thai demo.
UPDATE users
SET role_id       = (SELECT id FROM (SELECT id FROM roles WHERE name = 'CONTENT_CREATOR') r),
    password_hash = '$2a$10$ZVQmGVcG9/vzxWNITc6uoeDbY2MMpPgiyjSx.GXlxuVB75eUmJSvC',
    login_type    = 'LOCAL',
    is_verified   = 1
WHERE email = 'creator@jlab.local';

SELECT u.id, u.email, u.username, r.name AS role, u.login_type, u.is_verified
FROM users u JOIN roles r ON r.id = u.role_id
WHERE u.email = 'creator@jlab.local';
