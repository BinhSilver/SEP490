-- [JLAS2-49] Mua Premium qua PayOS (khong webhook): them cot cho link thanh toan va trang thai EXPIRED.
--   payments.payment_link_id  VARCHAR(100)  ma link PayOS
--   payments.checkout_url     VARCHAR(500)  trang thanh toan PayOS
--   payments.qr_code          VARCHAR(1000) chuoi VietQR de hien ma QR tai trang cua minh
--   payments.expires_at       DATETIME(6)   link het han luc nay
--   payments.checked_at       DATETIME(6)   lan gan nhat server hoi PayOS (gioi han tan suat polling)
--   payments.premium_until    DATETIME(6)   Premium co hieu luc den dau sau khi don nay duoc cong
--   chk_payment_status them 'EXPIRED'
-- Gia tam (team se chinh sau): MONTHLY 10000 VND, YEARLY 100000 VND. Chi doi khi gia con la so tam cua seed
-- (9.99 / 99.99), nen gia admin da sua khong bi ghi de.
-- Chay lai duoc. Chay TRUOC khi khoi dong app (ddl-auto: validate). MySQL 8.0.16+.

SET NAMES utf8mb4;

SET @j030_t = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments');

SET @j030_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'payment_link_id');
SET @j030_sql = IF(@j030_t = 1 AND @j030_c = 0,
    'ALTER TABLE payments
       ADD COLUMN payment_link_id VARCHAR(100)  NULL AFTER provider_transaction_id,
       ADD COLUMN checkout_url    VARCHAR(500)  NULL AFTER payment_link_id,
       ADD COLUMN qr_code         VARCHAR(1000) NULL AFTER checkout_url,
       ADD COLUMN expires_at      DATETIME(6)   NULL AFTER paid_at,
       ADD COLUMN checked_at      DATETIME(6)   NULL AFTER expires_at,
       ADD COLUMN premium_until   DATETIME(6)   NULL AFTER checked_at', 'DO 0');
PREPARE j030_stmt FROM @j030_sql; EXECUTE j030_stmt; DEALLOCATE PREPARE j030_stmt;

-- Status check: recreate with EXPIRED when it is missing.
SET @j030_c = (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_payment_status'
                 AND CHECK_CLAUSE LIKE '%EXPIRED%');
SET @j030_sql = IF(@j030_t = 1 AND @j030_c = 0, 'ALTER TABLE payments DROP CHECK chk_payment_status', 'DO 0');
PREPARE j030_stmt FROM @j030_sql; EXECUTE j030_stmt; DEALLOCATE PREPARE j030_stmt;
SET @j030_sql = IF(@j030_t = 1 AND @j030_c = 0,
    'ALTER TABLE payments ADD CONSTRAINT chk_payment_status
       CHECK (status IN (''PENDING'', ''PAID'', ''FAILED'', ''CANCELLED'', ''EXPIRED'', ''REFUNDED''))', 'DO 0');
PREPARE j030_stmt FROM @j030_sql; EXECUTE j030_stmt; DEALLOCATE PREPARE j030_stmt;

-- Background reconciliation looks up pending orders by expiry.
SET @j030_c = (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payment_status_checked');
SET @j030_sql = IF(@j030_t = 1 AND @j030_c = 0,
    'CREATE INDEX idx_payment_status_checked ON payments (status, checked_at)', 'DO 0');
PREPARE j030_stmt FROM @j030_sql; EXECUTE j030_stmt; DEALLOCATE PREPARE j030_stmt;

UPDATE premium_plans SET price = 10000.00  WHERE name = 'MONTHLY' AND price = 9.99;
UPDATE premium_plans SET price = 100000.00 WHERE name = 'YEARLY'  AND price = 99.99;
