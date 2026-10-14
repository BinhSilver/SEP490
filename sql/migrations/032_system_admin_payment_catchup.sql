-- =====================================================================================================
-- [JLAS2-51][JLAS2-49] BO SUNG DB cho may con thieu: dua DB dung tu nhanh develop (hoac schema cu) len dung cau truc
-- cua nhanh System Admin + Thanh toan. Gop san 028 + 029 + 030 + 031 vao 1 file, khop jlas_schema.sql:
--   1. System Admin   users.locked_at / locked_reason (khoa tai khoan), bang admin_audit_log, vai tro SUPER_ADMIN
--   2. Gop vai tro    ADMIN -> SUPER_ADMIN, CONTENT_ADMIN -> CONTENT_CREATOR (chuyen tai khoan roi xoa vai tro cu)
--   3. Thanh toan     credit_packages (goi CP), coupons (ma giam gia), payments them cot PayOS / CP / coupon,
--                     trang thai EXPIRED, premium_plan_id + duration_days_snapshot cho phep NULL (don CP)
--   4. Du lieu        3 goi CP; gia Premium tam 9.99 / 99.99 -> 10000 / 100000 VND
-- Moi buoc kiem tra information_schema truoc, cai gi da co thi bo qua -> CHAY LAI DUOC, KHONG xoa du lieu
-- (tru 2 vai tro cu ADMIN / CONTENT_ADMIN sau khi da chuyen het tai khoan sang vai tro moi).
-- Can bang users, roles, premium_plans da co. Khong co lenh USE: chon DB truoc khi chay. MySQL 8.0.16+.
--   mysql -u root -p --default-character-set=utf8mb4 jlas < sql/migrations/032_system_admin_payment_catchup.sql
--   (MySQL Workbench: mo file, chon schema jlas, bam Execute)
-- Chay TRUOC khi khoi dong app (ddl-auto: validate). Tai khoan demo System Admin: chay them
-- sql/seeds/demo_system_admin.sql (mat khau SysAdmin@123) neu muon.
-- =====================================================================================================
SET NAMES utf8mb4;

