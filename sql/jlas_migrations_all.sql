-- =============================================================================
-- JLAS - TOÀN BỘ PHẦN BỔ SUNG SAU jlas_schema.sql (gộp từ sql/migrations 001 -> 012)
--
-- Thứ tự chạy trên DB mới:
--   1) mysql -u root -p --default-character-set=utf8mb4 < jlas_schema.sql
--   2) mysql -u root -p --default-character-set=utf8mb4 < sql/jlas_migrations_all.sql
--   3) seed: sql/seeds/jlas_seed_all.sql, rồi sql/seeds/ai_call_scoring_rules.sql
--
-- Chạy lại được trên DB đang dùng: chỉ tạo bảng/cột còn thiếu (IF NOT EXISTS, kiểm tra
-- information_schema) và INSERT IGNORE dữ liệu mặc định; không xoá dữ liệu có sẵn.
-- 001, 002, 003, 007, 008, 009 đã nằm sẵn trong jlas_schema.sql nên không lặp lại ở đây.
-- MySQL 8.0.16+.
-- =============================================================================
USE jlas;
SET NAMES utf8mb4;


-- #############################################################################
-- 004) Shadowing: kiểm tra chất lượng phụ đề YouTube (subtitle_quality_review)
-- #############################################################################
CREATE TABLE IF NOT EXISTS subtitle_quality_review (
    subtitle_id             BIGINT UNSIGNED NOT NULL,
    source_type             VARCHAR(32) NOT NULL,
    source_text             TEXT NOT NULL,
    transcription_text      TEXT NOT NULL,
    similarity_score        DECIMAL(5,4) NOT NULL,
    review_status           VARCHAR(32) NOT NULL,
    review_reason           VARCHAR(500) NULL,
    reviewed_by_user_id     BIGINT UNSIGNED NULL,
    reviewed_at             DATETIME(6) NULL,
    created_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (subtitle_id),
    KEY idx_subtitle_quality_status (review_status, subtitle_id),
    KEY idx_subtitle_quality_reviewer (reviewed_by_user_id, reviewed_at),
    CONSTRAINT fk_subtitle_quality_subtitle FOREIGN KEY (subtitle_id)
        REFERENCES subtitle(id) ON DELETE CASCADE,
    CONSTRAINT fk_subtitle_quality_reviewer FOREIGN KEY (reviewed_by_user_id)
        REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- #############################################################################
-- 005) JLAS-W0250: moderation, denylist, kháng nghị, duyệt đóng góp từ điển
-- #############################################################################
-- 1) Roles used by the admin area (code already checks these names).
INSERT IGNORE INTO roles (name, description) VALUES
    ('MODERATOR',   'Kiem duyet noi dung cong dong, tu khoa cam, khang nghi, dong gop tu dien'),
    ('ADMIN',       'Quan tri: toan bo quyen kiem duyet va che tai nguoi dung'),
    ('SUPER_ADMIN', 'Quan tri cao nhat');

-- 2) Append-only audit trail: every moderation state transition writes one row
--    in the same transaction as the change itself.
CREATE TABLE IF NOT EXISTS moderation_audit_log (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT UNSIGNED NULL,              -- NULL khi tai khoan da bi xoa.
    actor_role    VARCHAR(50) NOT NULL,
    action        VARCHAR(40) NOT NULL,              -- REPORT_VALID, APPEAL_APPROVE, DENYLIST_CREATE, ...
    target_type   VARCHAR(40) NOT NULL,
    target_id     BIGINT UNSIGNED NULL,
    from_state    VARCHAR(40) NULL,
    to_state      VARCHAR(40) NULL,
    reason        VARCHAR(1000) NOT NULL,
    case_id       BIGINT UNSIGNED NULL,              -- review_case lien quan (neu co).
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_mod_audit_created (created_at),
    KEY idx_mod_audit_target (target_type, target_id, created_at),
    KEY idx_mod_audit_actor (actor_user_id, created_at),
    KEY idx_mod_audit_action (action, created_at),
    CONSTRAINT fk_mod_audit_actor FOREIGN KEY (actor_user_id)
        REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3) Denylist moves from code into moderation_policy (policy_type = TERM).
