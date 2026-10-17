-- [JLAS-W0260] Kana Connect: one shared board per local day and level (N5..N1, MIX),
-- with per-learner progress and a daily leaderboard per board.
-- Run after 028_daily_kana_challenge.sql. Safe to re-run; keeps existing rows.
-- Rows created before this migration have puzzle_id = NULL: they stay as history and are
-- never shown again or ranked.

CREATE TABLE IF NOT EXISTS daily_kana_puzzle (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    challenge_date DATE NOT NULL,
    board_level    VARCHAR(10) NOT NULL,          -- N5..N1 or MIX (validated by KanaChallengeService)
    puzzle_json    TEXT NOT NULL,                 -- versioned multi-word board (cells, words, solutions)
    created_at     TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_daily_kana_puzzle_day_level (challenge_date, board_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 1) daily_kana_challenge.puzzle_id -> the shared board this progress row belongs to.
SET @kana_has_puzzle_id = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'daily_kana_challenge' AND COLUMN_NAME = 'puzzle_id'
);
SET @kana_puzzle_id_sql = IF(@kana_has_puzzle_id = 0,
    'ALTER TABLE daily_kana_challenge ADD COLUMN puzzle_id BIGINT UNSIGNED NULL AFTER challenge_date',
    'SELECT 1');
PREPARE kana_puzzle_id_stmt FROM @kana_puzzle_id_sql;
EXECUTE kana_puzzle_id_stmt;
DEALLOCATE PREPARE kana_puzzle_id_stmt;

-- 2) One progress row per learner and board. Add it before dropping the old key, because
--    fk_daily_kana_user needs an index that starts with user_id.
SET @kana_has_user_puzzle = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'daily_kana_challenge'
      AND INDEX_NAME = 'uk_daily_kana_user_puzzle'
);
SET @kana_user_puzzle_sql = IF(@kana_has_user_puzzle = 0,
    'ALTER TABLE daily_kana_challenge ADD UNIQUE KEY uk_daily_kana_user_puzzle (user_id, puzzle_id)',
    'SELECT 1');
PREPARE kana_user_puzzle_stmt FROM @kana_user_puzzle_sql;
EXECUTE kana_user_puzzle_stmt;
DEALLOCATE PREPARE kana_user_puzzle_stmt;

-- 3) A learner may now play several boards (levels) on the same day.
SET @kana_has_user_date = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'daily_kana_challenge'
      AND INDEX_NAME = 'uk_daily_kana_user_date'
);
SET @kana_user_date_sql = IF(@kana_has_user_date > 0,
    'ALTER TABLE daily_kana_challenge DROP INDEX uk_daily_kana_user_date',
    'SELECT 1');
PREPARE kana_user_date_stmt FROM @kana_user_date_sql;
EXECUTE kana_user_date_stmt;
DEALLOCATE PREPARE kana_user_date_stmt;

-- 4) Leaderboard lookup: finishers of one board, fastest first.
SET @kana_has_board_idx = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'daily_kana_challenge'
      AND INDEX_NAME = 'idx_daily_kana_board_rank'
);
SET @kana_board_idx_sql = IF(@kana_has_board_idx = 0,
    'ALTER TABLE daily_kana_challenge ADD KEY idx_daily_kana_board_rank (puzzle_id, elapsed_seconds, completed_at)',
    'SELECT 1');
PREPARE kana_board_idx_stmt FROM @kana_board_idx_sql;
EXECUTE kana_board_idx_stmt;
DEALLOCATE PREPARE kana_board_idx_stmt;

-- 5) Progress rows follow their board.
SET @kana_has_puzzle_fk = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'daily_kana_challenge'
      AND CONSTRAINT_NAME = 'fk_daily_kana_puzzle'
);
SET @kana_puzzle_fk_sql = IF(@kana_has_puzzle_fk = 0,
    'ALTER TABLE daily_kana_challenge ADD CONSTRAINT fk_daily_kana_puzzle FOREIGN KEY (puzzle_id) REFERENCES daily_kana_puzzle(id) ON DELETE CASCADE',
    'SELECT 1');
PREPARE kana_puzzle_fk_stmt FROM @kana_puzzle_fk_sql;
EXECUTE kana_puzzle_fk_stmt;
DEALLOCATE PREPARE kana_puzzle_fk_stmt;
