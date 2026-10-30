-- [JLAS-W0199/W0205] Apply after the base schema. Safe to rerun.
SET NAMES utf8mb4;

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
