-- =====================================================================
-- J-LAS seed: du lieu mo phong cho trang Tong quan he thong (/admin/system/dashboard)
--
-- CHI DUNG CHO MOI TRUONG PHAT TRIEN / DEMO. KHONG chay tren production: day la nguoi dung,
-- giao dich va nhat ky HU CAU, se lam sai so lieu that.
--
-- Nap:
--   650 hoc vien mo phong  dashdemo.0001@jlas.demo ... dashdemo.0650@jlas.demo
--       (khong co mat khau nen khong dang nhap duoc), ngay tao rai trong ~4,7 nam, cang gan hien tai
--       cang nhieu; 1/5 so tai khoan dang co Premium con han.
--   900 giao dich Premium   order_code DASHDEMO-00001 ... (provider PAYOS) trong ~22 thang gan day:
--       phan lon PAID, mot it PENDING / FAILED / CANCELLED / EXPIRED.
--   10 dong nhat ky quan tri gan day (changes_json giong het backend ghi, them khoa "seed":"dashboard-demo"
--       de nhan ra khi xoa; trang nhat ky khong hien khoa nay), nguoi thao tac la
--       sysadmin@jlab.local neu co (sql/seeds/demo_system_admin.sql), khong thi la ADMIN co id nho nhat.
--
-- Moi moc thoi gian tinh tu NOW() luc chay, nen the 7 ngay / 30 ngay / nam nay luon co so lieu.
-- Chay lai duoc: moi lan chay xoa sach du lieu mo phong cu (theo email / order_code / dau danh) roi nap lai.
-- Can co role LEARNER va goi MONTHLY, YEARLY (jlas_schema.sql / seed goc). MySQL 8 (WITH RECURSIVE).
-- KHONG co lenh USE: chon schema truoc khi chay, vi du
--   mysql -u root -p --default-character-set=utf8mb4 jlas < sql/seeds/demo_admin_dashboard.sql
-- Go bo: chay 3 lenh DELETE o phan 1 ben duoi.
-- =====================================================================
SET NAMES utf8mb4;

-- 1) Xoa du lieu mo phong cua lan chay truoc ------------------------------------------------------
DELETE FROM admin_audit_log
WHERE changes_json LIKE '%"seed": "dashboard-demo"%' OR changes_json LIKE '%"demo":"dashboard-seed"%';
DELETE FROM payments WHERE provider = 'PAYOS' AND order_code LIKE 'DASHDEMO-%';
DELETE FROM users WHERE email LIKE 'dashdemo.%@jlas.demo';

SET @learner = (SELECT id FROM roles WHERE name = 'LEARNER');
SET @monthly = (SELECT id FROM premium_plans WHERE name = 'MONTHLY');
SET @yearly = (SELECT id FROM premium_plans WHERE name = 'YEARLY');

-- 2) Hoc vien: ngay tao = NOW - 1700 * (n/650)^1.6 ngay -> nhieu nguoi dang ky gan day hon ----------
INSERT INTO users (role_id, premium_plan_id, email, username, password_hash, login_type, full_name,
                   japanese_level, premium_expired_at, credit_balance, is_verified, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 650),
learner AS (
    SELECT n, DATE_SUB(DATE_SUB(NOW(6), INTERVAL FLOOR(1700 * POW(n / 650, 1.6)) DAY),
                       INTERVAL MOD(n * 37, 1440) MINUTE) AS created_at
    FROM seq
)
SELECT @learner,
       CASE WHEN MOD(n, 5) = 0 THEN IF(MOD(n, 10) = 0, @yearly, @monthly) END,
       CONCAT('dashdemo.', LPAD(n, 4, '0'), '@jlas.demo'), NULL, NULL, 'LOCAL',
       CONCAT(ELT(1 + MOD(n, 12), 'Nguyễn', 'Trần', 'Lê', 'Phạm', 'Hoàng', 'Vũ', 'Đặng', 'Bùi', 'Đỗ', 'Hồ', 'Ngô', 'Dương'), ' ',
              ELT(1 + MOD(n * 7, 6), 'Văn', 'Thị', 'Minh', 'Ngọc', 'Gia', 'Thu'), ' ',
              ELT(1 + MOD(n * 13, 14), 'An', 'Bình', 'Chi', 'Dũng', 'Hà', 'Khoa', 'Lan', 'Linh', 'Nam', 'Phúc', 'Quân', 'Trang', 'Vy', 'Yến')),
       ELT(1 + MOD(n, 5), 'N5', 'N5', 'N4', 'N3', 'N2'),
       CASE WHEN MOD(n, 5) = 0 THEN DATE_ADD(NOW(6), INTERVAL 5 + MOD(n * 11, 300) DAY) END,
       MOD(n * 17, 200), 1, created_at, created_at
