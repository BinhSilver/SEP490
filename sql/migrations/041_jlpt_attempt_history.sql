-- [JLAS-XXXX] Store submitted JLPT results per account for the history page.
CREATE TABLE IF NOT EXISTS jlpt_attempt_history (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id             BIGINT UNSIGNED NOT NULL,
    submission_id       CHAR(36) NOT NULL,
    exam_code           VARCHAR(30) NOT NULL,
    exam_title          VARCHAR(255) NOT NULL,
    jlpt_level          VARCHAR(10) NOT NULL,
    mode                VARCHAR(16) NOT NULL,
    part_code           VARCHAR(30) NULL,
    correct_answers     SMALLINT UNSIGNED NOT NULL,
    answered_questions  SMALLINT UNSIGNED NOT NULL,
    total_questions     SMALLINT UNSIGNED NOT NULL,
    score_percent       DECIMAL(5,2) NOT NULL,
    passed              TINYINT(1) NOT NULL,
    submitted_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_jlpt_attempt_history_submission (user_id, submission_id),
    KEY idx_jlpt_attempt_history_user_date (user_id, submitted_at, id),
    CONSTRAINT fk_jlpt_attempt_history_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_jlpt_attempt_history_mode CHECK (mode IN ('EXAM', 'PRACTICE')),
    CONSTRAINT chk_jlpt_attempt_history_level CHECK (jlpt_level IN ('N1','N2','N3','N4','N5')),
    CONSTRAINT chk_jlpt_attempt_history_counts CHECK (
        total_questions > 0 AND answered_questions <= total_questions
        AND correct_answers <= answered_questions
    ),
    CONSTRAINT chk_jlpt_attempt_history_score CHECK (score_percent BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