--    action: BLOCK (chan) | REVIEW (dua vao hang doi xem lai).
--    config_json.accentSensitive = true: so khop giu nguyen dau tieng Viet.
--    policy_key dung collation nhi phan: unicode_ci coi "ぽるの" = "ポルノ" va
--    "thằng chó" = "thang cho", khien unique key nuot mat bien the.
ALTER TABLE moderation_policy
    MODIFY policy_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

INSERT IGNORE INTO moderation_policy (policy_type, policy_key, language, action, config_json, is_active) VALUES
-- BLOCK, English
('TERM','porn','ALL','BLOCK',NULL,1),('TERM','xxx','ALL','BLOCK',NULL,1),('TERM','onlyfans','ALL','BLOCK',NULL,1),
('TERM','nsfw','ALL','BLOCK',NULL,1),('TERM','rape','ALL','BLOCK',NULL,1),('TERM','pedophil','ALL','BLOCK',NULL,1),
('TERM','child porn','ALL','BLOCK',NULL,1),('TERM','go fuck','ALL','BLOCK',NULL,1),('TERM','fuck you','ALL','BLOCK',NULL,1),
('TERM','motherfucker','ALL','BLOCK',NULL,1),
-- BLOCK, Vietnamese
('TERM','dit me','ALL','BLOCK',NULL,1),('TERM','deo ma','ALL','BLOCK',NULL,1),('TERM','thằng chó','ALL','BLOCK',NULL,1),
('TERM','thang cho','ALL','BLOCK',NULL,1),('TERM','óc chó','ALL','BLOCK',NULL,1),('TERM','oc cho','ALL','BLOCK',NULL,1),
('TERM','giết hết','ALL','BLOCK',NULL,1),('TERM','giet het','ALL','BLOCK',NULL,1),('TERM','terrorist bomb','ALL','BLOCK',NULL,1),
-- BLOCK, Japanese NSFW
('TERM','ポルノ','ALL','BLOCK',NULL,1),('TERM','ぽるの','ALL','BLOCK',NULL,1),('TERM','エロ動画','ALL','BLOCK',NULL,1),
('TERM','エロ画像','ALL','BLOCK',NULL,1),('TERM','エロサイト','ALL','BLOCK',NULL,1),('TERM','アダルトビデオ','ALL','BLOCK',NULL,1),
('TERM','アダルト動画','ALL','BLOCK',NULL,1),('TERM','セックス','ALL','BLOCK',NULL,1),('TERM','せっくす','ALL','BLOCK',NULL,1),
('TERM','フェラチオ','ALL','BLOCK',NULL,1),('TERM','フェラ','ALL','BLOCK',NULL,1),('TERM','まんこ','ALL','BLOCK',NULL,1),
('TERM','おまんこ','ALL','BLOCK',NULL,1),('TERM','ちんこ','ALL','BLOCK',NULL,1),('TERM','ちんぽ','ALL','BLOCK',NULL,1),
('TERM','チンポ','ALL','BLOCK',NULL,1),('TERM','おっぱい揉','ALL','BLOCK',NULL,1),('TERM','中出し','ALL','BLOCK',NULL,1),
('TERM','なかだし','ALL','BLOCK',NULL,1),('TERM','顔射','ALL','BLOCK',NULL,1),('TERM','ぶっかけ','ALL','BLOCK',NULL,1),
('TERM','レイプ','ALL','BLOCK',NULL,1),('TERM','れいぷ','ALL','BLOCK',NULL,1),('TERM','強姦','ALL','BLOCK',NULL,1),
('TERM','ごうかん','ALL','BLOCK',NULL,1),('TERM','児童ポルノ','ALL','BLOCK',NULL,1),('TERM','じどうぽるの','ALL','BLOCK',NULL,1),
('TERM','ロリコン動画','ALL','BLOCK',NULL,1),('TERM','ショタコン','ALL','BLOCK',NULL,1),
-- BLOCK, Japanese abuse / hate
('TERM','死ねボケ','ALL','BLOCK',NULL,1),('TERM','くたばれ','ALL','BLOCK',NULL,1),('TERM','クソガキ','ALL','BLOCK',NULL,1),
('TERM','くそがき','ALL','BLOCK',NULL,1),('TERM','殺しに行く','ALL','BLOCK',NULL,1),('TERM','ころしにいく','ALL','BLOCK',NULL,1),
('TERM','皆殺し','ALL','BLOCK',NULL,1),('TERM','みなごろし','ALL','BLOCK',NULL,1),('TERM','在日叩き','ALL','BLOCK',NULL,1),
('TERM','朝鮮人は出てけ','ALL','BLOCK',NULL,1),
-- BLOCK, Vietnamese short words that must keep their accents
('TERM','địt','ALL','BLOCK','{"accentSensitive": true}',1),('TERM','đụ','ALL','BLOCK','{"accentSensitive": true}',1),
('TERM','lồn','ALL','BLOCK','{"accentSensitive": true}',1),('TERM','cặc','ALL','BLOCK','{"accentSensitive": true}',1),
('TERM','buồi','ALL','BLOCK','{"accentSensitive": true}',1),('TERM','đéo','ALL','BLOCK','{"accentSensitive": true}',1),
-- REVIEW
('TERM','nude','ALL','REVIEW',NULL,1),('TERM','naked','ALL','REVIEW',NULL,1),('TERM','sex video','ALL','REVIEW',NULL,1),
('TERM','only fans','ALL','REVIEW',NULL,1),('TERM','giết người','ALL','REVIEW',NULL,1),('TERM','giet nguoi','ALL','REVIEW',NULL,1),
('TERM','tự tử','ALL','REVIEW',NULL,1),('TERM','tu tu huong dan','ALL','REVIEW',NULL,1),('TERM','hate speech','ALL','REVIEW',NULL,1),
('TERM','kill yourself','ALL','REVIEW',NULL,1),('TERM','エロ','ALL','REVIEW',NULL,1),('TERM','えろい','ALL','REVIEW',NULL,1),
('TERM','えっち','ALL','REVIEW',NULL,1),('TERM','エッチ','ALL','REVIEW',NULL,1),('TERM','ヌード','ALL','REVIEW',NULL,1),
('TERM','ぬーど','ALL','REVIEW',NULL,1),('TERM','全裸','ALL','REVIEW',NULL,1),('TERM','ぜんら','ALL','REVIEW',NULL,1),
('TERM','裸体','ALL','REVIEW',NULL,1),('TERM','らたい','ALL','REVIEW',NULL,1),('TERM','下着姿','ALL','REVIEW',NULL,1),
('TERM','自殺','ALL','REVIEW',NULL,1),('TERM','じさつ','ALL','REVIEW',NULL,1),('TERM','自殺方法','ALL','REVIEW',NULL,1),
('TERM','自殺サイト','ALL','REVIEW',NULL,1),('TERM','他殺','ALL','REVIEW',NULL,1),('TERM','テロリズム','ALL','REVIEW',NULL,1),
('TERM','爆破予告','ALL','REVIEW',NULL,1),('TERM','死ね','ALL','REVIEW',NULL,1),('TERM','しね!','ALL','REVIEW',NULL,1),
('TERM','しね！','ALL','REVIEW',NULL,1),('TERM','殺すぞ','ALL','REVIEW',NULL,1),('TERM','ころすぞ','ALL','REVIEW',NULL,1),
('TERM','ぶっ殺','ALL','REVIEW',NULL,1),('TERM','ぶっころ','ALL','REVIEW',NULL,1),('TERM','障害者は消えろ','ALL','REVIEW',NULL,1),
('TERM','部落','ALL','REVIEW',NULL,1),('TERM','在日朝鮮人','ALL','REVIEW',NULL,1);