FROM learner;

-- 3) Giao dich Premium trong ~22 thang gan day (cang gan hien tai cang nhieu). Moi giao dich thuoc ve mot
--    hoc vien mo phong da co tai khoan truoc thoi diem thanh toan (n >= 650 * (so ngay / 1700)^0.625).
INSERT INTO payments (user_id, premium_plan_id, product_type, provider, order_code, provider_transaction_id,
                      status, amount_paid, original_amount, currency, response_code, plan_name_snapshot,
                      duration_days_snapshot, paid_at, premium_until, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 900),
timing AS (
    SELECT n,
           CASE WHEN MOD(n, 25) = 3 THEN 'PENDING'
                WHEN MOD(n, 40) = 7 THEN 'FAILED'
                WHEN MOD(n, 45) = 11 THEN 'CANCELLED'
                WHEN MOD(n, 60) = 19 THEN 'EXPIRED'
                ELSE 'PAID' END AS status,
           IF(MOD(n, 4) = 0, @yearly, @monthly) AS plan_id,
           IF(MOD(n, 25) = 3, 0, FLOOR(660 * POW(MOD(n * 53, 900) / 900, 1.3))) AS days_ago
    FROM seq
),
pay AS (
    SELECT timing.*,
           IF(status = 'PENDING', DATE_SUB(NOW(6), INTERVAL MOD(n * 7, 40) HOUR),
              DATE_SUB(DATE_SUB(NOW(6), INTERVAL days_ago DAY), INTERVAL MOD(n * 29, 1440) MINUTE)) AS at,
           GREATEST(1, CEIL(650 * POW((days_ago + 2) / 1700, 0.625))) AS first_user
    FROM timing
)
SELECT u.id, pay.plan_id, 'PREMIUM', 'PAYOS', CONCAT('DASHDEMO-', LPAD(pay.n, 5, '0')),
       IF(pay.status = 'PAID', CONCAT('DASHDEMO-TX-', LPAD(pay.n, 5, '0')), NULL),
       pay.status, p.price, p.price, 'VND', IF(pay.status = 'PAID', '00', NULL), p.name, p.duration_days,
       IF(pay.status = 'PAID', pay.at, NULL),
       IF(pay.status = 'PAID', DATE_ADD(pay.at, INTERVAL p.duration_days DAY), NULL),
       pay.at, pay.at
FROM pay
JOIN premium_plans p ON p.id = pay.plan_id
JOIN users u ON u.email = CONCAT('dashdemo.',
        LPAD(pay.first_user + MOD(pay.n * 7, 651 - pay.first_user), 4, '0'), '@jlas.demo');

-- 4) Nhat ky quan tri gan day: changes_json cung dang AdminUserService / AdminPlanService / AdminCatalogService ghi.
SET @actor = COALESCE((SELECT id FROM users WHERE email = 'sysadmin@jlab.local'),
                      (SELECT MIN(u.id) FROM users u JOIN roles r ON r.id = u.role_id WHERE r.name = 'ADMIN'));
SET @iso = '%Y-%m-%dT%H:%i:%s';
INSERT INTO admin_audit_log (actor_user_id, action, target_type, target_id, reason, result, changes_json, created_at)
SELECT @actor, a.action, a.target_type,
       CASE WHEN a.demo_user = -1 THEN CAST(@actor AS CHAR)
            WHEN a.target_type = 'USER' THEN
                 (SELECT CAST(id AS CHAR) FROM users WHERE email = CONCAT('dashdemo.', LPAD(a.demo_user, 4, '0'), '@jlas.demo'))
            WHEN a.target_type = 'PREMIUM_PLAN' THEN CAST(@monthly AS CHAR)
            ELSE CAST(a.demo_user AS CHAR) END,
       a.reason, a.result, CAST(JSON_SET(a.changes, '$.seed', 'dashboard-demo') AS CHAR),
       DATE_SUB(NOW(6), INTERVAL a.minutes_ago MINUTE)
