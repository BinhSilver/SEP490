-- [JLAS-W0193] Task 6 — Grammar ordering/fill practice-gate progress, plus a fix to the micro-check quiz
-- type CHECK constraint that Task 5 should have widened but did not (KANJI_MICRO_CHECK was never added;
-- caught while adding GRAMMAR_MICRO_CHECK for this task, since both share the same constraint).

CREATE TABLE learning_grammar_practice_progress (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_item_id  BIGINT UNSIGNED NOT NULL,
    grammar_point_id  BIGINT UNSIGNED NOT NULL,
    source_revision   VARCHAR(64) NOT NULL,
    ordering_done     TINYINT(1) NOT NULL DEFAULT 0,
    fill_done         TINYINT(1) NOT NULL DEFAULT 0,
    ordering_done_at  DATETIME(6) NULL,
    fill_done_at      DATETIME(6) NULL,
    row_version       BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                      ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_grammar_practice_identity (activity_item_id, grammar_point_id),
    CONSTRAINT fk_grammar_practice_activity FOREIGN KEY (activity_item_id)
        REFERENCES user_learning_path_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE learning_quiz_session DROP CHECK chk_quiz_session_type;
ALTER TABLE learning_quiz_session
    ADD CONSTRAINT chk_quiz_session_type
        CHECK (quiz_type IN ('GATE','LESSON','GRAMMAR','FLASHCARD','VOCAB_MICRO_CHECK','KANJI_MICRO_CHECK','GRAMMAR_MICRO_CHECK'));