-- ===================================================================================== 1. System Admin
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'locked_at');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE users ADD COLUMN locked_at DATETIME(6) NULL AFTER last_login_at', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'locked_reason');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE users ADD COLUMN locked_reason VARCHAR(500) NULL AFTER locked_at', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT UNSIGNED NOT NULL,
    action VARCHAR(50) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_id VARCHAR(64) NULL,
    reason VARCHAR(500) NULL,
    result VARCHAR(20) NOT NULL,
    changes_json TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_admin_audit_created (created_at),
    KEY idx_admin_audit_actor_created (actor_user_id, created_at),
    KEY idx_admin_audit_target (target_type, target_id),
    KEY idx_admin_audit_action_created (action, created_at),
    CONSTRAINT fk_admin_audit_actor FOREIGN KEY (actor_user_id) REFERENCES users(id),
    CONSTRAINT chk_admin_audit_result CHECK (result IN ('SUCCESS','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===================================================================================== 2. Gop vai tro
-- 4 vai tro chuan: LEARNER, CONTENT_CREATOR, MODERATOR, SUPER_ADMIN (Premium la LEARNER co goi con han).
INSERT IGNORE INTO roles (name, description) VALUES
    ('SUPER_ADMIN',     'System Admin: quan tri he thong, kiem duyet va che tai nguoi dung'),
    ('CONTENT_CREATOR', 'Content Creator: soan, quan ly va duyet noi dung hoc');

UPDATE users u
  JOIN roles old_role ON old_role.id = u.role_id AND old_role.name = 'ADMIN'
  JOIN roles new_role ON new_role.name = 'SUPER_ADMIN'
   SET u.role_id = new_role.id;
UPDATE users u
  JOIN roles old_role ON old_role.id = u.role_id AND old_role.name = 'CONTENT_ADMIN'
  JOIN roles new_role ON new_role.name = 'CONTENT_CREATOR'
   SET u.role_id = new_role.id;
DELETE FROM roles WHERE name IN ('ADMIN', 'CONTENT_ADMIN')
   AND NOT EXISTS (SELECT 1 FROM users u WHERE u.role_id = roles.id);

-- ===================================================================================== 3. Thanh toan

-- ------------------------------------------------------------------------------------- credit_packages
CREATE TABLE IF NOT EXISTS credit_packages (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    credit_amount INT UNSIGNED NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), UNIQUE KEY uk_credit_package_name (name),
    CONSTRAINT chk_credit_package_amount CHECK (credit_amount > 0),
    CONSTRAINT chk_credit_package_price CHECK (price > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 031 tao unique key ten `name`; doi ve ten trong jlas_schema.sql.
SET @j032_old = (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'credit_packages' AND INDEX_NAME = 'name');
SET @j032_new = (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'credit_packages' AND INDEX_NAME = 'uk_credit_package_name');
SET @j032_sql = IF(@j032_old > 0 AND @j032_new = 0,
                   'ALTER TABLE credit_packages RENAME INDEX `name` TO uk_credit_package_name', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

-- --------------------------------------------------------------------------------------------- coupons
CREATE TABLE IF NOT EXISTS coupons (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(40) NOT NULL,
    description VARCHAR(500) NULL,
    discount_type VARCHAR(10) NOT NULL,
    discount_value DECIMAL(12,2) NOT NULL,
    max_discount DECIMAL(12,2) NULL,
    min_order_amount DECIMAL(12,2) NULL,
    applies_to VARCHAR(10) NOT NULL,
    starts_at DATETIME(6) NULL,
    ends_at DATETIME(6) NULL,
    usage_limit INT NULL,
    per_user_limit INT NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), UNIQUE KEY uk_coupon_code (code),
    CONSTRAINT chk_coupon_code CHECK (REGEXP_LIKE(code, '^[A-Z0-9_-]{3,40}$', 'c')),
    CONSTRAINT chk_coupon_type CHECK (discount_type IN ('PERCENT','FIXED')),
    CONSTRAINT chk_coupon_value CHECK (discount_value > 0 AND (discount_type <> 'PERCENT' OR discount_value BETWEEN 1 AND 100)),
    CONSTRAINT chk_coupon_max CHECK (max_discount IS NULL OR max_discount > 0),
    CONSTRAINT chk_coupon_min CHECK (min_order_amount IS NULL OR min_order_amount >= 0),
    CONSTRAINT chk_coupon_scope CHECK (applies_to IN ('ALL','PREMIUM','CREDIT')),
    CONSTRAINT chk_coupon_dates CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT chk_coupon_limits CHECK ((usage_limit IS NULL OR usage_limit > 0) AND (per_user_limit IS NULL OR per_user_limit > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @j032_old = (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupons' AND INDEX_NAME = 'code');
SET @j032_new = (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupons' AND INDEX_NAME = 'uk_coupon_code');
SET @j032_sql = IF(@j032_old > 0 AND @j032_new = 0, 'ALTER TABLE coupons RENAME INDEX `code` TO uk_coupon_code', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

-- -------------------------------------------------------------------------------------------- payments
-- DB chua tung co bang payments: tao luon ban cuoi.
CREATE TABLE IF NOT EXISTS payments (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id                 BIGINT UNSIGNED NOT NULL,
    premium_plan_id         BIGINT UNSIGNED NULL,
    product_type            VARCHAR(20) NOT NULL DEFAULT 'PREMIUM',
    credit_package_id       BIGINT UNSIGNED NULL,
    credit_amount_snapshot  INT NULL,
    coupon_id               BIGINT UNSIGNED NULL,
    coupon_code_snapshot    VARCHAR(40) NULL,
    original_amount         DECIMAL(12,2) NULL,
    discount_amount         DECIMAL(12,2) NOT NULL DEFAULT 0,
    provider                VARCHAR(30) NOT NULL,
    order_code              VARCHAR(100) NOT NULL,
    provider_transaction_id VARCHAR(150) NULL,
    payment_link_id         VARCHAR(100) NULL,
    checkout_url            VARCHAR(500) NULL,
    qr_code                 VARCHAR(1000) NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    amount_paid             DECIMAL(12,2) NOT NULL,
    currency                CHAR(3) NOT NULL DEFAULT 'VND',
    response_code           VARCHAR(50) NULL,
    plan_name_snapshot      VARCHAR(100) NOT NULL,
    duration_days_snapshot  INT UNSIGNED NULL,
    paid_at                 DATETIME(6) NULL,
    expires_at              DATETIME(6) NULL,
    checked_at              DATETIME(6) NULL,
    premium_until           DATETIME(6) NULL,
    created_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_order (provider, order_code),
    UNIQUE KEY uk_payment_provider_transaction (provider, provider_transaction_id),
    KEY idx_payment_user_created (user_id, created_at),
    KEY idx_payment_status_created (status, created_at),
    KEY idx_payment_status_checked (status, checked_at),
    CONSTRAINT fk_payment_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_payment_plan FOREIGN KEY (premium_plan_id) REFERENCES premium_plans(id),
    CONSTRAINT fk_payment_credit_package FOREIGN KEY (credit_package_id) REFERENCES credit_packages(id),
    CONSTRAINT fk_payment_coupon FOREIGN KEY (coupon_id) REFERENCES coupons(id),
    CONSTRAINT chk_payment_status CHECK (status IN ('PENDING','PAID','FAILED','CANCELLED','EXPIRED','REFUNDED')),
    CONSTRAINT chk_payment_amount CHECK (amount_paid >= 0),
    CONSTRAINT chk_payment_duration CHECK (duration_days_snapshot IS NULL OR duration_days_snapshot > 0),
    CONSTRAINT chk_payment_product CHECK (product_type IN ('PREMIUM','CREDIT')),
    CONSTRAINT chk_payment_product_fields CHECK ((product_type = 'PREMIUM' AND premium_plan_id IS NOT NULL AND duration_days_snapshot > 0 AND credit_package_id IS NULL)
        OR (product_type = 'CREDIT' AND credit_package_id IS NOT NULL AND credit_amount_snapshot > 0 AND premium_plan_id IS NULL)),
    CONSTRAINT chk_payment_discount CHECK (discount_amount >= 0 AND (original_amount IS NULL OR original_amount >= amount_paid))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Bang payments cu (chi Premium, chua co PayOS): bo sung tung cot con thieu, dung vi tri nhu jlas_schema.sql.

-- Don CP khong co goi Premium -> 2 cot nay phai cho phep NULL (giu nguyen du lieu).
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'premium_plan_id' AND IS_NULLABLE = 'NO');
SET @j032_sql = IF(@j032_c > 0, 'ALTER TABLE payments MODIFY COLUMN premium_plan_id BIGINT UNSIGNED NULL', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

-- Check cu "duration_days_snapshot > 0" chan don CP: thay bang ban cho phep NULL.
SET @j032_c = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
               AND CONSTRAINT_NAME = 'chk_payment_duration' AND CHECK_CLAUSE NOT LIKE '%IS NULL%');
SET @j032_sql = IF(@j032_c > 0, 'ALTER TABLE payments DROP CHECK chk_payment_duration', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'duration_days_snapshot' AND IS_NULLABLE = 'NO');
SET @j032_sql = IF(@j032_c > 0, 'ALTER TABLE payments MODIFY COLUMN duration_days_snapshot INT UNSIGNED NULL', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'product_type');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD COLUMN product_type VARCHAR(20) NOT NULL DEFAULT ''PREMIUM'' AFTER premium_plan_id', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'credit_package_id');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD COLUMN credit_package_id BIGINT UNSIGNED NULL AFTER product_type', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'credit_amount_snapshot');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD COLUMN credit_amount_snapshot INT NULL AFTER credit_package_id', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'coupon_id');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD COLUMN coupon_id BIGINT UNSIGNED NULL AFTER credit_amount_snapshot', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'coupon_code_snapshot');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD COLUMN coupon_code_snapshot VARCHAR(40) NULL AFTER coupon_id', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'original_amount');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD COLUMN original_amount DECIMAL(12,2) NULL AFTER coupon_code_snapshot', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'discount_amount');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD COLUMN discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0 AFTER original_amount', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'payment_link_id');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD COLUMN payment_link_id VARCHAR(100) NULL AFTER provider_transaction_id', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'checkout_url');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD COLUMN checkout_url VARCHAR(500) NULL AFTER payment_link_id', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'qr_code');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD COLUMN qr_code VARCHAR(1000) NULL AFTER checkout_url', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'expires_at');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD COLUMN expires_at DATETIME(6) NULL AFTER paid_at', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'checked_at');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD COLUMN checked_at DATETIME(6) NULL AFTER expires_at', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;
SET @j032_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'premium_until');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD COLUMN premium_until DATETIME(6) NULL AFTER checked_at', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

-- Trang thai EXPIRED (link PayOS het han).
SET @j032_c = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
               AND CONSTRAINT_NAME = 'chk_payment_status' AND CHECK_CLAUSE NOT LIKE '%EXPIRED%');
SET @j032_sql = IF(@j032_c > 0, 'ALTER TABLE payments DROP CHECK chk_payment_status', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

-- Index + khoa ngoai + check con thieu (them sau khi du cot).
SET @j032_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payment_status_checked');
SET @j032_sql = IF(@j032_c = 0, 'CREATE INDEX idx_payment_status_checked ON payments (status, checked_at)', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

SET @j032_c = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
               AND TABLE_NAME = 'payments' AND CONSTRAINT_NAME = 'fk_payment_credit_package');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD CONSTRAINT fk_payment_credit_package FOREIGN KEY (credit_package_id) REFERENCES credit_packages(id)', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

SET @j032_c = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
               AND TABLE_NAME = 'payments' AND CONSTRAINT_NAME = 'fk_payment_coupon');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD CONSTRAINT fk_payment_coupon FOREIGN KEY (coupon_id) REFERENCES coupons(id)', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

SET @j032_c = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
               AND CONSTRAINT_NAME = 'chk_payment_status');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD CONSTRAINT chk_payment_status CHECK (status IN (''PENDING'',''PAID'',''FAILED'',''CANCELLED'',''EXPIRED'',''REFUNDED''))', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

SET @j032_c = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
               AND CONSTRAINT_NAME = 'chk_payment_duration');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD CONSTRAINT chk_payment_duration CHECK (duration_days_snapshot IS NULL OR duration_days_snapshot > 0)', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