-- #############################################################################
-- 006) JLAS-W0127/W0193: mục tiêu ngày, thưởng CP, nhiệm vụ, bảo vệ chuỗi
-- #############################################################################
-- The application also creates these tables at startup (MissionSchemaInitializer).
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

-- 7) uk_credit_transaction_reference của user_credit_transaction đã có trong jlas_schema.sql.


-- #############################################################################
-- 010) JLAS-W0199/W0205: AI Call - transcript, nhận xét từng lượt, lượt gọi trừ CP
-- #############################################################################
-- Điểm cuối phiên ghi vào các cột sẵn có của conversation_session
-- (overall_score, score_details_json, mistakes_count, suggestions, scored_at).
-- 1) Transcript: mỗi lời thoại của một phiên, thuộc đúng phiên đó (xoá phiên thì xoá theo).
CREATE TABLE IF NOT EXISTS conversation_message (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id  BIGINT UNSIGNED NOT NULL,
    role        VARCHAR(20) NOT NULL,                 -- user / assistant
    content     TEXT NOT NULL,
    state_code  VARCHAR(80) NULL,                     -- cảnh (state) của kịch bản lúc nói câu này
    meta_json   JSON NULL,                            -- tín hiệu AI: objectivesMet, readyToEnd, ...
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_conversation_message_session (session_id, created_at, id),
    CONSTRAINT fk_conversation_message_session FOREIGN KEY (session_id)
        REFERENCES conversation_session(id) ON DELETE CASCADE,
    CONSTRAINT chk_conversation_message_role CHECK (role IN ('user','assistant'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2) Nhận xét từng lượt (ngữ pháp/từ vựng) gắn với câu trả lời của AI trong cùng phiên.
CREATE TABLE IF NOT EXISTS conversation_turn_feedback (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id     BIGINT UNSIGNED NOT NULL,
    message_id     BIGINT UNSIGNED NULL,
    mistake_type   VARCHAR(80) NULL,                  -- grammar / particle / vocab / vocab_help ...
    original_text  TEXT NULL,
    corrected_text TEXT NULL,
    reason         TEXT NULL,                         -- giải thích bằng tiếng Việt
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_turn_feedback_session (session_id, created_at),
    CONSTRAINT fk_turn_feedback_session FOREIGN KEY (session_id)
        REFERENCES conversation_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_turn_feedback_message FOREIGN KEY (message_id)
        REFERENCES conversation_message(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3) Mỗi lượt chat có phí: chống trừ hai lần (client_request_id), giữ chỗ -> chốt -> hoàn.
--    PENDING chỉ dùng khi lượt hợp lệ đang chờ/xếp hàng xử lý; lỗi AI thì FAILED và hoàn lại.
--    Giao dịch CP nằm ở user_credit_transaction (reason AI_CALL_TURN / REFUND, reference AI_CALL_TURN + id).
CREATE TABLE IF NOT EXISTS ai_call_turn (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id           BIGINT UNSIGNED NOT NULL,
    session_id        BIGINT UNSIGNED NOT NULL,
    client_request_id VARCHAR(64) NOT NULL,
    status            VARCHAR(20) NOT NULL,           -- PENDING / COMPLETED / FAILED
    charge_type       VARCHAR(20) NOT NULL,           -- FREE / QUOTA / CREDIT
    quota_minutes     DECIMAL(10,3) NOT NULL DEFAULT 0,
    credit_amount     INT NOT NULL DEFAULT 0,
    usage_date        DATE NULL,                      -- ngày đã cộng phút, để hoàn đúng ngày
    error_code        VARCHAR(50) NULL,
    response_json     JSON NULL,                      -- kết quả đã trả, để gửi lại khi request bị lặp
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at      DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_call_turn_request (user_id, client_request_id),
    KEY idx_ai_call_turn_status (status, created_at),
    KEY idx_ai_call_turn_session (session_id, created_at),
    CONSTRAINT fk_ai_call_turn_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_call_turn_session FOREIGN KEY (session_id)
        REFERENCES conversation_session(id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_call_turn_status CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    CONSTRAINT chk_ai_call_turn_charge CHECK (charge_type IN ('FREE','QUOTA','CREDIT')),
    CONSTRAINT chk_ai_call_turn_credit CHECK (credit_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- #############################################################################
-- 011) Shadowing: ngân hàng câu hỏi luyện nghe đã chuẩn bị
-- #############################################################################
CREATE TABLE IF NOT EXISTS shadowing_preparation_job (
    video_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    levels_csv VARCHAR(40) NOT NULL DEFAULT '',
    kinds_csv VARCHAR(240) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'IDLE',
    progress TINYINT UNSIGNED NOT NULL DEFAULT 0,
    error_message VARCHAR(500) NULL,
    suggestions_csv VARCHAR(240) NULL,
    suggestion_hash CHAR(64) NULL,
    targets_csv VARCHAR(300) NULL,
    capacities_csv VARCHAR(300) NULL,
    suggested_jlpt_level VARCHAR(2) NULL,
    selected_jlpt_level VARCHAR(2) NULL,
    claim_token CHAR(36) NULL,
    lease_until TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_shadowing_preparation_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE,
    INDEX idx_shadowing_preparation_status (status, updated_at),
    UNIQUE KEY uk_shadowing_preparation_claim (claim_token)
);

-- Preserve existing preparation jobs when this migration is reapplied.
SET @shadowing_has_suggested_level = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shadowing_preparation_job'
      AND COLUMN_NAME = 'suggested_jlpt_level'
);
SET @shadowing_suggested_sql = IF(@shadowing_has_suggested_level = 0,
    'ALTER TABLE shadowing_preparation_job ADD COLUMN suggested_jlpt_level VARCHAR(2) NULL',
    'SELECT 1');
PREPARE shadowing_suggested_stmt FROM @shadowing_suggested_sql;
EXECUTE shadowing_suggested_stmt;
DEALLOCATE PREPARE shadowing_suggested_stmt;

SET @shadowing_has_selected_level = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shadowing_preparation_job'
      AND COLUMN_NAME = 'selected_jlpt_level'
);
SET @shadowing_selected_sql = IF(@shadowing_has_selected_level = 0,
    'ALTER TABLE shadowing_preparation_job ADD COLUMN selected_jlpt_level VARCHAR(2) NULL',
    'SELECT 1');
PREPARE shadowing_selected_stmt FROM @shadowing_selected_sql;
EXECUTE shadowing_selected_stmt;
DEALLOCATE PREPARE shadowing_selected_stmt;

SET @shadowing_has_targets = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shadowing_preparation_job' AND COLUMN_NAME = 'targets_csv'
);
SET @shadowing_targets_sql = IF(@shadowing_has_targets = 0,
    'ALTER TABLE shadowing_preparation_job ADD COLUMN targets_csv VARCHAR(300) NULL', 'SELECT 1');
PREPARE shadowing_targets_stmt FROM @shadowing_targets_sql;
EXECUTE shadowing_targets_stmt;
DEALLOCATE PREPARE shadowing_targets_stmt;

SET @shadowing_has_capacities = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shadowing_preparation_job' AND COLUMN_NAME = 'capacities_csv'
);
SET @shadowing_capacities_sql = IF(@shadowing_has_capacities = 0,
    'ALTER TABLE shadowing_preparation_job ADD COLUMN capacities_csv VARCHAR(300) NULL', 'SELECT 1');
PREPARE shadowing_capacities_stmt FROM @shadowing_capacities_sql;
EXECUTE shadowing_capacities_stmt;
DEALLOCATE PREPARE shadowing_capacities_stmt;

CREATE TABLE IF NOT EXISTS shadowing_question_bank (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT UNSIGNED NOT NULL,
    subtitle_id BIGINT UNSIGNED NOT NULL,
    source_ids_json TEXT NOT NULL,
    kind VARCHAR(40) NOT NULL,
    level_code VARCHAR(10) NOT NULL,
    prompt_text TEXT NOT NULL,
    answer_text TEXT NOT NULL,
    choices_json TEXT NOT NULL,
    explanation TEXT NULL,
    start_time DECIMAL(10,3) NOT NULL,
    end_time DECIMAL(10,3) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    occurrence_index SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    content_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_shadowing_question_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE,
    CONSTRAINT fk_shadowing_question_subtitle FOREIGN KEY (subtitle_id) REFERENCES subtitle(id) ON DELETE CASCADE,
    UNIQUE KEY uk_shadowing_question_content (video_id, content_hash),
    INDEX idx_shadowing_question_pick (video_id, status, level_code, kind)
);

CREATE TABLE IF NOT EXISTS shadowing_question_contribution (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    created_on DATE NOT NULL,
    CONSTRAINT fk_shadowing_contribution_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE,
    UNIQUE KEY uk_shadowing_contribution_day (video_id, user_id, created_on)
);

CREATE TABLE IF NOT EXISTS shadowing_question_generation_issue (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT UNSIGNED NOT NULL,
    kind VARCHAR(40) NULL,
    level_code VARCHAR(10) NULL,
    prompt_text TEXT NULL,
    answer_text TEXT NULL,
    choices_json TEXT NULL,
    reason VARCHAR(300) NOT NULL,
    start_time DECIMAL(10,3) NULL,
    attempt_number TINYINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_shadowing_generation_issue_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE,
    INDEX idx_shadowing_generation_issue_video (video_id, id)
);

-- Local startup may have added the column already; keep this migration repeatable.
SET @shadowing_has_prepared_id = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'shadowing_practice_item'
      AND COLUMN_NAME = 'prepared_question_id'
);
SET @shadowing_prepared_sql = IF(@shadowing_has_prepared_id = 0,
    'ALTER TABLE shadowing_practice_item ADD COLUMN prepared_question_id BIGINT UNSIGNED NULL, ADD INDEX idx_shadowing_practice_item_prepared (prepared_question_id)',
    'SELECT 1');
