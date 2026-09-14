-- Profile/security additions: two-channel OTP confirmation for email changes.
CREATE TABLE IF NOT EXISTS email_change_requests (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id        BIGINT UNSIGNED NOT NULL,
    new_email      VARCHAR(255) NOT NULL,
    old_otp_hash   CHAR(64) NOT NULL,
    new_otp_hash   CHAR(64) NOT NULL,
    attempt_count  INT UNSIGNED NOT NULL DEFAULT 0,
    expires_at     DATETIME(6) NOT NULL,
    confirmed_at   DATETIME(6) NULL,
    revoked_at     DATETIME(6) NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_email_change_user_active (user_id, confirmed_at, revoked_at, created_at),
    KEY idx_email_change_expiry (expires_at),
    CONSTRAINT fk_email_change_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
