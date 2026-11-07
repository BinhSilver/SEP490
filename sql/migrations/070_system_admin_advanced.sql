-- [JLAS2-51] Quan tri he thong nang cao: audit log co vai tro, IP va chuoi bam.
--   admin_audit_log: them actor_role, ip_address, prev_hash, row_hash (NULL cho dong cu = legacy).
--   admin_audit_chain: dau chuoi bam, mot dong id = 1.
-- Entity da map cac cot nay (ddl-auto: validate), nen phai chay truoc khi khoi dong ban code moi. Chay lai an toan.

SET NAMES utf8mb4;

SET @audit_role_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'admin_audit_log' AND column_name = 'actor_role');
SET @audit_role_ddl = IF(@audit_role_exists = 0,
    'ALTER TABLE admin_audit_log ADD COLUMN actor_role VARCHAR(50) NULL AFTER created_at',
    'SELECT 1');
PREPARE audit_role_stmt FROM @audit_role_ddl;
EXECUTE audit_role_stmt;
DEALLOCATE PREPARE audit_role_stmt;

SET @audit_ip_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'admin_audit_log' AND column_name = 'ip_address');
SET @audit_ip_ddl = IF(@audit_ip_exists = 0,
    'ALTER TABLE admin_audit_log ADD COLUMN ip_address VARCHAR(64) NULL AFTER actor_role',
    'SELECT 1');
PREPARE audit_ip_stmt FROM @audit_ip_ddl;
EXECUTE audit_ip_stmt;
DEALLOCATE PREPARE audit_ip_stmt;

SET @audit_prev_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'admin_audit_log' AND column_name = 'prev_hash');
SET @audit_prev_ddl = IF(@audit_prev_exists = 0,
    'ALTER TABLE admin_audit_log ADD COLUMN prev_hash CHAR(64) NULL AFTER ip_address',
    'SELECT 1');
PREPARE audit_prev_stmt FROM @audit_prev_ddl;
EXECUTE audit_prev_stmt;
DEALLOCATE PREPARE audit_prev_stmt;

SET @audit_hash_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'admin_audit_log' AND column_name = 'row_hash');
SET @audit_hash_ddl = IF(@audit_hash_exists = 0,
    'ALTER TABLE admin_audit_log ADD COLUMN row_hash CHAR(64) NULL AFTER prev_hash',
    'SELECT 1');
PREPARE audit_hash_stmt FROM @audit_hash_ddl;
EXECUTE audit_hash_stmt;
DEALLOCATE PREPARE audit_hash_stmt;

-- Khong co FK toi admin_audit_log: xoa dong cuoi phai van phat hien duoc qua last_log_id.
CREATE TABLE IF NOT EXISTS admin_audit_chain (
    id TINYINT NOT NULL,
    last_log_id BIGINT UNSIGNED NULL,
    last_hash CHAR(64) NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_admin_audit_chain_single CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO admin_audit_chain (id) VALUES (1);