PREPARE shadowing_prepared_stmt FROM @shadowing_prepared_sql;
EXECUTE shadowing_prepared_stmt;
DEALLOCATE PREPARE shadowing_prepared_stmt;


-- #############################################################################
-- 012) Shadowing: lượt xem 30 giây và mốc thưởng CP cho chủ video
-- #############################################################################
SET @video_has_view_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'video' AND COLUMN_NAME = 'view_count'
);
SET @video_view_count_sql = IF(@video_has_view_count = 0,
    'ALTER TABLE video ADD COLUMN view_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER moderation_summary',
    'SELECT 1');
PREPARE video_view_count_stmt FROM @video_view_count_sql;
EXECUTE video_view_count_stmt;
DEALLOCATE PREPARE video_view_count_stmt;

-- Carry over views counted under the old one-view-per-account rule (only when the column is new).
SET @video_view_seed_sql = IF(@video_has_view_count = 0,
    'UPDATE video v JOIN (SELECT video_id, COUNT(*) AS views FROM video_user_state WHERE counted_as_view = 1 GROUP BY video_id) s ON s.video_id = v.id SET v.view_count = s.views',
    'SELECT 1');
PREPARE video_view_seed_stmt FROM @video_view_seed_sql;
EXECUTE video_view_seed_stmt;
DEALLOCATE PREPARE video_view_seed_stmt;

