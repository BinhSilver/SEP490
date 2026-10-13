-- [JLAS2-51] Coupons and CP packages. Safe to rerun on MySQL 8.0.16+.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS credit_packages (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE,
 description VARCHAR(500) NULL, credit_amount INT UNSIGNED NOT NULL, price DECIMAL(12,2) NOT NULL,
 is_active TINYINT(1) NOT NULL DEFAULT 1, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 CONSTRAINT chk_credit_package_amount CHECK (credit_amount > 0),
 CONSTRAINT chk_credit_package_price CHECK (price > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS coupons (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, code VARCHAR(40) NOT NULL UNIQUE,
 description VARCHAR(500) NULL, discount_type VARCHAR(10) NOT NULL, discount_value DECIMAL(12,2) NOT NULL,
 max_discount DECIMAL(12,2) NULL, min_order_amount DECIMAL(12,2) NULL, applies_to VARCHAR(10) NOT NULL,
 starts_at DATETIME(6) NULL, ends_at DATETIME(6) NULL, usage_limit INT NULL, per_user_limit INT NULL,
 is_active TINYINT(1) NOT NULL DEFAULT 1, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 CONSTRAINT chk_coupon_code CHECK (REGEXP_LIKE(code, '^[A-Z0-9_-]{3,40}$', 'c')),
 CONSTRAINT chk_coupon_type CHECK (discount_type IN ('PERCENT','FIXED')),
 CONSTRAINT chk_coupon_value CHECK (discount_value > 0 AND (discount_type <> 'PERCENT' OR discount_value BETWEEN 1 AND 100)),
 CONSTRAINT chk_coupon_max CHECK (max_discount IS NULL OR max_discount > 0),
 CONSTRAINT chk_coupon_min CHECK (min_order_amount IS NULL OR min_order_amount >= 0),
 CONSTRAINT chk_coupon_scope CHECK (applies_to IN ('ALL','PREMIUM','CREDIT')),
 CONSTRAINT chk_coupon_dates CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at),
 CONSTRAINT chk_coupon_limits CHECK ((usage_limit IS NULL OR usage_limit > 0) AND (per_user_limit IS NULL OR per_user_limit > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @j031_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='payments' AND COLUMN_NAME='product_type');
SET @j031_sql = IF(@j031_exists=0, 'ALTER TABLE payments ADD COLUMN product_type VARCHAR(20) NOT NULL DEFAULT ''PREMIUM'' AFTER premium_plan_id', 'DO 0');
PREPARE j031_stmt FROM @j031_sql; EXECUTE j031_stmt; DEALLOCATE PREPARE j031_stmt;
SET @j031_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='payments' AND COLUMN_NAME='credit_package_id');
SET @j031_sql = IF(@j031_exists=0, 'ALTER TABLE payments ADD COLUMN credit_package_id BIGINT UNSIGNED NULL AFTER product_type, ADD COLUMN credit_amount_snapshot INT NULL AFTER credit_package_id, ADD COLUMN coupon_id BIGINT UNSIGNED NULL AFTER credit_amount_snapshot, ADD COLUMN coupon_code_snapshot VARCHAR(40) NULL AFTER coupon_id, ADD COLUMN original_amount DECIMAL(12,2) NULL AFTER coupon_code_snapshot, ADD COLUMN discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0 AFTER original_amount', 'DO 0');
PREPARE j031_stmt FROM @j031_sql; EXECUTE j031_stmt; DEALLOCATE PREPARE j031_stmt;
-- An old Premium-only check must be relaxed before CREDIT orders can omit duration.
SET @j031_exists = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_payment_duration' AND CHECK_CLAUSE NOT LIKE '%IS NULL%');
SET @j031_sql = IF(@j031_exists>0, 'ALTER TABLE payments DROP CHECK chk_payment_duration', 'DO 0');
PREPARE j031_stmt FROM @j031_sql; EXECUTE j031_stmt; DEALLOCATE PREPARE j031_stmt;
ALTER TABLE payments MODIFY COLUMN premium_plan_id BIGINT UNSIGNED NULL, MODIFY COLUMN duration_days_snapshot INT UNSIGNED NULL;
SET @j031_exists = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_payment_duration');
SET @j031_sql = IF(@j031_exists=0, 'ALTER TABLE payments ADD CONSTRAINT chk_payment_duration CHECK (duration_days_snapshot IS NULL OR duration_days_snapshot > 0)', 'DO 0');
PREPARE j031_stmt FROM @j031_sql; EXECUTE j031_stmt; DEALLOCATE PREPARE j031_stmt;
SET @j031_exists = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='payments' AND CONSTRAINT_NAME='fk_payment_credit_package');
SET @j031_sql = IF(@j031_exists=0, 'ALTER TABLE payments ADD CONSTRAINT fk_payment_credit_package FOREIGN KEY (credit_package_id) REFERENCES credit_packages(id)', 'DO 0');
PREPARE j031_stmt FROM @j031_sql; EXECUTE j031_stmt; DEALLOCATE PREPARE j031_stmt;
SET @j031_exists = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='payments' AND CONSTRAINT_NAME='fk_payment_coupon');
SET @j031_sql = IF(@j031_exists=0, 'ALTER TABLE payments ADD CONSTRAINT fk_payment_coupon FOREIGN KEY (coupon_id) REFERENCES coupons(id)', 'DO 0');
PREPARE j031_stmt FROM @j031_sql; EXECUTE j031_stmt; DEALLOCATE PREPARE j031_stmt;
SET @j031_exists = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_payment_product');
SET @j031_sql = IF(@j031_exists=0, 'ALTER TABLE payments ADD CONSTRAINT chk_payment_product CHECK (product_type IN (''PREMIUM'',''CREDIT''))', 'DO 0');
PREPARE j031_stmt FROM @j031_sql; EXECUTE j031_stmt; DEALLOCATE PREPARE j031_stmt;
SET @j031_exists = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_payment_product_fields');
SET @j031_sql = IF(@j031_exists=0, 'ALTER TABLE payments ADD CONSTRAINT chk_payment_product_fields CHECK ((product_type=''PREMIUM'' AND premium_plan_id IS NOT NULL AND duration_days_snapshot>0 AND credit_package_id IS NULL) OR (product_type=''CREDIT'' AND credit_package_id IS NOT NULL AND credit_amount_snapshot>0 AND premium_plan_id IS NULL))', 'DO 0');
PREPARE j031_stmt FROM @j031_sql; EXECUTE j031_stmt; DEALLOCATE PREPARE j031_stmt;
SET @j031_exists = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_payment_discount');
SET @j031_sql = IF(@j031_exists=0, 'ALTER TABLE payments ADD CONSTRAINT chk_payment_discount CHECK (discount_amount>=0 AND (original_amount IS NULL OR original_amount>=amount_paid))', 'DO 0');
PREPARE j031_stmt FROM @j031_sql; EXECUTE j031_stmt; DEALLOCATE PREPARE j031_stmt;