SET @j032_c = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
               AND CONSTRAINT_NAME = 'chk_payment_product');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD CONSTRAINT chk_payment_product CHECK (product_type IN (''PREMIUM'',''CREDIT''))', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

SET @j032_c = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
               AND CONSTRAINT_NAME = 'chk_payment_product_fields');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD CONSTRAINT chk_payment_product_fields CHECK ((product_type = ''PREMIUM'' AND premium_plan_id IS NOT NULL AND duration_days_snapshot > 0 AND credit_package_id IS NULL) OR (product_type = ''CREDIT'' AND credit_package_id IS NOT NULL AND credit_amount_snapshot > 0 AND premium_plan_id IS NULL))', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

SET @j032_c = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
               AND CONSTRAINT_NAME = 'chk_payment_discount');
SET @j032_sql = IF(@j032_c = 0, 'ALTER TABLE payments ADD CONSTRAINT chk_payment_discount CHECK (discount_amount >= 0 AND (original_amount IS NULL OR original_amount >= amount_paid))', 'DO 0');
PREPARE j032_stmt FROM @j032_sql; EXECUTE j032_stmt; DEALLOCATE PREPARE j032_stmt;

-- ===================================================================================== 4. Du lieu
-- 3 goi CP (giong sql/seeds/jlas_seed_all.sql); goi da co (theo ten) giu nguyen, ke ca gia admin da sua.
INSERT IGNORE INTO credit_packages (name, description, credit_amount, price, is_active) VALUES
    ('CP 100', '100 CP', 100, 10000.00, 1),
    ('CP 500', '500 CP', 500, 45000.00, 1),
    ('CP 1200', '1200 CP', 1200, 100000.00, 1);