CREATE TABLE IF NOT EXISTS video_view_tracker (
    user_id         BIGINT UNSIGNED NOT NULL,
    video_id        BIGINT UNSIGNED NOT NULL,
    listened_sec    DECIMAL(10,3) NOT NULL DEFAULT 0,
    last_ping_at    DATETIME(6) NULL,
    last_counted_at DATETIME(6) NULL,
    PRIMARY KEY (user_id, video_id),
    KEY idx_video_view_tracker_video (video_id),
    CONSTRAINT fk_video_view_tracker_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_video_view_tracker_video FOREIGN KEY (video_id)
        REFERENCES video(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shadowing_view_milestone (
    video_id   BIGINT UNSIGNED NOT NULL,
    milestone  INT UNSIGNED NOT NULL,
    owner_id   BIGINT UNSIGNED NOT NULL,
    credit     INT UNSIGNED NOT NULL,
    reached_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (video_id, milestone),
    CONSTRAINT fk_shadowing_view_milestone_video FOREIGN KEY (video_id)
        REFERENCES video(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- #############################################################################
-- 013) Giáo trình: kanji gắn tay vào bài học (lesson_kanji)
-- #############################################################################
-- Trang học viên gộp kanji gắn tay với kanji suy ra từ từ vựng của bài, không trùng lặp.
CREATE TABLE IF NOT EXISTS lesson_kanji (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lesson_id   BIGINT UNSIGNED NOT NULL,
    kanji_id    BIGINT UNSIGNED NOT NULL,
    order_index INT UNSIGNED NOT NULL DEFAULT 1,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_lesson_kanji (lesson_id, kanji_id),
    KEY idx_lesson_kanji_kanji (kanji_id),
    CONSTRAINT fk_lesson_kanji_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_kanji_kanji FOREIGN KEY (kanji_id)
        REFERENCES kanji(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
