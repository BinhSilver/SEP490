-- [JLAS-PRO-CP] Kế hoạch gói Pro và CP. Chạy lại an toàn;
-- ProCpPlanSchemaInitializer cũng tự tạo bốn bảng mới khi khởi động.
--   1. quick_lookup_wallet / quick_lookup_daily: tra từ nhanh (bôi đen để tra) cho tài khoản không có Pro,
--      5 CP / 10 lượt; tra lại cùng một từ trong ngày không tốn lượt.
--   2. pronunciation_usage_daily: lượt chấm phát âm mỗi ngày (Free 5, Pro 100; quá lượt 5 CP/lần).
--   3. ai_usage_log: chi phí thật của mỗi lần gọi AI (token, byte chữ giọng đọc, giây âm thanh, USD) để chốt giá CP.
--   4. Mức thưởng nhiệm vụ và giá khung avatar mới. Chỉ đổi dòng còn giữ giá trị mặc định cũ, nên con số
--      admin đã chỉnh tay được giữ nguyên.
--   5. Bậc trang phục game (cột game_shop_skin.tier): Thường 500, Hiếm 800, Huyền thoại 1.000 CP, giá đi theo bậc.

CREATE TABLE IF NOT EXISTS quick_lookup_wallet (
    user_id    BIGINT UNSIGNED NOT NULL,
    remaining  INT UNSIGNED NOT NULL DEFAULT 0,  -- lượt tra đã mua còn lại
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_quick_lookup_wallet_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Từ đã tra trong ngày: tra lại không trừ lượt.
CREATE TABLE IF NOT EXISTS quick_lookup_daily (
    user_id       BIGINT UNSIGNED NOT NULL,
    lookup_date   DATE NOT NULL,
    vocabulary_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (user_id, lookup_date, vocabulary_id),
    CONSTRAINT fk_quick_lookup_daily_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pronunciation_usage_daily (
    user_id    BIGINT UNSIGNED NOT NULL,
    usage_date DATE NOT NULL,
    attempts   INT UNSIGNED NOT NULL DEFAULT 0,  -- lượt chấm trong hạn mức ngày (lượt trả bằng CP không tính)
    PRIMARY KEY (user_id, usage_date),
    CONSTRAINT fk_pronunciation_usage_daily_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ai_usage_log (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id           BIGINT UNSIGNED NULL,
    feature           VARCHAR(40) NOT NULL,      -- AI_CALL_REPLY | AI_CALL_HELPER | AI_CALL_TTS | PRONUNCIATION_CHECK
    provider          VARCHAR(40) NOT NULL,      -- openrouter | fish-audio | azure
    model             VARCHAR(120) NULL,
    prompt_tokens     INT UNSIGNED NULL,
    completion_tokens INT UNSIGNED NULL,
    reasoning_tokens  INT UNSIGNED NULL,
    cached_tokens     INT UNSIGNED NULL,
    characters        INT UNSIGNED NULL,         -- giọng đọc: số byte UTF-8 của câu (Fish Audio tính tiền theo byte)
    audio_seconds     DECIMAL(8,2) NULL,         -- chấm phát âm: độ dài bản thu
    cost_usd          DECIMAL(14,8) NULL,        -- chi phí nhà cung cấp trả về (OpenRouter usage.cost)
    created_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_usage_feature_created (feature, created_at),
    KEY idx_ai_usage_user_created (user_id, created_at),
    CONSTRAINT fk_ai_usage_log_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Thưởng nhiệm vụ mới (trần CP từ học: Free 40, Pro 50 mỗi ngày).
UPDATE mission_definition SET reward_credits = 10 WHERE code = 'COMPLETE_LESSON' AND reward_credits = 15;
UPDATE mission_definition SET reward_credits = 5 WHERE code = 'COMPLETE_QUIZ' AND reward_credits = 10;
UPDATE mission_definition SET reward_credits = 10 WHERE code = 'PUBLISH_SHADOWING' AND reward_credits = 15;

-- Khung avatar 300 → 400 CP.
UPDATE avatar_frame SET price = 400
WHERE code IN ('frame-cosmic', 'frame-seraph', 'frame-inferno', 'frame-vampire') AND price = 300;

-- Bậc trang phục game: Thường 500, Hiếm 800, Huyền thoại 1.000 CP; giá đi theo bậc, trang phục mặc định miễn phí.
-- (GameShopSchemaInitializer cũng thêm cột tier khi khởi động.)
SET @j066_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'game_shop_skin' AND COLUMN_NAME = 'tier');
SET @j066_sql = IF(@j066_exists = 0,
                   'ALTER TABLE game_shop_skin ADD COLUMN tier VARCHAR(20) NOT NULL DEFAULT ''COMMON''', 'DO 0');
PREPARE j066_stmt FROM @j066_sql;
EXECUTE j066_stmt;
DEALLOCATE PREPARE j066_stmt;

-- Trang phục có sẵn còn giữ giá mặc định cũ (150 / 200) nhận bậc đã chọn.
UPDATE game_shop_skin SET tier = 'COMMON', price = 500
WHERE code IN ('cat-cowboy', 'cat-wizard') AND starter = FALSE AND price IN (150, 200);
UPDATE game_shop_skin SET tier = 'RARE', price = 800
WHERE code IN ('cat-godzilla', 'cat-ronova', 'plane-halo-fighter', 'plane-aegis-frame') AND starter = FALSE AND price IN (150, 200);
UPDATE game_shop_skin SET tier = 'LEGENDARY', price = 1000
WHERE code IN ('cat-zenitsu', 'cat-tanjiro', 'plane-split-core', 'plane-void-blade') AND starter = FALSE AND price IN (150, 200);

-- Các trang phục khác (admin tải lên) về bậc gần nhất với giá đang bán, rồi giá theo bậc. Ba lệnh dưới không lọc
-- theo khoá nên tạm tắt safe update mode của MySQL Workbench (lỗi 1175), xong thì trả lại như cũ.
SET @j066_safe_updates = @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;
UPDATE game_shop_skin
SET tier = CASE WHEN price >= 1000 THEN 'LEGENDARY' WHEN price >= 800 THEN 'RARE' ELSE 'COMMON' END,
    price = CASE WHEN price >= 1000 THEN 1000 WHEN price >= 800 THEN 800 ELSE 500 END
WHERE starter = FALSE AND price NOT IN (500, 800, 1000);
UPDATE game_shop_skin
SET tier = CASE price WHEN 1000 THEN 'LEGENDARY' WHEN 800 THEN 'RARE' ELSE 'COMMON' END
WHERE starter = FALSE AND price IN (500, 800, 1000);
UPDATE game_shop_skin SET tier = 'COMMON', price = 0 WHERE starter = TRUE;
SET SQL_SAFE_UPDATES = @j066_safe_updates;