-- Gia Premium tam cua seed cu (9.99 / 99.99) -> VND. Gia admin da sua khong bi ghi de.
UPDATE premium_plans SET price = 10000.00  WHERE name = 'MONTHLY' AND price = 9.99;
UPDATE premium_plans SET price = 100000.00 WHERE name = 'YEARLY'  AND price = 99.99;

-- Ma giam gia mau de test (tuy chon): bo comment de them. Khong bat tren production.
-- INSERT IGNORE INTO coupons (code, description, discount_type, discount_value, max_discount, min_order_amount,
--                             applies_to, starts_at, ends_at, usage_limit, per_user_limit, is_active) VALUES
--     ('DEMO10',    'Giam 10% moi don (toi da 20.000d)', 'PERCENT', 10,    20000, NULL,  'ALL',     NULL, NULL, NULL, 1, 1),
--     ('PREMIUM5K', 'Giam 5.000d goi Premium',           'FIXED',   5000,  NULL,  10000, 'PREMIUM', NULL, NULL, 100,  1, 1),
--     ('CP20',      'Giam 20% khi mua CP',               'PERCENT', 20,    NULL,  NULL,  'CREDIT',  NULL, NULL, NULL, 2, 1);

-- Kiem tra nhanh sau khi chay.
SELECT 'credit_packages' AS bang, COUNT(*) AS so_dong FROM credit_packages
UNION ALL SELECT 'coupons', COUNT(*) FROM coupons
UNION ALL SELECT 'payments', COUNT(*) FROM payments
UNION ALL SELECT 'admin_audit_log', COUNT(*) FROM admin_audit_log;
SELECT r.name AS vai_tro, COUNT(u.id) AS so_tai_khoan FROM roles r LEFT JOIN users u ON u.role_id = r.id GROUP BY r.name ORDER BY r.name;