FROM (
    SELECT 'USER_LOCK' AS action, 'USER' AS target_type, 12 AS demo_user, 'Spam bình luận liên tục' AS reason,
           'SUCCESS' AS result, 18 AS minutes_ago,
           JSON_OBJECT('before', JSON_OBJECT('status', 'ACTIVE'), 'after', JSON_OBJECT('status', 'LOCKED')) AS changes
    UNION ALL SELECT 'PREMIUM_GRANT', 'USER', 45, 'Bù Premium do lỗi thanh toán', 'SUCCESS', 95,
           JSON_OBJECT('before', JSON_OBJECT('plan', NULL, 'expiresAt', NULL, 'active', FALSE),
                       'after', JSON_OBJECT('plan', 'MONTHLY', 'expiresAt', DATE_FORMAT(NOW() + INTERVAL 30 DAY, @iso), 'active', TRUE),
                       'durationDays', 30, 'stacked', FALSE)
    UNION ALL SELECT 'USER_CREDIT_ADJUST', 'USER', 78, 'Tặng CP sự kiện JLPT', 'SUCCESS', 160,
           JSON_OBJECT('amount', 500, 'before', 120, 'after', 620)
    UNION ALL SELECT 'PREMIUM_PLAN_UPDATE', 'PREMIUM_PLAN', 0, 'Cập nhật mô tả gói tháng', 'SUCCESS', 300,
           JSON_OBJECT('before', JSON_OBJECT('name', 'MONTHLY', 'description', 'Monthly premium', 'price', '10000', 'durationDays', 30),
                       'after', JSON_OBJECT('name', 'MONTHLY', 'description', 'Premium 1 tháng: mở khoá toàn bộ bài học', 'price', '10000', 'durationDays', 30))
    UNION ALL SELECT 'USER_ROLE_CHANGE', 'USER', 101, 'Thêm người biên soạn cho nhóm N3', 'SUCCESS', 520,
           JSON_OBJECT('before', JSON_OBJECT('role', 'LEARNER'), 'after', JSON_OBJECT('role', 'CONTENT_CREATOR'))
    UNION ALL SELECT 'USER_LOCK', 'USER', -1, 'Thử khoá tài khoản đang đăng nhập', 'REJECTED', 900,
           JSON_OBJECT('rejection', 'Không thể tự khoá tài khoản của mình.')
    UNION ALL SELECT 'USER_UNLOCK', 'USER', 12, 'Đã xác minh lại chủ tài khoản', 'SUCCESS', 1500,
           JSON_OBJECT('before', JSON_OBJECT('status', 'LOCKED'), 'after', JSON_OBJECT('status', 'ACTIVE'))
    UNION ALL SELECT 'COUPON_CREATE', 'COUPON', 1, 'Mã giảm giá mùa thi JLPT', 'SUCCESS', 2200,
           JSON_OBJECT('code', 'JLPT2026')
    UNION ALL SELECT 'CREDIT_PACKAGE_UPDATE', 'CREDIT_PACKAGE', 1, 'Điều chỉnh giá gói CP', 'SUCCESS', 3100,
           JSON_OBJECT('before', JSON_OBJECT('name', 'Gói 100 CP', 'description', NULL, 'creditAmount', 100, 'price', 20000, 'active', TRUE),
                       'after', JSON_OBJECT('name', 'Gói 100 CP', 'description', NULL, 'creditAmount', 100, 'price', 15000, 'active', TRUE))
    UNION ALL SELECT 'PREMIUM_GRANT', 'USER', 230, 'Học bổng Premium cho học viên', 'SUCCESS', 4300,
           JSON_OBJECT('before', JSON_OBJECT('plan', 'MONTHLY', 'expiresAt', DATE_FORMAT(NOW() + INTERVAL 12 DAY, @iso), 'active', TRUE),
                       'after', JSON_OBJECT('plan', 'YEARLY', 'expiresAt', DATE_FORMAT(NOW() + INTERVAL 377 DAY, @iso), 'active', TRUE),
                       'durationDays', 365, 'stacked', TRUE)
) a
WHERE @actor IS NOT NULL;
