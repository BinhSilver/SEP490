-- [JLAS2-51] System Admin core: khoa tai khoan + nhat ky quan tri (khop jlas_schema.sql).
--   users.locked_at      DATETIME(6)  NULL  thoi diem System Admin khoa tai khoan (NULL = dang hoat dong)
--   users.locked_reason  VARCHAR(500) NULL  ly do khoa, hien cho System Admin
--   admin_audit_log                         nhat ky append-only cho thao tac System Admin (che du lieu nhay cam)
--   roles 'SUPER_ADMIN'                     ma quyen chuan cua System Admin
-- Cach lam (giong 019/020/021): doc information_schema, cot nao chua co moi ADD; bang khong ton tai thi bo qua.
-- Chay lai duoc. Chay TRUOC khi khoi dong app (ddl-auto: validate). MySQL 8.0.16+.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------- users.locked_at
SET @j028_t = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users');
SET @j028_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'locked_at');
SET @j028_sql = IF(@j028_t = 1 AND @j028_c = 0,
                   'ALTER TABLE users ADD COLUMN locked_at DATETIME(6) NULL', 'DO 0');
PREPARE j028_stmt FROM @j028_sql; EXECUTE j028_stmt; DEALLOCATE PREPARE j028_stmt;

-- ---------------------------------------------------------------- users.locked_reason
SET @j028_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'locked_reason');
SET @j028_sql = IF(@j028_t = 1 AND @j028_c = 0,
                   'ALTER TABLE users ADD COLUMN locked_reason VARCHAR(500) NULL AFTER locked_at', 'DO 0');
PREPARE j028_stmt FROM @j028_sql; EXECUTE j028_stmt; DEALLOCATE PREPARE j028_stmt;

-- ---------------------------------------------------------------- admin_audit_log
CREATE TABLE IF NOT EXISTS admin_audit_log (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT UNSIGNED NOT NULL,
    action        VARCHAR(50) NOT NULL,
    target_type   VARCHAR(40) NOT NULL,
    target_id     VARCHAR(64) NULL,
    reason        VARCHAR(500) NULL,
    result        VARCHAR(20) NOT NULL,
    changes_json  TEXT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_admin_audit_created (created_at),
    KEY idx_admin_audit_actor_created (actor_user_id, created_at),
    KEY idx_admin_audit_target (target_type, target_id),
    KEY idx_admin_audit_action_created (action, created_at),
    CONSTRAINT fk_admin_audit_actor FOREIGN KEY (actor_user_id) REFERENCES users(id),
    CONSTRAINT chk_admin_audit_result CHECK (result IN ('SUCCESS','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------- SUPER_ADMIN role
INSERT IGNORE INTO roles (name, description) VALUES ('SUPER_ADMIN', 'System Admin');
