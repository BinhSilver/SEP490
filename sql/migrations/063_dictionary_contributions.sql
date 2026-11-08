-- MySQL does not support IF EXISTS for DROP CHECK; check the constraint before changing it.
SET @comment_check_exists = (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 'content_comment'
      AND constraint_name = 'chk_content_comment_target_type' AND constraint_type = 'CHECK');
SET @comment_drop_sql = IF(@comment_check_exists > 0,
    'ALTER TABLE content_comment DROP CHECK chk_content_comment_target_type', 'DO 0');
PREPARE comment_drop FROM @comment_drop_sql;
EXECUTE comment_drop;
DEALLOCATE PREPARE comment_drop;

ALTER TABLE content_comment ADD CONSTRAINT chk_content_comment_target_type
    CHECK (target_type IN ('FLASHCARD_SET','VIDEO','LESSON','SCENARIO','TEXTBOOK','VOCABULARY','KANJI'));

CREATE TABLE IF NOT EXISTS content_comment_vote (
    comment_id BIGINT UNSIGNED NOT NULL,
    user_id    BIGINT UNSIGNED NOT NULL,
    vote       TINYINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (comment_id, user_id),
    KEY idx_content_comment_vote_user (user_id),
    CONSTRAINT fk_content_comment_vote_comment FOREIGN KEY (comment_id)
        REFERENCES content_comment(id) ON DELETE CASCADE,
    CONSTRAINT fk_content_comment_vote_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_content_comment_vote CHECK (vote IN (-1, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
