-- =====================================================================
-- 056: tra loi binh luan + tha cam xuc cho binh luan (content_comment).
--
-- content_comment.parent_id: binh luan goc ma binh luan nay tra loi (mot cap, nhu binh luan duoi bai viet);
--   NULL = binh luan goc. Xoa binh luan goc thi xoa luon cac tra loi.
-- content_comment_reaction: moi nguoi mot cam xuc cho moi binh luan (LIKE/LOVE/HAHA/WOW/SAD/CLAP);
--   chon lai cam xuc dang co thi bo, chon cam xuc khac thi doi.
--
-- Chay lai an toan. ShadowingCommentThreadSchemaInitializer ap dung cung DDL khi ung dung khoi dong.
-- =====================================================================
SET @has_parent = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'content_comment' AND COLUMN_NAME = 'parent_id');
SET @ddl = IF(@has_parent = 0,
    'ALTER TABLE content_comment ADD COLUMN parent_id BIGINT UNSIGNED NULL AFTER target_id, '
    'ADD KEY idx_content_comment_parent (parent_id, created_at), '
    'ADD CONSTRAINT fk_content_comment_parent FOREIGN KEY (parent_id) REFERENCES content_comment(id) ON DELETE CASCADE',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS content_comment_reaction (
    comment_id BIGINT UNSIGNED NOT NULL,
    user_id    BIGINT UNSIGNED NOT NULL,
    reaction   VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (comment_id, user_id),
    KEY idx_comment_reaction_user (user_id),
    CONSTRAINT fk_comment_reaction_comment FOREIGN KEY (comment_id) REFERENCES content_comment(id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_reaction_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_comment_reaction CHECK (reaction IN ('LIKE','LOVE','HAHA','WOW','SAD','CLAP'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
