-- [JLAS2-37] Community Hub: trang thai cong dong, danh gia, binh luan, bao cao.
-- Bang goc (flashcard_set, video...) van la nguon su that cho title/owner/visibility/draft/xoa/moderation;
-- community_content chi luu trang thai cong dong va bo dem, tao dong khi co rating/comment/report dau tien.
-- Khong co dong = PUBLISHED, cac bo dem = 0. Chay lai an toan.

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS community_content (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    content_type  VARCHAR(30) NOT NULL,               -- FLASHCARD_SET, SHADOWING_VIDEO
    content_id    BIGINT UNSIGNED NOT NULL,           -- id trong bang goc cua content_type
    status        VARCHAR(24) NOT NULL DEFAULT 'PUBLISHED',
    rating_avg    DECIMAL(3,2) NOT NULL DEFAULT 0.00,
    rating_count  INT UNSIGNED NOT NULL DEFAULT 0,
    comment_count INT UNSIGNED NOT NULL DEFAULT 0,    -- binh luan PUBLISHED, chua xoa
    report_count  INT UNSIGNED NOT NULL DEFAULT 0,    -- tong so bao cao da nhan
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_content_source (content_type, content_id),
    KEY idx_community_content_status (status, content_type),
    CONSTRAINT chk_community_content_status CHECK (status IN ('PUBLISHED','TEMPORARILY_HIDDEN','REMOVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS community_rating (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    community_content_id BIGINT UNSIGNED NOT NULL,
    user_id              BIGINT UNSIGNED NOT NULL,
    score                TINYINT UNSIGNED NOT NULL,
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_rating_user (community_content_id, user_id),
    KEY idx_community_rating_user (user_id),
    CONSTRAINT fk_community_rating_content FOREIGN KEY (community_content_id)
        REFERENCES community_content(id) ON DELETE CASCADE,
    CONSTRAINT fk_community_rating_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_community_rating_score CHECK (score BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS community_comment (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    community_content_id BIGINT UNSIGNED NOT NULL,
    user_id              BIGINT UNSIGNED NOT NULL,
    body                 VARCHAR(1000) NOT NULL,
    status               VARCHAR(24) NOT NULL DEFAULT 'PUBLISHED',
    report_count         INT UNSIGNED NOT NULL DEFAULT 0,
    deleted_at           DATETIME(6) NULL,             -- xoa mem
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_community_comment_content (community_content_id, deleted_at, status, created_at),
    KEY idx_community_comment_status (status, deleted_at),
    KEY idx_community_comment_user (user_id),
    CONSTRAINT fk_community_comment_content FOREIGN KEY (community_content_id)
        REFERENCES community_content(id) ON DELETE CASCADE,
    CONSTRAINT fk_community_comment_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_community_comment_status CHECK (status IN ('PUBLISHED','TEMPORARILY_HIDDEN','REMOVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS community_report (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    reporter_id     BIGINT UNSIGNED NOT NULL,
    target_type     VARCHAR(16) NOT NULL,              -- CONTENT -> community_content.id, COMMENT -> community_comment.id
    target_id       BIGINT UNSIGNED NOT NULL,
    reason          VARCHAR(24) NOT NULL,
    note            VARCHAR(500) NULL,
    status          VARCHAR(12) NOT NULL DEFAULT 'OPEN',
    resolved_by     BIGINT UNSIGNED NULL,
    resolved_at     DATETIME(6) NULL,
    resolution_note VARCHAR(500) NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_report_once (reporter_id, target_type, target_id),
    KEY idx_community_report_target (target_type, target_id, status),
    CONSTRAINT fk_community_report_reporter FOREIGN KEY (reporter_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_community_report_resolver FOREIGN KEY (resolved_by)
        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_community_report_target CHECK (target_type IN ('CONTENT','COMMENT')),
    CONSTRAINT chk_community_report_reason
        CHECK (reason IN ('SPAM','INAPPROPRIATE','COPYRIGHT','WRONG_CONTENT','OTHER')),
    CONSTRAINT chk_community_report_status CHECK (status IN ('OPEN','RESOLVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
