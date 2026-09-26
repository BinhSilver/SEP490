-- JLAS-W0127 / JLAS-W0193: daily goal, credit awards, missions, streak freeze.
-- Keep jlas_schema.sql unchanged; run this once on existing databases.
-- The application also creates the new tables at startup (MissionSchemaInitializer) and seeds
-- missing missions (MissionDefinitionSeeder), so this script is for DBAs and fresh reviews.

-- 1) Learning events already applied (dedupe by deterministic event id).
CREATE TABLE IF NOT EXISTS learning_event_receipt (
    event_id     VARCHAR(120) NOT NULL,
    user_id      BIGINT UNSIGNED NOT NULL,
    event_type   VARCHAR(40) NOT NULL,
    occurred_at  DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id),
    KEY idx_event_receipt_user (user_id, processed_at),
    CONSTRAINT fk_event_receipt_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2) Learner timezone reported by the browser; defines where each learner's day starts.
CREATE TABLE IF NOT EXISTS user_time_zone (
    user_id    BIGINT UNSIGNED NOT NULL,
    zone_id    VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_time_zone_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3) Outbox of credit awards; retried until the central credit service accepts them.
CREATE TABLE IF NOT EXISTS pending_credit_award (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id           BIGINT UNSIGNED NOT NULL,
    amount            INT NOT NULL,
    reason            VARCHAR(30) NOT NULL,
    reference_type    VARCHAR(30) NOT NULL,
    reference_id      BIGINT UNSIGNED NOT NULL,
    note              VARCHAR(200) NULL,
    celebration_kind  VARCHAR(30) NULL,
    celebration_title VARCHAR(160) NULL,
    streak            INT NULL,
    attempts          INT NOT NULL DEFAULT 0,
    last_error        VARCHAR(500) NULL,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    next_attempt_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at      DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pending_award_reference (user_id, reason, reference_type, reference_id),
    KEY idx_pending_award_due (completed_at, next_attempt_at),
    CONSTRAINT fk_pending_award_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_pending_award_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4) Rewards waiting to be animated on the learner's next page load.
CREATE TABLE IF NOT EXISTS reward_celebration (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id    BIGINT UNSIGNED NOT NULL,
    event_key  VARCHAR(160) NOT NULL,
    kind       VARCHAR(30) NOT NULL,
    title      VARCHAR(160) NOT NULL,
    amount     INT NOT NULL DEFAULT 0,
    streak     INT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    seen_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reward_celebration_event (user_id, event_key),
    KEY idx_reward_celebration_unseen (user_id, seen_at, id),
    CONSTRAINT fk_reward_celebration_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5) Missed days offered for a streak freeze and the learner's decision.
CREATE TABLE IF NOT EXISTS streak_freeze_day (
    user_id        BIGINT UNSIGNED NOT NULL,
    freeze_date    DATE NOT NULL,
    status         VARCHAR(20) NOT NULL,          -- OFFERED/USED/DECLINED/EXPIRED
    streak_at_risk INT NOT NULL DEFAULT 0,
    source         VARCHAR(20) NULL,              -- INVENTORY/PURCHASE when USED
    offered_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    decided_at     DATETIME(6) NULL,
    PRIMARY KEY (user_id, freeze_date),
    CONSTRAINT fk_streak_freeze_day_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_streak_freeze_day_status CHECK (status IN ('OFFERED','USED','DECLINED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6) Built-in missions (existing rows are kept as tuned).
INSERT IGNORE INTO mission_definition
    (code, mission_type, title, description, target_count, reward_credits, display_order, is_active)
VALUES
    ('DAILY_CHECK_IN',    'DAILY',  'Điểm danh hôm nay',        'Ghé J-LAS và nhận thưởng điểm danh.',          1,   5,  10, 1),
    ('COMPLETE_LESSON',   'DAILY',  'Hoàn thành một bài học',   'Học xong trọn vẹn một bài học.',               1,  15,  20, 1),
    ('COMPLETE_QUIZ',     'DAILY',  'Làm một bài quiz',         'Nộp một bài quiz của bài học.',                1,  10,  30, 1),
    ('PUBLISH_SHADOWING', 'DAILY',  'Đăng một bài Shadowing',   'Chia sẻ công khai một video Shadowing.',       1,  15,  40, 1),
    ('STREAK_3',          'STREAK', 'Chuỗi 3 ngày',             'Hoàn thành mục tiêu ngày 3 ngày liên tiếp.',   3,  20, 110, 1),
    ('STREAK_7',          'STREAK', 'Chuỗi 7 ngày',             'Hoàn thành mục tiêu ngày 7 ngày liên tiếp.',   7,  50, 120, 1),
    ('STREAK_15',         'STREAK', 'Chuỗi 15 ngày',            'Hoàn thành mục tiêu ngày 15 ngày liên tiếp.', 15,  75, 125, 1),
    ('STREAK_30',         'STREAK', 'Chuỗi 30 ngày',            'Hoàn thành mục tiêu ngày 30 ngày liên tiếp.', 30, 100, 130, 1);

-- 7) Ledger idempotency backstop for databases created before W0127.
--    Skip if uk_credit_transaction_reference already exists. Check for duplicates first:
--      SELECT user_id, reason, reference_type, reference_id, COUNT(*) FROM user_credit_transaction
--      WHERE reference_type IS NOT NULL AND reference_id IS NOT NULL
--      GROUP BY user_id, reason, reference_type, reference_id HAVING COUNT(*) > 1;
-- ALTER TABLE user_credit_transaction
--     ADD UNIQUE KEY uk_credit_transaction_reference (user_id, reason, reference_type, reference_id);
