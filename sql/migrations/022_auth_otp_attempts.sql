-- [JLAS-SECURITY] Add persistent OTP attempt accounting without resetting existing data.
-- Run on the selected J-LAS database before starting the updated application. Re-runnable.
SET @jlas_otp_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'auth_tokens' AND column_name = 'attempt_count') = 0,
    'ALTER TABLE auth_tokens ADD COLUMN attempt_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER otp_hash',
    'SELECT 1');
PREPARE jlas_otp_stmt FROM @jlas_otp_ddl;
EXECUTE jlas_otp_stmt;
DEALLOCATE PREPARE jlas_otp_stmt;
