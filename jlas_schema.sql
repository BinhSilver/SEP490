-- =============================================================================
-- JLAS COMPLETE RUNTIME SCHEMA (MySQL 8.0.16+)
-- Canonical one-shot schema for jlas.
-- Includes the merged core plus Shadowing series, pronunciation history,
-- listening practice, and retention onboarding columns.
--
-- Run once:
--   mysql -u root -p < sql/jlas_schema.sql
-- Then load data once:
--   mysql -u root -p < sql/data_full.sql
-- =============================================================================

CREATE DATABASE IF NOT EXISTS jlas
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE jlas;
SET NAMES utf8mb4;

-- =============================================================================
-- SO DO QUAN HE CHO KANJI SEARCH VA PAYMENT
-- =============================================================================
-- KANJI SEARCH:
--   radical (1) -------- (N) kanji
--   kanji  (1) -------- (N) kanji_reading
--   vocabulary_entry (1) -- (N) vocabulary_reading
--   vocabulary_entry (1) -- (N) vocabulary_variant
--   vocabulary_entry (1) -- (N) vocabulary_sense
--   vocabulary_sense (1) -- (N) vocabulary_gloss
--   vocabulary_sense (N) -- (N) dictionary_domain, qua sense_domain
--   kanji (N) ----------- (N) vocabulary_entry, qua vocabulary_kanji
--
-- PAYMENT:
--   users          (1) -- (N) payments
--   premium_plans  (1) -- (N) payments
--   users.premium_plan_id cho biet goi hien tai cua user.
--   users.premium_expired_at cho biet thoi diem het han hien tai.
--   payments luu lich su giao dich; khong thay the trang thai premium trong users.
--
-- LUONG TRA KANJI:
--   DRAW -> AI/WASM -> kanji.character
--   -> vocabulary_kanji -> vocabulary_entry
--   Vi du: 間 -> 時間 -> じかん -> thoi gian.
-- Model chi nhan dien Kanji don. Cac tu lien quan nhu 大人, 大学 duoc lay tu
-- vocabulary_entry qua vocabulary_kanji, khong phai do model tu ghep ky tu.
-- Khi user chon Kanji hoac tu goi y, gia tri duoc dua len thanh tim kiem va chay
-- chung luong tra tu dien. dictionary_search_history chi ghi mot lan tim kiem
-- tu dien; khong tao lich su nhan dien rieng va khong luu net ve.
-- Net ve chi ton tai tren client trong luc nhan dien; khong luu vao MySQL.
-- =============================================================================

-- #############################################################################
-- 1) AUTH VA TAI KHOAN
-- #############################################################################

-- Vai tro he thong, vi du ADMIN hoac LEARNER.
CREATE TABLE roles (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name        VARCHAR(50) NOT NULL,              -- Ma vai tro duy nhat.
    description VARCHAR(255) NULL,                 -- Mo ta quyen cua vai tro.
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Goi premium va thoi han su dung.
CREATE TABLE premium_plans (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(500) NULL,
    price         DECIMAL(12,2) NOT NULL,          -- Gia goi.
    duration_days INT UNSIGNED NOT NULL,           -- So ngay hieu luc.
    is_active     TINYINT(1) NOT NULL DEFAULT 1,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                  ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_premium_plan_name (name),
    CONSTRAINT chk_premium_plan_price CHECK (price >= 0),
    CONSTRAINT chk_premium_plan_duration CHECK (duration_days > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Nguon tai khoan duy nhat cua toan he thong; thay user_account trong more.txt.
CREATE TABLE users (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    role_id            BIGINT UNSIGNED NOT NULL,  -- Vai tro cua user.
    premium_plan_id    BIGINT UNSIGNED NULL,     -- FK -> premium_plans.id; goi premium hien tai.
    email              VARCHAR(255) NOT NULL,     -- Dinh danh dang nhap chinh.
    username           VARCHAR(50) NULL,          -- Ten dang nhap neu du lieu cu co dung.
    password_hash      VARCHAR(255) NULL,
    login_type         VARCHAR(20) NOT NULL,      -- LOCAL hoac GOOGLE.
    google_id          VARCHAR(255) NULL,
    full_name          VARCHAR(150) NULL,
    avatar_url         VARCHAR(500) NULL,
    phone_number       VARCHAR(30) NULL,
    gender             VARCHAR(20) NULL,
    japanese_level     VARCHAR(10) NULL,
    premium_expired_at DATETIME(6) NULL,         -- Ngay het han premium hien tai; NULL neu chua co.
    credit_balance     INT NOT NULL DEFAULT 0,    -- So credit hien tai, doc nhanh.
    is_verified        TINYINT(1) NOT NULL DEFAULT 0,
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                       ON UPDATE CURRENT_TIMESTAMP(6),
    last_login_at      DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_email (email),
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_google_id (google_id),
    KEY idx_user_role (role_id),
    KEY idx_user_premium_expiry (premium_expired_at),
    CONSTRAINT fk_user_role FOREIGN KEY (role_id) REFERENCES roles(id),
    CONSTRAINT fk_user_premium_plan FOREIGN KEY (premium_plan_id)
        REFERENCES premium_plans(id) ON DELETE SET NULL,
    CONSTRAINT chk_user_login_type CHECK (login_type IN ('LOCAL','GOOGLE')),
    CONSTRAINT chk_user_credit_balance CHECK (credit_balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Lich su thanh toan premium.
-- Quan he:
--   payments.user_id -> users.id: user thuc hien giao dich.
--   payments.premium_plan_id -> premium_plans.id: goi duoc mua.
-- Moi payment la mot giao dich doc lap; khong xoa theo user/plan de giu du lieu doi soat.
CREATE TABLE payments (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, -- Khoa chinh giao dich noi bo.
    user_id                 BIGINT UNSIGNED NOT NULL,                -- FK -> users.id; nguoi thanh toan.
    premium_plan_id         BIGINT UNSIGNED NOT NULL,                -- FK -> premium_plans.id; goi duoc mua.
    provider                VARCHAR(30) NOT NULL,                    -- Cong thanh toan, vi du VNPAY/MOMO.
    order_code              VARCHAR(100) NOT NULL,                   -- Ma don hang gui sang cong thanh toan.
    provider_transaction_id VARCHAR(150) NULL,                       -- Ma giao dich do cong thanh toan tra ve.
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING/PAID/FAILED/CANCELLED/REFUNDED.
    amount_paid             DECIMAL(12,2) NOT NULL,                 -- So tien thuc te user da thanh toan.
    currency                CHAR(3) NOT NULL DEFAULT 'VND',         -- Ma tien te ISO 4217, vi du VND/USD.
    response_code           VARCHAR(50) NULL,                        -- Ma ket qua cuoi cung tu cong thanh toan.
    plan_name_snapshot      VARCHAR(100) NOT NULL,                   -- Ten goi tai luc mua, phuc vu hoa don lich su.
    duration_days_snapshot  INT UNSIGNED NOT NULL,                   -- So ngay duoc mua tai luc giao dich.
    paid_at                 DATETIME(6) NULL,                        -- Thoi diem thanh toan thanh cong.
    created_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), -- Luc tao payment.
    updated_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                            ON UPDATE CURRENT_TIMESTAMP(6),         -- Luc payment thay doi gan nhat.
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_order (provider, order_code),              -- Mot ma don chi xuat hien mot lan/provider.
    UNIQUE KEY uk_payment_provider_transaction
        (provider, provider_transaction_id),                         -- Chong ghi trung callback giao dich.
    KEY idx_payment_user_created (user_id, created_at),              -- Xem lich su thanh toan cua mot user.
    KEY idx_payment_status_created (status, created_at),             -- Loc payment theo trang thai/thoi gian.
    CONSTRAINT fk_payment_user FOREIGN KEY (user_id)
        REFERENCES users(id),                                       -- RESTRICT mac dinh de bao toan lich su.
    CONSTRAINT fk_payment_plan FOREIGN KEY (premium_plan_id)
        REFERENCES premium_plans(id),                               -- RESTRICT mac dinh de bao toan lich su.
    CONSTRAINT chk_payment_status
        CHECK (status IN ('PENDING','PAID','FAILED','CANCELLED','REFUNDED')),
    CONSTRAINT chk_payment_amount CHECK (amount_paid >= 0),
    CONSTRAINT chk_payment_duration CHECK (duration_days_snapshot > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Token xac thuc dung chung cho refresh, verify email va reset password.
CREATE TABLE auth_tokens (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,
    token_type  VARCHAR(30) NOT NULL,          -- REFRESH/VERIFY_EMAIL/RESET_PASSWORD.
    token_hash  CHAR(64) NOT NULL,             -- Chi luu hash, khong luu token tho.
    otp_hash    CHAR(64) NULL,
    device_info VARCHAR(255) NULL,
    ip_address  VARCHAR(45) NULL,
    expires_at  DATETIME(6) NOT NULL,
    revoked_at  DATETIME(6) NULL,
    used_at     DATETIME(6) NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_token_hash (token_hash),
    KEY idx_auth_token_user_type_expiry (user_id, token_type, expires_at),
    KEY idx_auth_token_expiry (expires_at),
    CONSTRAINT fk_auth_token_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_auth_token_type
        CHECK (token_type IN ('REFRESH','VERIFY_EMAIL','RESET_PASSWORD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Yeu cau doi email: chi luu hash cua hai OTP, khong luu ma ro.
CREATE TABLE email_change_requests (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id        BIGINT UNSIGNED NOT NULL,
    new_email      VARCHAR(255) NOT NULL,
    old_otp_hash   CHAR(64) NOT NULL,
    new_otp_hash   CHAR(64) NOT NULL,
    attempt_count  INT UNSIGNED NOT NULL DEFAULT 0,
    expires_at     DATETIME(6) NOT NULL,
    confirmed_at   DATETIME(6) NULL,
    revoked_at     DATETIME(6) NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_email_change_user_active (user_id, confirmed_at, revoked_at, created_at),
    KEY idx_email_change_expiry (expires_at),
    CONSTRAINT fk_email_change_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- #############################################################################
-- 2) AI CALL / KAIWA
-- #############################################################################

-- Nhom scenario hoi thoai.
CREATE TABLE category (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code          VARCHAR(50) NOT NULL,
    title         VARCHAR(200) NOT NULL,
    title_ja      VARCHAR(200) NULL,
    description   TEXT NULL,
    icon          VARCHAR(100) NULL,
    thumbnail_url VARCHAR(500) NULL,
    display_order INT NOT NULL DEFAULT 0,
    is_active     TINYINT(1) NOT NULL DEFAULT 1,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                  ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Vai AI va vai nguoi hoc trong scenario.
CREATE TABLE scenario_role (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    role_kind    VARCHAR(20) NOT NULL,          -- AI hoac LEARNER.
    code         VARCHAR(80) NOT NULL,
    name         VARCHAR(150) NOT NULL,
    description  TEXT NULL,
    personality  TEXT NULL,
    speech_style TEXT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_scenario_role_kind_code (role_kind, code),
    KEY idx_scenario_role_kind (role_kind),
    CONSTRAINT chk_scenario_role_kind CHECK (role_kind IN ('AI','LEARNER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Mau system prompt co version.
CREATE TABLE prompt_template (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code          VARCHAR(80) NOT NULL,
    name          VARCHAR(200) NOT NULL,
    system_prompt MEDIUMTEXT NOT NULL,
    version       VARCHAR(20) NOT NULL DEFAULT '1.0',
    is_active     TINYINT(1) NOT NULL DEFAULT 1,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                  ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prompt_template_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tinh huong hoi thoai; flow_json chua state/condition/hint/ending runtime.
CREATE TABLE scenario (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    category_id           BIGINT UNSIGNED NOT NULL,
    ai_role_id            BIGINT UNSIGNED NULL,
    learner_role_id       BIGINT UNSIGNED NULL,
    prompt_template_id    BIGINT UNSIGNED NULL,
    title                 VARCHAR(200) NOT NULL,
    slug                  VARCHAR(120) NOT NULL,
    description           TEXT NULL,
    jlpt_level            VARCHAR(10) NOT NULL DEFAULT 'N5',
    difficulty            VARCHAR(20) NOT NULL DEFAULT 'EASY',
    estimated_minutes     SMALLINT UNSIGNED NOT NULL DEFAULT 5,
    background            TEXT NULL,
    location              VARCHAR(200) NULL,
    time_of_day           VARCHAR(100) NULL,
    culture_note          TEXT NULL,
    flow_json             JSON NOT NULL,       -- Cau hinh luong hoi thoai.
    learning_content_json JSON NULL,
    thumbnail_url         VARCHAR(500) NULL,
    is_active             TINYINT(1) NOT NULL DEFAULT 1,
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                          ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_scenario_slug (slug),
    KEY idx_scenario_category (category_id, is_active),
    CONSTRAINT fk_scenario_category FOREIGN KEY (category_id) REFERENCES category(id),
    CONSTRAINT fk_scenario_ai_role FOREIGN KEY (ai_role_id)
        REFERENCES scenario_role(id) ON DELETE SET NULL,
    CONSTRAINT fk_scenario_learner_role FOREIGN KEY (learner_role_id)
        REFERENCES scenario_role(id) ON DELETE SET NULL,
    CONSTRAINT fk_scenario_prompt FOREIGN KEY (prompt_template_id)
        REFERENCES prompt_template(id) ON DELETE SET NULL,
    CONSTRAINT chk_scenario_jlpt CHECK (jlpt_level IN ('N5','N4','N3','N2','N1')),
    CONSTRAINT chk_scenario_estimated_minutes CHECK (estimated_minutes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Rule cham diem/feedback dung chung.
CREATE TABLE evaluation_rule (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rule_type   VARCHAR(20) NOT NULL,       -- FEEDBACK hoac SCORING.
    code        VARCHAR(80) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description TEXT NULL,
    priority    INT NOT NULL DEFAULT 0,
    max_score   SMALLINT UNSIGNED NULL,
    weight      SMALLINT UNSIGNED NULL,
    is_enabled  TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_evaluation_rule_code (code),
    CONSTRAINT chk_evaluation_rule_type CHECK (rule_type IN ('FEEDBACK','SCORING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Bridge scenario va rule danh gia.
CREATE TABLE scenario_evaluation_rule (
    scenario_id BIGINT UNSIGNED NOT NULL,
    rule_id     BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (scenario_id, rule_id),
    CONSTRAINT fk_scenario_eval_scenario FOREIGN KEY (scenario_id)
        REFERENCES scenario(id) ON DELETE CASCADE,
    CONSTRAINT fk_scenario_eval_rule FOREIGN KEY (rule_id)
        REFERENCES evaluation_rule(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tom tat phien hoi thoai; transcript dang chay nam o Redis/in-memory.
CREATE TABLE conversation_session (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id            BIGINT UNSIGNED NOT NULL,
    scenario_id        BIGINT UNSIGNED NOT NULL,
    status             VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS',
    overall_score      DECIMAL(5,2) NULL,
    score_details_json JSON NULL,
    mistakes_count     INT UNSIGNED NOT NULL DEFAULT 0,
    suggestions        TEXT NULL,
    started_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ended_at           DATETIME(6) NULL,
    scored_at          DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_session_user_started (user_id, started_at),
    KEY idx_session_scenario (scenario_id),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_session_scenario FOREIGN KEY (scenario_id)
        REFERENCES scenario(id),
    CONSTRAINT chk_session_score
        CHECK (overall_score IS NULL OR overall_score BETWEEN 0 AND 100),
    CONSTRAINT chk_session_time CHECK (ended_at IS NULL OR ended_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- #############################################################################
-- 3) TAG, TU DIEN VA GIAO TRINH DUNG CHUNG
-- #############################################################################

-- Catalog tag dung chung cho video, lesson va question.
CREATE TABLE tag (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code       VARCHAR(100) NOT NULL,       -- Business key on dinh, vi du N5-PARTICLE.
    name       VARCHAR(255) NOT NULL,       -- Ten hien thi.
    tag_type   VARCHAR(20) NOT NULL,        -- VIDEO/KNOWLEDGE/SITUATION/SKILL.
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag_code (code),
    KEY idx_tag_type_name (tag_type, name),
    CONSTRAINT chk_tag_type
        CHECK (tag_type IN ('VIDEO','KNOWLEDGE','SITUATION','SKILL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Danh muc bo thu Kanji.
-- Quan he: radical (1) -> (N) kanji qua kanji.radical_id.
-- Tach bang de mot bo thu chi duoc khai bao mot lan va co the dung cho nhieu Kanji.
CREATE TABLE radical (
    id             SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT, -- Khoa chinh bo thu.
    radical_number SMALLINT UNSIGNED NOT NULL,                -- So thu tu bo thu chuan, vi du 169.
    `character`    VARCHAR(8) NOT NULL,                        -- Ky tu bo thu, vi du 門.
    name_ja        VARCHAR(100) NULL,                          -- Ten/cach goi bo thu bang tieng Nhat.
    name_vi        VARCHAR(100) NULL,                          -- Ten bo thu bang tieng Viet.
    meaning_vi     VARCHAR(255) NULL,                          -- Nghia ngan gon cua bo thu.
    stroke_count   TINYINT UNSIGNED NULL,                      -- So net cua rieng bo thu.
    PRIMARY KEY (id),
    UNIQUE KEY uk_radical_number (radical_number),             -- Moi so bo thu la duy nhat.
    UNIQUE KEY uk_radical_character (`character`),             -- Moi ky tu bo thu la duy nhat.
    CONSTRAINT chk_radical_number CHECK (radical_number > 0),
    CONSTRAINT chk_radical_stroke_count
        CHECK (stroke_count IS NULL OR stroke_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Thong tin mot chu Kanji.
-- Quan he:
--   kanji.radical_id -> radical.id: bo thu chinh cua Kanji.
--   kanji (1) -> (N) kanji_reading: cac cach doc tieng Nhat On/Kun/Nanori.
-- Am Han Viet va nghia Viet la metadata hien thi cua Kanji, nen luu truc tiep tai day,
-- khong tron vao bang cach doc tieng Nhat.
CREATE TABLE kanji (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, -- Khoa chinh Kanji.
    `character`    VARCHAR(8) NOT NULL,                      -- Ky tu Kanji duy nhat, vi du 間.
    unicode_code   VARCHAR(10) NOT NULL,                     -- Ma Unicode, vi du 9593.
    radical_id     SMALLINT UNSIGNED NULL,                   -- FK -> radical.id; bo thu chinh.
    stroke_count   TINYINT UNSIGNED NULL,                    -- Tong so net cua Kanji.
    han_viet       VARCHAR(255) NULL,                        -- Am Han Viet hien thi, vi du GIAN.
    meaning_vi     TEXT NULL,                                -- Nghia Viet tom tat tren trang chi tiet Kanji.
    jlpt_level     VARCHAR(10) NULL,                         -- Cap JLPT N5..N1; NULL neu chua xep cap.
    stroke_svg_uri VARCHAR(500) NULL,                        -- URI SVG huong dan thu tu net o trang chi tiet.
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), -- Luc tao du lieu.
    updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                   ON UPDATE CURRENT_TIMESTAMP(6),          -- Luc cap nhat gan nhat.
    PRIMARY KEY (id),
    UNIQUE KEY uk_kanji_character (`character`),
    UNIQUE KEY uk_kanji_unicode (unicode_code),
    KEY idx_kanji_radical (radical_id),                    -- Tim Kanji theo bo thu.
    KEY idx_kanji_han_viet (han_viet),
    KEY idx_kanji_jlpt (jlpt_level),
    CONSTRAINT fk_kanji_radical FOREIGN KEY (radical_id)
        REFERENCES radical(id) ON DELETE SET NULL,         -- Xoa bo thu khong xoa Kanji.
    CONSTRAINT chk_kanji_jlpt
        CHECK (jlpt_level IS NULL OR jlpt_level IN ('N5','N4','N3','N2','N1')),
    CONSTRAINT chk_kanji_stroke_count
        CHECK (stroke_count IS NULL OR stroke_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Danh sach cach doc chuan hoa cua tung Kanji.
-- Quan he: nhieu kanji_reading thuoc mot kanji qua kanji_id.
-- Neu Kanji chi co On thi chi tao dong ON; khong can tao dong KUN rong va nguoc lai.
-- Moi cach doc duoc luu mot dong de co the tim/index rieng, khong ghep bang dau phay.
CREATE TABLE kanji_reading (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, -- Khoa chinh cach doc.
    kanji_id     BIGINT UNSIGNED NOT NULL,                -- FK -> kanji.id; Kanji so huu cach doc.
    reading_type VARCHAR(20) NOT NULL,                     -- ON/KUN/NANORI/OTHER.
    reading_text VARCHAR(100) NOT NULL,                    -- Noi dung cach doc, vi du カン hoac あいだ.
    is_common    TINYINT(1) NOT NULL DEFAULT 0,            -- 1 neu la cach doc thuong dung.
    PRIMARY KEY (id),
    UNIQUE KEY uk_kanji_reading (kanji_id, reading_type, reading_text), -- Chong trung reading.
    KEY idx_kanji_reading_text (reading_text),             -- Tim Kanji theo cach doc.
    CONSTRAINT fk_kanji_reading_kanji FOREIGN KEY (kanji_id)
        REFERENCES kanji(id) ON DELETE CASCADE,            -- Xoa Kanji thi xoa cac reading cua no.
    CONSTRAINT chk_kanji_reading_type
        CHECK (reading_type IN ('ON','KUN','NANORI','OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tu hoan chinh; reading la cach doc CHINH va la nguon furigana uu tien.
-- Quan he:
--   vocabulary_entry (1) -> (N) vocabulary_reading: cac cach doc khac.
--   vocabulary_entry (1) -> (N) vocabulary_variant: cac cach viet khac.
--   vocabulary_entry (1) -> (N) vocabulary_sense: cac nghia tu dien.
-- Cac cot meaning/word_type/example_* tiep tuc phuc vu du lieu giao trinh;
-- du lieu tu dien chuan hoa nam trong vocabulary_sense va vocabulary_gloss.
CREATE TABLE vocabulary_entry (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, -- Khoa chinh tu vung.
    expression            VARCHAR(255) NOT NULL,                   -- Dang viet, vi du 時間.
    normalized_expression VARCHAR(255) NOT NULL,                   -- Dang chuan hoa de tim/chong trung.
    reading               VARCHAR(255) NOT NULL,                   -- Cach doc chinh, vi du じかん.
    normalized_reading    VARCHAR(255) NOT NULL,                   -- Cach doc da chuan hoa.
    reading_romaji        VARCHAR(255) NULL,                       -- Romaji cua cach doc chinh.
    meaning               TEXT NULL,                               -- Nghia tom tat cho noi dung giao trinh.
    word_type             VARCHAR(50) NULL,                        -- Tu loai tom tat cho giao trinh.
    jlpt_level            VARCHAR(10) NULL,                        -- Cap JLPT N5..N1.
    example_ja            TEXT NULL,                               -- Cau vi du tieng Nhat.
    example_vi            TEXT NULL,                               -- Ban dich Viet cua cau vi du.
    usage_scope           VARCHAR(20) NOT NULL DEFAULT 'TEXTBOOK', -- TEXTBOOK/DICTIONARY/BOTH.
    dictionary_status     VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',-- DRAFT/PUBLISHED/HIDDEN.
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), -- Luc tao.
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                          ON UPDATE CURRENT_TIMESTAMP(6),          -- Luc cap nhat gan nhat.
    PRIMARY KEY (id),
    UNIQUE KEY uk_vocabulary_normalized
        (normalized_expression, normalized_reading),
    KEY idx_vocabulary_expression (normalized_expression),
    KEY idx_vocabulary_reading (normalized_reading),
    KEY idx_vocabulary_romaji (reading_romaji),
    KEY idx_vocabulary_jlpt (jlpt_level),
    CONSTRAINT chk_vocabulary_jlpt
        CHECK (jlpt_level IS NULL OR jlpt_level IN ('N5','N4','N3','N2','N1')),
    CONSTRAINT chk_vocabulary_scope
        CHECK (usage_scope IN ('TEXTBOOK','DICTIONARY','BOTH')),
    CONSTRAINT chk_vocabulary_dictionary_status
        CHECK (dictionary_status IN ('DRAFT','PUBLISHED','HIDDEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Cac cach doc KHAC cua mot vocabulary_entry.
-- Quan he: nhieu vocabulary_reading thuoc mot vocabulary_entry qua vocabulary_id.
-- vocabulary_entry.reading van la cach doc chinh cho code cu; bang nay chi bo sung
-- truong hop mot tu co nhieu cach doc, vi du 今日: きょう va こんにち.
CREATE TABLE vocabulary_reading (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, -- Khoa chinh reading bo sung.
    vocabulary_id      BIGINT UNSIGNED NOT NULL,                -- FK -> vocabulary_entry.id.
    reading            VARCHAR(255) NOT NULL,                    -- Cach doc khac cua tu.
    normalized_reading VARCHAR(255) NOT NULL,                    -- Cach doc phu da chuan hoa.
    reading_romaji     VARCHAR(255) NULL,                        -- Romaji cua cach doc phu.
    usage_note         VARCHAR(255) NULL,                        -- Ghi chu ngu canh/cach dung cua reading.
    PRIMARY KEY (id),
    UNIQUE KEY uk_vocabulary_reading (vocabulary_id, normalized_reading),
    KEY idx_vocabulary_alt_reading (normalized_reading),    -- Tim tu theo cach doc bo sung.
    KEY idx_vocabulary_alt_romaji (reading_romaji),
    CONSTRAINT fk_vocabulary_reading_entry FOREIGN KEY (vocabulary_id)
        REFERENCES vocabulary_entry(id) ON DELETE CASCADE  -- Xoa tu thi xoa reading bo sung.
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Cach viet bien the cua mot muc tu; chi tao dong khi thuc su co bien the.
CREATE TABLE vocabulary_variant (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    vocabulary_id         BIGINT UNSIGNED NOT NULL,
    variant_expression    VARCHAR(255) NOT NULL,
    normalized_expression VARCHAR(255) NOT NULL,
    variant_type          VARCHAR(30) NOT NULL DEFAULT 'OTHER',
    PRIMARY KEY (id),
    UNIQUE KEY uk_vocabulary_variant (vocabulary_id, normalized_expression),
    KEY idx_vocabulary_variant_expression (normalized_expression),
    CONSTRAINT fk_vocabulary_variant_entry FOREIGN KEY (vocabulary_id)
        REFERENCES vocabulary_entry(id) ON DELETE CASCADE,
    CONSTRAINT chk_vocabulary_variant_type CHECK
        (variant_type IN ('KANJI_VARIANT','ORTHOGRAPHIC','KANA','OLD_FORM','OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Bang noi Kanji don voi cac muc tu co chua Kanji do.
-- Quan he:
--   kanji (N) <-> (N) vocabulary_entry qua vocabulary_kanji.
-- Ly do can bang nay:
--   1. Tim nhanh cac tu chua Kanji sau khi model nhan dien, khong dung
--      WHERE expression LIKE '%Kanji%' tren toan bo tu dien.
--   2. Biet vi tri cua Kanji trong expression, vi du 日 o vi tri 1 trong 日本
--      va vi tri 2 trong 毎日.
--   3. Ho tro goi y tu bat dau bang Kanji Top 1 bang character_position = 1.
--   4. Mot Kanji co the xuat hien nhieu lan trong cung mot tu, nen vi tri nam
--      trong khoa chinh.
-- Du lieu bang nay do backend/import script sinh tu vocabulary_entry.expression;
-- khong nhap thu cong va khong thay the cot expression.
CREATE TABLE vocabulary_kanji (
    vocabulary_id      BIGINT UNSIGNED NOT NULL,    -- FK -> vocabulary_entry.id.
    kanji_id           BIGINT UNSIGNED NOT NULL,    -- FK -> kanji.id.
    character_position SMALLINT UNSIGNED NOT NULL,  -- Vi tri ky tu, bat dau tu 1.
    PRIMARY KEY (vocabulary_id, kanji_id, character_position),
    KEY idx_vocabulary_kanji_lookup (kanji_id, vocabulary_id),
    KEY idx_vocabulary_kanji_prefix
        (kanji_id, character_position, vocabulary_id),
    CONSTRAINT fk_vocabulary_kanji_vocabulary FOREIGN KEY (vocabulary_id)
        REFERENCES vocabulary_entry(id) ON DELETE CASCADE,
    CONSTRAINT fk_vocabulary_kanji_kanji FOREIGN KEY (kanji_id)
        REFERENCES kanji(id) ON DELETE CASCADE,
    CONSTRAINT chk_vocabulary_kanji_position
        CHECK (character_position > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Moi dong la mot nghia/ngu canh nghia cua muc tu.
CREATE TABLE vocabulary_sense (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    vocabulary_id BIGINT UNSIGNED NOT NULL,
    sense_order   SMALLINT UNSIGNED NOT NULL,
    usage_note    VARCHAR(1000) NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                  ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_vocabulary_sense_order (vocabulary_id, sense_order),
    CONSTRAINT fk_vocabulary_sense_entry FOREIGN KEY (vocabulary_id)
        REFERENCES vocabulary_entry(id) ON DELETE CASCADE,
    CONSTRAINT chk_vocabulary_sense_order CHECK (sense_order > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Dien giai cua tung nghia theo ngon ngu; da rut gon theo ban chot.
CREATE TABLE vocabulary_gloss (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    sense_id      BIGINT UNSIGNED NOT NULL,
    language_code VARCHAR(10) NOT NULL,
    gloss_text    VARCHAR(2000) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_vocabulary_gloss_text (language_code, gloss_text(255)),
    CONSTRAINT fk_vocabulary_gloss_sense FOREIGN KEY (sense_id)
        REFERENCES vocabulary_sense(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Danh muc tu loai dung chung cho cac nghia tu dien.
CREATE TABLE dictionary_part_of_speech (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(50) NOT NULL,
    name_vi     VARCHAR(150) NOT NULL,
    name_en     VARCHAR(150) NULL,
    name_ja     VARCHAR(150) NULL,
    description VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dictionary_pos_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Mot nghia co the co nhieu tu loai va mot tu loai co the thuoc nhieu nghia.
CREATE TABLE sense_part_of_speech (
    sense_id         BIGINT UNSIGNED NOT NULL,
    part_of_speech_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (sense_id, part_of_speech_id),
    CONSTRAINT fk_sense_pos_sense FOREIGN KEY (sense_id)
        REFERENCES vocabulary_sense(id) ON DELETE CASCADE,
    CONSTRAINT fk_sense_pos_pos FOREIGN KEY (part_of_speech_id)
        REFERENCES dictionary_part_of_speech(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Danh sach chuyen nganh dang phang: GENERAL, IT, MEDICAL, ELECTRICAL...
CREATE TABLE dictionary_domain (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code          VARCHAR(80) NOT NULL,
    name_vi       VARCHAR(150) NOT NULL,
    name_en       VARCHAR(150) NULL,
    name_ja       VARCHAR(150) NULL,
    description   VARCHAR(1000) NULL,
    is_active     TINYINT(1) NOT NULL DEFAULT 1,
    display_order INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dictionary_domain_code (code),
    KEY idx_dictionary_domain_display (is_active, display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Gan tung nghia vao mot hoac nhieu chuyen nganh; khong xac minh noi dung nghia.
CREATE TABLE sense_domain (
    sense_id BIGINT UNSIGNED NOT NULL,
    domain_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (sense_id, domain_id),
    KEY idx_sense_domain_domain (domain_id, sense_id),
    CONSTRAINT fk_sense_domain_sense FOREIGN KEY (sense_id)
        REFERENCES vocabulary_sense(id) ON DELETE CASCADE,
    CONSTRAINT fk_sense_domain_domain FOREIGN KEY (domain_id)
        REFERENCES dictionary_domain(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Nguon du lieu tu dien/Kanji. Them nguon moi bang du lieu, khong tao bang rieng.
CREATE TABLE dictionary_source (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code         VARCHAR(50) NOT NULL,
    name         VARCHAR(255) NOT NULL,
    source_url   VARCHAR(1000) NULL,
    license_name VARCHAR(255) NULL,
    license_url  VARCHAR(1000) NULL,
    is_active    TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dictionary_source_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Audit mot lan chay importer. Counter tinh theo source record, khong theo row SQL.
CREATE TABLE dictionary_import_batch (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_id        BIGINT UNSIGNED NOT NULL,
    source_version   VARCHAR(255) NULL,
    source_checksum  CHAR(64) NOT NULL,
    status           VARCHAR(30) NOT NULL,
    started_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at     DATETIME(6) NULL,
    total_records    BIGINT UNSIGNED NOT NULL DEFAULT 0,
    inserted_records BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_records  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    skipped_records  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    failed_records   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    error_message    VARCHAR(2000) NULL,
    PRIMARY KEY (id),
    KEY idx_dictionary_import_identity
        (source_id, source_version, source_checksum, status),
    CONSTRAINT fk_dictionary_import_source FOREIGN KEY (source_id)
        REFERENCES dictionary_source(id),
    CONSTRAINT chk_dictionary_import_status CHECK
        (status IN ('RUNNING','COMPLETED','COMPLETED_WITH_ERRORS','FAILED','SKIPPED')),
    CONSTRAINT chk_dictionary_import_counters CHECK
        (total_records >= 0 AND inserted_records >= 0 AND updated_records >= 0
         AND skipped_records >= 0 AND failed_records >= 0),
    CONSTRAINT chk_dictionary_import_completion CHECK
        (completed_at IS NULL OR completed_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ID on dinh cua vocabulary tai tung nguon; JMdict dung ent_seq.
CREATE TABLE vocabulary_external_reference (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    vocabulary_id     BIGINT UNSIGNED NOT NULL,
    source_id         BIGINT UNSIGNED NOT NULL,
    external_entry_id VARCHAR(255) NOT NULL,
    imported_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                      ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_vocabulary_external_source (source_id, external_entry_id),
    KEY idx_vocabulary_external_vocabulary (vocabulary_id),
    CONSTRAINT fk_vocabulary_external_vocabulary FOREIGN KEY (vocabulary_id)
        REFERENCES vocabulary_entry(id) ON DELETE CASCADE,
    CONSTRAINT fk_vocabulary_external_source FOREIGN KEY (source_id)
        REFERENCES dictionary_source(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO dictionary_source
    (code, name, source_url, license_name, license_url, is_active)
VALUES
    ('JMDICT', 'JMdict', 'https://www.edrdg.org/wiki/JMdict-EDICT_Dictionary_Project.html',
     'Creative Commons Attribution-ShareAlike 4.0',
     'https://www.edrdg.org/edrdg/licence.html', 1),
    ('KANJIDIC2', 'KANJIDIC2', 'https://www.edrdg.org/wiki/KANJIDIC_Project.html',
     'Creative Commons Attribution-ShareAlike 4.0',
     'https://www.edrdg.org/edrdg/licence.html', 1),
    ('KANJIVG', 'KanjiVG', 'https://kanjivg.tagaini.net/',
     'Creative Commons Attribution-ShareAlike 3.0',
     'https://creativecommons.org/licenses/by-sa/3.0/', 1);

-- Lich su tra cuu dung chung cho moi truy van tu dien.
-- Khi user chon Kanji/tu trong hop nhan dien, frontend xoa net ve, dua gia tri
-- da chon len thanh tim kiem va chay luong tra tu dien; bang nay chi ghi truy
-- van do mot lan nhu mot tim kiem binh thuong.
-- Khong them HANDWRITING, confidence score, selected_kanji_id hay bang lich su
-- nhan dien rieng vi he thong khong co nghiep vu phan tich/luu lich su net ve.
CREATE TABLE dictionary_search_history (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id                BIGINT UNSIGNED NULL,
    query_text             VARCHAR(255) NOT NULL,
    normalized_query       VARCHAR(255) NOT NULL,
    search_mode            VARCHAR(20) NOT NULL DEFAULT 'AUTO',
    selected_vocabulary_id BIGINT UNSIGNED NULL,
    result_count           INT UNSIGNED NOT NULL DEFAULT 0,
    searched_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_dictionary_history_user_time (user_id, searched_at),
    KEY idx_dictionary_history_query (normalized_query),
    CONSTRAINT fk_dictionary_history_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_dictionary_history_vocabulary FOREIGN KEY (selected_vocabulary_id)
        REFERENCES vocabulary_entry(id) ON DELETE SET NULL,
    CONSTRAINT chk_dictionary_search_mode CHECK
        (search_mode IN ('AUTO','KANJI','HIRAGANA','KATAKANA','ROMAJI','VIETNAMESE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tu yeu thich cua nguoi dung; chi luu lien ket, khong sao chep noi dung tu.
CREATE TABLE dictionary_favorite (
    user_id       BIGINT UNSIGNED NOT NULL,
    vocabulary_id BIGINT UNSIGNED NOT NULL,
    note          VARCHAR(1000) NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, vocabulary_id),
    KEY idx_dictionary_favorite_vocabulary (vocabulary_id),
    CONSTRAINT fk_dictionary_favorite_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_dictionary_favorite_vocabulary FOREIGN KEY (vocabulary_id)
        REFERENCES vocabulary_entry(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Dau sach/giao trinh; Amon cung la mot textbook trong cay chung.
CREATE TABLE textbook (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    title       VARCHAR(255) NOT NULL,
    description TEXT NULL,
    jlpt_level  VARCHAR(10) NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version     INT UNSIGNED NOT NULL DEFAULT 1,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_textbook_status (status, updated_at),
    CONSTRAINT chk_textbook_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    CONSTRAINT chk_textbook_jlpt
        CHECK (jlpt_level IS NULL OR jlpt_level IN ('N5','N4','N3','N2','N1'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Chuong/phan cua textbook; thay rule_category Amon.
CREATE TABLE unit (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    textbook_id BIGINT UNSIGNED NOT NULL,
    title       VARCHAR(255) NOT NULL,
    description TEXT NULL,
    order_index INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_unit_order (textbook_id, order_index),
    CONSTRAINT fk_unit_textbook FOREIGN KEY (textbook_id)
        REFERENCES textbook(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Lesson chung cho curriculum va Amon/Am On/Kun.
CREATE TABLE lesson (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    unit_id           BIGINT UNSIGNED NOT NULL,
    knowledge_tag_id  BIGINT UNSIGNED NOT NULL,  -- Diem kien thuc chinh cho weakness/recommendation.
    title             VARCHAR(255) NOT NULL,
    description       TEXT NULL,
    order_index       INT UNSIGNED NOT NULL DEFAULT 0,
    estimated_minutes INT UNSIGNED NULL,
    lesson_type       VARCHAR(20) NOT NULL,       -- CURRICULUM/AMON/REVIEW.
    skill             VARCHAR(20) NOT NULL,       -- Ky nang/noi dung chinh.
    situation_tag     VARCHAR(50) NULL,           -- TRAVEL/WORK/FOUNDATION cho goal-based path.
    is_review         TINYINT(1) NOT NULL DEFAULT 0,
    status            VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                      ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_lesson_unit_order (unit_id, order_index),
    KEY idx_lesson_status_skill (status, skill),
    KEY idx_lesson_knowledge_tag (knowledge_tag_id),
    KEY idx_lesson_situation_tag (situation_tag, status),
    CONSTRAINT fk_lesson_unit FOREIGN KEY (unit_id)
        REFERENCES unit(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_knowledge_tag FOREIGN KEY (knowledge_tag_id)
        REFERENCES tag(id),
    CONSTRAINT chk_lesson_type CHECK (lesson_type IN ('CURRICULUM','AMON','REVIEW')),
    CONSTRAINT chk_lesson_skill
        CHECK (skill IN ('VOCABULARY','GRAMMAR','READING','LISTENING','SPEAKING','ONYOMI','KUNYOMI')),
    CONSTRAINT chk_lesson_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    CONSTRAINT chk_lesson_situation_tag
        CHECK (situation_tag IS NULL OR situation_tag IN ('TRAVEL','WORK','FOUNDATION')),
    CONSTRAINT chk_lesson_minutes CHECK (estimated_minutes IS NULL OR estimated_minutes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Bridge lesson-tag cho cac tag phu; knowledge_tag_id va situation_tag van la khoa/cot chinh.
CREATE TABLE lesson_tag (
    lesson_id BIGINT UNSIGNED NOT NULL,
    tag_id    BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (lesson_id, tag_id),
    KEY idx_lesson_tag_tag (tag_id),
    CONSTRAINT fk_lesson_tag_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_tag_tag FOREIGN KEY (tag_id)
        REFERENCES tag(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Phan noi dung trong lesson.
CREATE TABLE lesson_section (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lesson_id    BIGINT UNSIGNED NOT NULL,
    title        VARCHAR(255) NOT NULL,
    section_type VARCHAR(20) NOT NULL,       -- EXPLANATION/EXAMPLE/EXERCISE.
    order_index  INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_section_order (lesson_id, order_index),
    CONSTRAINT fk_section_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT chk_section_type
        CHECK (section_type IN ('EXPLANATION','EXAMPLE','EXERCISE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Noi dung cu the cua mot section.
CREATE TABLE content_item (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    section_id      BIGINT UNSIGNED NOT NULL,
    content_type    VARCHAR(20) NOT NULL,    -- READING/GRAMMAR/LISTENING.
    title           VARCHAR(255) NOT NULL,
    content         MEDIUMTEXT NULL,
    translated_text MEDIUMTEXT NULL,         -- Ban dich cua noi dung co dinh.
    order_index     INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_item_order (section_id, order_index),
    CONSTRAINT fk_content_item_section FOREIGN KEY (section_id)
        REFERENCES lesson_section(id) ON DELETE CASCADE,
    CONSTRAINT chk_content_item_type
        CHECK (content_type IN ('READING','GRAMMAR','LISTENING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Mau ngu phap co cau truc trong lesson.
CREATE TABLE grammar_point (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lesson_id   BIGINT UNSIGNED NOT NULL,
    tag_id      BIGINT UNSIGNED NULL,        -- Tag KNOWLEDGE chi tiet neu co.
    pattern     VARCHAR(500) NOT NULL,
    meaning     TEXT NOT NULL,
    example     TEXT NULL,
    note        TEXT NULL,
    order_index INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_grammar_order (lesson_id, order_index),
    KEY idx_grammar_tag (tag_id),
    CONSTRAINT fk_grammar_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT fk_grammar_tag FOREIGN KEY (tag_id)
        REFERENCES tag(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Bridge noi dung lesson voi tu dien tu vung.
CREATE TABLE content_vocabulary (
    content_item_id BIGINT UNSIGNED NOT NULL,
    vocabulary_id   BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (content_item_id, vocabulary_id),
    KEY idx_content_vocabulary_vocab (vocabulary_id),
    CONSTRAINT fk_content_vocab_content FOREIGN KEY (content_item_id)
        REFERENCES content_item(id) ON DELETE CASCADE,
    CONSTRAINT fk_content_vocab_vocabulary FOREIGN KEY (vocabulary_id)
        REFERENCES vocabulary_entry(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- #############################################################################
-- 4) AMON / AM ON-KUN VA ASSESSMENT DUNG CHUNG
-- #############################################################################

-- Quy tac chuyen am; chi ton tai voi lesson Amon/Onyomi/Kunyomi.
CREATE TABLE conversion_rule (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lesson_id         BIGINT UNSIGNED NOT NULL,
    source_sound      VARCHAR(50) NULL,
    target_sound      VARCHAR(50) NULL,
    title             VARCHAR(200) NULL,
    description       TEXT NULL,
    example_breakdown TEXT NULL,
    tip               TEXT NULL,
    note              TEXT NULL,
    display_order     INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversion_rule_order (lesson_id, display_order),
    CONSTRAINT fk_conversion_rule_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Cac buoc giai thich mot conversion rule.
CREATE TABLE rule_pattern (
    id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rule_id BIGINT UNSIGNED NOT NULL,
    step    SMALLINT UNSIGNED NOT NULL,
    title   VARCHAR(100) NULL,
    content VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_pattern_step (rule_id, step),
    CONSTRAINT fk_rule_pattern_rule FOREIGN KEY (rule_id)
        REFERENCES conversion_rule(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Vi du su pham cho rule, neo vao chu Kanji dang minh hoa.
CREATE TABLE rule_example (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rule_id            BIGINT UNSIGNED NOT NULL,
    kanji_id           BIGINT UNSIGNED NOT NULL, -- Chu chinh ap dung rule.
    example_expression VARCHAR(255) NOT NULL,    -- Tu/cum tu dung lam vi du.
    example_reading    VARCHAR(255) NULL,        -- Cach doc cua vi du trong ngu canh.
    breakdown          TEXT NULL,
    display_order      INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_example (rule_id, kanji_id, example_expression),
    KEY idx_rule_example_kanji (kanji_id),
    CONSTRAINT fk_rule_example_rule FOREIGN KEY (rule_id)
        REFERENCES conversion_rule(id) ON DELETE CASCADE,
    CONSTRAINT fk_rule_example_kanji FOREIGN KEY (kanji_id)
        REFERENCES kanji(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Dau bai/bo cau hoi dung chung. Cau hoi khong nam truc tiep trong bang nay;
-- assessment_quiz_question la bridge de mot cau hoi co the duoc tai su dung.
CREATE TABLE assessment_quiz (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lesson_id   BIGINT UNSIGNED NULL,
    unit_id     BIGINT UNSIGNED NULL,
    title       VARCHAR(255) NOT NULL,
    quiz_type   VARCHAR(30) NOT NULL,        -- LESSON/RULE_PRACTICE/UNIT_FINAL/JLPT_SECTION.
    status      VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_assessment_quiz_lesson (lesson_id, status),
    KEY idx_assessment_quiz_unit (unit_id, status),
    CONSTRAINT fk_assessment_quiz_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_quiz_unit FOREIGN KEY (unit_id)
        REFERENCES unit(id) ON DELETE CASCADE,
    CONSTRAINT chk_assessment_quiz_owner CHECK (
        (quiz_type IN ('LESSON','RULE_PRACTICE')
            AND lesson_id IS NOT NULL AND unit_id IS NULL)
        OR (quiz_type = 'UNIT_FINAL'
            AND lesson_id IS NULL AND unit_id IS NOT NULL)
        OR (quiz_type = 'JLPT_SECTION'
            AND lesson_id IS NULL AND unit_id IS NULL)
    ),
    CONSTRAINT chk_assessment_quiz_type
        CHECK (quiz_type IN ('LESSON','RULE_PRACTICE','UNIT_FINAL','JLPT_SECTION')),
    CONSTRAINT chk_assessment_quiz_status
        CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ngan hang cau hoi dung chung cho Amon, JLPT mock test va Smart Test.
-- skill/jlpt_level/difficulty la phan loai noi tai cua cau hoi; diem va thu tu
-- phu thuoc tung bai nen nam trong assessment_quiz_question.
CREATE TABLE assessment_question (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversion_rule_id  BIGINT UNSIGNED NULL,     -- Chi dung khi cau hoi gan rule Amon.
    knowledge_tag_id    BIGINT UNSIGNED NOT NULL, -- Knowledge point chinh de gom weakness.
    skill               VARCHAR(20) NOT NULL DEFAULT 'KANJI',
    jlpt_level          VARCHAR(10) NOT NULL DEFAULT 'N5',
    difficulty          VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    question_type       VARCHAR(30) NOT NULL DEFAULT 'MULTIPLE_CHOICE',
    title               VARCHAR(255) NULL,
    passage_html        MEDIUMTEXT NULL,
    audio_url           VARCHAR(500) NULL,
    image_url           VARCHAR(500) NULL,
    question_text       TEXT NOT NULL,
    answer_text         TEXT NULL,                -- Dap an chuan cho FILL_BLANK.
    option_1            VARCHAR(255) NULL,        -- Lua chon 1 cho MULTIPLE_CHOICE.
    option_2            VARCHAR(255) NULL,        -- Lua chon 2 cho MULTIPLE_CHOICE.
    option_3            VARCHAR(255) NULL,        -- Lua chon 3 cho MULTIPLE_CHOICE.
    option_4            VARCHAR(255) NULL,        -- Lua chon 4 cho MULTIPLE_CHOICE.
    correct_option      INT NULL,                 -- Chi so dap an dung tu 1 den 4.
    explanation         TEXT NULL,
    context_json        JSON NULL,                -- Metadata hiem/khong dung de loc.
    -- Legacy seed compatibility; runtime code uses assessment_quiz_question.
    quiz_id             BIGINT UNSIGNED NULL,
    order_index         INT UNSIGNED NULL,
    is_active           TINYINT(1) NOT NULL DEFAULT 1,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_assessment_question_adaptive
        (skill, jlpt_level, difficulty, is_active),
    KEY idx_assessment_question_rule (conversion_rule_id),
    KEY idx_assessment_question_knowledge (knowledge_tag_id),
    CONSTRAINT fk_assessment_question_rule FOREIGN KEY (conversion_rule_id)
        REFERENCES conversion_rule(id) ON DELETE SET NULL,
    CONSTRAINT fk_assessment_question_knowledge FOREIGN KEY (knowledge_tag_id)
        REFERENCES tag(id),
    CONSTRAINT fk_assessment_question_legacy_quiz FOREIGN KEY (quiz_id)
        REFERENCES assessment_quiz(id) ON DELETE SET NULL,
    CONSTRAINT chk_assessment_question_skill
        CHECK (skill IN ('VOCABULARY','GRAMMAR','READING','LISTENING','KANJI')),
    CONSTRAINT chk_assessment_question_jlpt
        CHECK (jlpt_level IN ('N5','N4','N3','N2','N1')),
    CONSTRAINT chk_assessment_question_difficulty
        CHECK (difficulty IN ('EASY','MEDIUM','HARD')),
    CONSTRAINT chk_assessment_question_type
        CHECK (question_type IN ('MULTIPLE_CHOICE','FILL_BLANK','MATCHING')),
    CONSTRAINT chk_assessment_question_correct_option
        CHECK (correct_option IS NULL OR correct_option BETWEEN 1 AND 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Compatibility table for older seed files. New code stores options inline.
CREATE TABLE assessment_question_option (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    question_id BIGINT UNSIGNED NOT NULL,
    content     VARCHAR(255) NOT NULL,
    is_correct  TINYINT(1) NOT NULL DEFAULT 0,
    order_index INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_assessment_option_order (question_id, order_index),
    CONSTRAINT fk_assessment_option_question FOREIGN KEY (question_id)
        REFERENCES assessment_question(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Bridge question-tag cho tag phu, gom ca SITUATION. knowledge_tag_id tren
-- assessment_question van la knowledge point chinh duy nhat cho code hien tai.
CREATE TABLE question_tag (
    question_id BIGINT UNSIGNED NOT NULL,
    tag_id      BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (question_id, tag_id),
    KEY idx_question_tag_tag (tag_id, question_id),
    CONSTRAINT fk_question_tag_question FOREIGN KEY (question_id)
        REFERENCES assessment_question(id) ON DELETE CASCADE,
    CONSTRAINT fk_question_tag_tag FOREIGN KEY (tag_id)
        REFERENCES tag(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Cap dung cho cau MATCHING.
CREATE TABLE assessment_question_pair (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    question_id BIGINT UNSIGNED NOT NULL,
    left_text   VARCHAR(500) NOT NULL,
    right_text  VARCHAR(500) NOT NULL,
    order_index INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_assessment_pair_order (question_id, order_index),
    CONSTRAINT fk_assessment_pair_question FOREIGN KEY (question_id)
        REFERENCES assessment_question(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Gan cau hoi vao mot quiz/bo de co the tai su dung cung cau hoi o nhieu noi.
-- mondai_number, raw_score va order_index phu thuoc lan gan, khong phu thuoc cau hoi.
CREATE TABLE assessment_quiz_question (
    quiz_id       BIGINT UNSIGNED NOT NULL,
    question_id   BIGINT UNSIGNED NOT NULL,
    order_index   INT UNSIGNED NOT NULL DEFAULT 0,
    mondai_number INT UNSIGNED NULL,
    raw_score     DECIMAL(6,2) NOT NULL DEFAULT 1.00,
    PRIMARY KEY (quiz_id, question_id),
    UNIQUE KEY uk_assessment_quiz_question_order (quiz_id, order_index),
    KEY idx_assessment_quiz_question_lookup (question_id, quiz_id),
    CONSTRAINT fk_assessment_quiz_question_quiz FOREIGN KEY (quiz_id)
        REFERENCES assessment_quiz(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_quiz_question_question FOREIGN KEY (question_id)
        REFERENCES assessment_question(id),
    CONSTRAINT chk_assessment_quiz_question_mondai
        CHECK (mondai_number IS NULL OR mondai_number > 0),
    CONSTRAINT chk_assessment_quiz_question_score CHECK (raw_score > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER $$
CREATE TRIGGER trg_assessment_question_legacy_bridge
AFTER INSERT ON assessment_question
FOR EACH ROW
BEGIN
    IF NEW.quiz_id IS NOT NULL THEN
        INSERT IGNORE INTO assessment_quiz_question (quiz_id, question_id, order_index)
        VALUES (NEW.quiz_id, NEW.id, COALESCE(NEW.order_index, 0));
    END IF;
END$$

CREATE TRIGGER trg_assessment_question_option_inline
AFTER INSERT ON assessment_question_option
FOR EACH ROW
BEGIN
    IF NEW.order_index = 1 THEN
        UPDATE assessment_question SET option_1 = NEW.content,
            correct_option = IF(NEW.is_correct = 1, 1, correct_option)
            WHERE id = NEW.question_id;
    ELSEIF NEW.order_index = 2 THEN
        UPDATE assessment_question SET option_2 = NEW.content,
            correct_option = IF(NEW.is_correct = 1, 2, correct_option)
            WHERE id = NEW.question_id;
    ELSEIF NEW.order_index = 3 THEN
        UPDATE assessment_question SET option_3 = NEW.content,
            correct_option = IF(NEW.is_correct = 1, 3, correct_option)
            WHERE id = NEW.question_id;
    ELSEIF NEW.order_index = 4 THEN
        UPDATE assessment_question SET option_4 = NEW.content,
            correct_option = IF(NEW.is_correct = 1, 4, correct_option)
            WHERE id = NEW.question_id;
    END IF;
END$$
DELIMITER ;

-- De thi JLPT; total_duration va max_score duoc tinh tu cac section de tranh lap.
CREATE TABLE jlpt_exam (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    title         VARCHAR(255) NOT NULL,
    jlpt_level    VARCHAR(10) NOT NULL,
    passing_score DECIMAL(6,2) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                  ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_jlpt_exam_level_status (jlpt_level, status),
    CONSTRAINT chk_jlpt_exam_level
        CHECK (jlpt_level IN ('N5','N4','N3','N2','N1')),
    CONSTRAINT chk_jlpt_exam_passing_score CHECK (passing_score >= 0),
    CONSTRAINT chk_jlpt_exam_status
        CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Moi section dung mot assessment_quiz loai JLPT_SECTION lam bo cau hoi.
-- Ten section lay tu assessment_quiz.title, khong luu lap o bang nay.
CREATE TABLE jlpt_exam_section (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exam_id           BIGINT UNSIGNED NOT NULL,
    quiz_id           BIGINT UNSIGNED NOT NULL,
    section_type      VARCHAR(30) NOT NULL,
    duration_minutes  INT UNSIGNED NOT NULL,
    max_score         DECIMAL(6,2) NOT NULL,
    min_passing_score DECIMAL(6,2) NOT NULL,
    order_index       INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jlpt_section_order (exam_id, order_index),
    UNIQUE KEY uk_jlpt_section_quiz (quiz_id),
    KEY idx_jlpt_section_exam_type (exam_id, section_type),
    CONSTRAINT fk_jlpt_section_exam FOREIGN KEY (exam_id)
        REFERENCES jlpt_exam(id),
    CONSTRAINT fk_jlpt_section_quiz FOREIGN KEY (quiz_id)
        REFERENCES assessment_quiz(id),
    CONSTRAINT chk_jlpt_section_type CHECK (
        section_type IN (
            'VOCAB_GRAMMAR','READING','LISTENING','LANGUAGE_KNOWLEDGE'
        )
    ),
    CONSTRAINT chk_jlpt_section_duration CHECK (duration_minutes > 0),
    CONSTRAINT chk_jlpt_section_score CHECK (
        max_score > 0
        AND min_passing_score >= 0
        AND min_passing_score <= max_score
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Supertype cho moi lan lam bai. Chi luu thuoc tinh chung; quiz/JLPT/Smart
-- nam trong bang subtype de khong tao nhieu cot nullable va giu 3NF.
CREATE TABLE assessment_attempt (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id      BIGINT UNSIGNED NOT NULL,
    attempt_type VARCHAR(20) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    started_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    submitted_at DATETIME(6) NULL,
    -- Legacy seed compatibility; runtime code uses assessment_quiz_attempt.
    quiz_id      BIGINT UNSIGNED NULL,
    score_percent DECIMAL(5,2) NULL,
    PRIMARY KEY (id),
    KEY idx_assessment_attempt_user (user_id, started_at),
    KEY idx_assessment_attempt_type_status (attempt_type, status, started_at),
    CONSTRAINT fk_assessment_attempt_user FOREIGN KEY (user_id)
        REFERENCES users(id),
    CONSTRAINT fk_assessment_attempt_legacy_quiz FOREIGN KEY (quiz_id)
        REFERENCES assessment_quiz(id) ON DELETE SET NULL,
    CONSTRAINT chk_assessment_attempt_type
        CHECK (attempt_type IN ('QUIZ','JLPT','SMART_TEST')),
    CONSTRAINT chk_assessment_attempt_status
        CHECK (status IN ('IN_PROGRESS','SUBMITTED','ABANDONED')),
    CONSTRAINT chk_assessment_attempt_time
        CHECK (submitted_at IS NULL OR submitted_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Thuoc tinh rieng cua lan lam quiz Amon/lesson/unit.
CREATE TABLE assessment_quiz_attempt (
    attempt_id    BIGINT UNSIGNED NOT NULL,
    quiz_id       BIGINT UNSIGNED NOT NULL,
    score_percent DECIMAL(5,2) NULL,
    PRIMARY KEY (attempt_id),
    KEY idx_assessment_quiz_attempt_quiz (quiz_id, attempt_id),
    CONSTRAINT fk_assessment_quiz_attempt_attempt FOREIGN KEY (attempt_id)
        REFERENCES assessment_attempt(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_quiz_attempt_quiz FOREIGN KEY (quiz_id)
        REFERENCES assessment_quiz(id),
    CONSTRAINT chk_assessment_quiz_attempt_score
        CHECK (score_percent IS NULL OR score_percent BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER $$
CREATE TRIGGER trg_assessment_attempt_legacy_quiz
AFTER INSERT ON assessment_attempt
FOR EACH ROW
BEGIN
    IF NEW.quiz_id IS NOT NULL THEN
        INSERT IGNORE INTO assessment_quiz_attempt (attempt_id, quiz_id, score_percent)
        VALUES (NEW.id, NEW.quiz_id, NEW.score_percent);
    END IF;
END$$
DELIMITER ;

-- Thuoc tinh rieng cua lan thi JLPT. Tong diem va pass/fail duoc tinh qua view.
CREATE TABLE jlpt_exam_attempt (
    attempt_id BIGINT UNSIGNED NOT NULL,
    exam_id    BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (attempt_id),
    KEY idx_jlpt_exam_attempt_exam (exam_id, attempt_id),
    CONSTRAINT fk_jlpt_exam_attempt_attempt FOREIGN KEY (attempt_id)
        REFERENCES assessment_attempt(id) ON DELETE CASCADE,
    CONSTRAINT fk_jlpt_exam_attempt_exam FOREIGN KEY (exam_id)
        REFERENCES jlpt_exam(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Diem tung section. is_passed_section duoc tinh tu score va min_passing_score.
CREATE TABLE jlpt_section_result (
    attempt_id BIGINT UNSIGNED NOT NULL,
    section_id BIGINT UNSIGNED NOT NULL,
    score      DECIMAL(6,2) NOT NULL DEFAULT 0.00,
    PRIMARY KEY (attempt_id, section_id),
    KEY idx_jlpt_section_result_section (section_id, attempt_id),
    CONSTRAINT fk_jlpt_section_result_attempt FOREIGN KEY (attempt_id)
        REFERENCES jlpt_exam_attempt(attempt_id) ON DELETE CASCADE,
    CONSTRAINT fk_jlpt_section_result_section FOREIGN KEY (section_id)
        REFERENCES jlpt_exam_section(id),
    CONSTRAINT chk_jlpt_section_result_score CHECK (score >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Profile va ket qua cap cao cua Smart Test; user/status/thoi gian dung tu attempt cha.
CREATE TABLE smart_test_session (
    attempt_id              BIGINT UNSIGNED NOT NULL,
    goal                    VARCHAR(30) NOT NULL,
    target_level            VARCHAR(10) NULL,
    self_reported_level     VARCHAR(20) NOT NULL,
    study_duration_months   INT UNSIGNED NULL,
    daily_study_minutes     INT UNSIGNED NULL,
    estimated_overall_level VARCHAR(30) NULL,
    overall_mastery_score   DECIMAL(5,2) NULL,
    readiness_level         VARCHAR(20) NULL,
    PRIMARY KEY (attempt_id),
    CONSTRAINT fk_smart_test_session_attempt FOREIGN KEY (attempt_id)
        REFERENCES assessment_attempt(id) ON DELETE CASCADE,
    CONSTRAINT chk_smart_test_target_level CHECK (
        target_level IS NULL OR target_level IN ('N5','N4','N3','N2','N1')
    ),
    CONSTRAINT chk_smart_test_readiness CHECK (
        readiness_level IS NULL OR readiness_level IN ('LOW','MEDIUM','HIGH')
    ),
    CONSTRAINT chk_smart_test_mastery CHECK (
        overall_mastery_score IS NULL OR overall_mastery_score BETWEEN 0 AND 100
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Snapshot cac cau da phat cho Smart Test, ke ca cau user chua tra loi.
-- skill va tested_level lay tu assessment_question, khong luu lap tai day.
CREATE TABLE smart_test_session_question (
    attempt_id  BIGINT UNSIGNED NOT NULL,
    question_id BIGINT UNSIGNED NOT NULL,
    batch_number INT UNSIGNED NOT NULL,
    order_index INT UNSIGNED NOT NULL,
    presented_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (attempt_id, question_id),
    UNIQUE KEY uk_smart_test_question_order (attempt_id, order_index),
    KEY idx_smart_test_question_batch (attempt_id, batch_number, order_index),
    KEY idx_smart_test_question_lookup (question_id, attempt_id),
    CONSTRAINT fk_smart_test_question_session FOREIGN KEY (attempt_id)
        REFERENCES smart_test_session(attempt_id) ON DELETE CASCADE,
    CONSTRAINT fk_smart_test_question_question FOREIGN KEY (question_id)
        REFERENCES assessment_question(id),
    CONSTRAINT chk_smart_test_question_batch CHECK (batch_number > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ket qua theo ky nang cua Smart Test. So cau va so cau dung tinh tu answer.
CREATE TABLE smart_test_skill_result (
    attempt_id      BIGINT UNSIGNED NOT NULL,
    skill           VARCHAR(20) NOT NULL,
    estimated_level VARCHAR(30) NOT NULL,
    mastery_score   DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    confidence      VARCHAR(20) NOT NULL DEFAULT 'HIGH',
    PRIMARY KEY (attempt_id, skill),
    CONSTRAINT fk_smart_test_skill_session FOREIGN KEY (attempt_id)
        REFERENCES smart_test_session(attempt_id) ON DELETE CASCADE,
    CONSTRAINT chk_smart_test_skill CHECK (
        skill IN ('VOCABULARY','GRAMMAR','READING','LISTENING','KANJI')
    ),
    CONSTRAINT chk_smart_test_skill_mastery
        CHECK (mastery_score BETWEEN 0 AND 100),
    CONSTRAINT chk_smart_test_skill_confidence
        CHECK (confidence IN ('LOW','MEDIUM','HIGH'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Dap an chung cho ca quiz, JLPT va Smart Test. is_correct la grading snapshot
-- de ket qua cu khong doi khi logic cham diem thay doi.
CREATE TABLE assessment_answer (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    attempt_id      BIGINT UNSIGNED NOT NULL,
    question_id     BIGINT UNSIGNED NOT NULL,
    selected_option INT NULL,                 -- Chi so lua chon 1 den 4 cho MULTIPLE_CHOICE.
    selected_option_id BIGINT UNSIGNED NULL,  -- Legacy seed compatibility.
    answer_text     TEXT NULL,
    answer_json     JSON NULL,                -- Cau tra loi MATCHING co cau truc.
    is_correct      TINYINT(1) NULL,
    answered_at     DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_assessment_answer (attempt_id, question_id),
    KEY idx_assessment_answer_question (question_id, is_correct),
    CONSTRAINT fk_assessment_answer_attempt FOREIGN KEY (attempt_id)
        REFERENCES assessment_attempt(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_answer_question FOREIGN KEY (question_id)
        REFERENCES assessment_question(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER $$
CREATE TRIGGER trg_assessment_answer_legacy_option
BEFORE INSERT ON assessment_answer
FOR EACH ROW
BEGIN
    IF NEW.selected_option IS NULL AND NEW.selected_option_id IS NOT NULL THEN
        SET NEW.selected_option = (
            SELECT order_index FROM assessment_question_option WHERE id = NEW.selected_option_id
        );
    END IF;
END$$
DELIMITER ;

-- Cac aggregate khong luu lap trong bang; view cung cap shape de code doc nhanh.
CREATE VIEW v_jlpt_exam_summary AS
SELECT
    e.id AS exam_id,
    COALESCE(SUM(s.duration_minutes), 0) AS total_duration,
    COALESCE(SUM(s.max_score), 0.00) AS max_score
FROM jlpt_exam e
LEFT JOIN jlpt_exam_section s ON s.exam_id = e.id
GROUP BY e.id;

CREATE VIEW v_jlpt_section_result AS
SELECT
    r.attempt_id,
    r.section_id,
    r.score,
    CASE WHEN r.score >= s.min_passing_score THEN 1 ELSE 0 END AS is_passed_section
FROM jlpt_section_result r
JOIN jlpt_exam_section s ON s.id = r.section_id;

CREATE VIEW v_jlpt_exam_attempt_summary AS
SELECT
    a.attempt_id,
    a.exam_id,
    COALESCE(SUM(r.score), 0.00) AS total_score,
    CASE
        WHEN COUNT(r.section_id) = COUNT(s.id)
         AND COALESCE(SUM(r.score), 0.00) >= e.passing_score
         AND MIN(CASE
                WHEN r.score IS NOT NULL AND r.score >= s.min_passing_score THEN 1
                ELSE 0
             END) = 1
        THEN 1 ELSE 0
    END AS is_passed
FROM jlpt_exam_attempt a
JOIN jlpt_exam e ON e.id = a.exam_id
JOIN jlpt_exam_section s ON s.exam_id = e.id
LEFT JOIN jlpt_section_result r
    ON r.attempt_id = a.attempt_id AND r.section_id = s.id
GROUP BY a.attempt_id, a.exam_id, e.passing_score;

CREATE VIEW v_smart_test_session_summary AS
SELECT
    s.attempt_id,
    COUNT(q.question_id) AS total_questions,
    SUM(CASE WHEN a.is_correct = 1 THEN 1 ELSE 0 END) AS total_correct,
    COUNT(a.id) AS total_answered
FROM smart_test_session s
LEFT JOIN smart_test_session_question q ON q.attempt_id = s.attempt_id
LEFT JOIN assessment_answer a
    ON a.attempt_id = q.attempt_id AND a.question_id = q.question_id
GROUP BY s.attempt_id;

-- #############################################################################
-- 5) LEARNING PATH, PROGRESS, WEAKNESS VA GOI Y
-- #############################################################################

-- Lo trinh hoc dai han co version cua user.
CREATE TABLE user_learning_path (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id      BIGINT UNSIGNED NOT NULL,
    title        VARCHAR(255) NOT NULL,
    goal         VARCHAR(20) NULL,
    target_level VARCHAR(10) NULL,
    current_level VARCHAR(10) NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version      INT UNSIGNED NOT NULL DEFAULT 1,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                 ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_learning_path_user_status (user_id, status, updated_at),
    CONSTRAINT fk_learning_path_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_path_goal
        CHECK (goal IS NULL OR goal IN ('JLPT','TRAVEL','BA','FROM_ZERO')),
    CONSTRAINT chk_learning_path_status CHECK (status IN ('ACTIVE','SUPERSEDED')),
    CONSTRAINT chk_learning_path_target
        CHECK (target_level IS NULL OR target_level IN ('N5','N4','N3','N2','N1'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Lesson trong learning path; cho phep cung lesson xuat hien lai de review.
CREATE TABLE user_learning_path_item (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    learning_path_id BIGINT UNSIGNED NOT NULL,
    lesson_id        BIGINT UNSIGNED NOT NULL,
    order_index      INT UNSIGNED NOT NULL,
    reason           TEXT NULL,
    kind             VARCHAR(20) NOT NULL,       -- CURRICULUM hoac REVIEW.
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_path_item_order (learning_path_id, order_index),
    KEY idx_learning_path_item_lesson (lesson_id),
    CONSTRAINT fk_learning_path_item_path FOREIGN KEY (learning_path_id)
        REFERENCES user_learning_path(id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_path_item_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_path_item_kind CHECK (kind IN ('CURRICULUM','REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Nguon tien do lesson duy nhat, giu mo hinh more.txt.
-- Khong migrate du lieu user_lesson_progress Amon cu.
CREATE TABLE lesson_progress (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id      BIGINT UNSIGNED NOT NULL,      -- Thong nhat ten FK user trong toan schema.
    lesson_id    BIGINT UNSIGNED NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    started_at   DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_lesson_progress (user_id, lesson_id),
    KEY idx_lesson_progress_lesson (lesson_id, status),
    CONSTRAINT fk_lesson_progress_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_progress_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT chk_lesson_progress_status
        CHECK (status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED')),
    CONSTRAINT chk_lesson_progress_time CHECK (
        completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Diem yeu tong hop theo tag KNOWLEDGE; answer la bang chung chi tiet.
CREATE TABLE user_knowledge_weakness (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id       BIGINT UNSIGNED NOT NULL,
    tag_id        BIGINT UNSIGNED NOT NULL,
    skill         VARCHAR(20) NULL,            -- Fallback khi chua tim duoc lesson khop tag.
    score_percent DECIMAL(5,2) NULL,
    severity      VARCHAR(30) NOT NULL,
    source        VARCHAR(255) NULL,
    detected_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                  ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_knowledge_weakness (user_id, tag_id),
    KEY idx_weakness_tag_severity (tag_id, severity),
    CONSTRAINT fk_weakness_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_weakness_tag FOREIGN KEY (tag_id)
        REFERENCES tag(id) ON DELETE CASCADE,
    CONSTRAINT chk_weakness_score
        CHECK (score_percent IS NULL OR score_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_weakness_severity
        CHECK (severity IN ('WEAK','NEEDS_REINFORCEMENT')),
    CONSTRAINT chk_weakness_skill CHECK (
        skill IS NULL OR skill IN (
            'VOCABULARY','GRAMMAR','READING','LISTENING','SPEAKING','ONYOMI','KUNYOMI'
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Goi y lesson on tap tu mot weakness.
CREATE TABLE user_lesson_recommendation (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,      -- User nhan recommendation, giu de truy van truc tiep.
    weakness_id BIGINT UNSIGNED NOT NULL,
    lesson_id   BIGINT UNSIGNED NULL,
    reason      TEXT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_recommendation_user_status (user_id, status, created_at),
    KEY idx_recommendation_weakness_status (weakness_id, status),
    KEY idx_recommendation_lesson (lesson_id),
    CONSTRAINT fk_recommendation_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_recommendation_weakness FOREIGN KEY (weakness_id)
        REFERENCES user_knowledge_weakness(id) ON DELETE CASCADE,
    CONSTRAINT fk_recommendation_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE SET NULL,
    CONSTRAINT chk_recommendation_status
        CHECK (status IN ('PENDING','ACCEPTED','DISMISSED','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- #############################################################################
-- 6) FLASHCARD
-- #############################################################################

-- Thu muc ca nhan de moi user phan loai bo the.
CREATE TABLE flashcard_folder (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_id   BIGINT UNSIGNED NOT NULL,
    name       VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
               ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_flashcard_folder_owner_name (owner_id, name),
    KEY idx_flashcard_folder_owner (owner_id),
    CONSTRAINT fk_flashcard_folder_owner FOREIGN KEY (owner_id)
        REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Bo flashcard do user/admin tao; metadata clone nam ngay tren set.
CREATE TABLE flashcard_set (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_id              BIGINT UNSIGNED NOT NULL,
    folder_id             BIGINT UNSIGNED NULL,
    title                 VARCHAR(100) NOT NULL,
    description           VARCHAR(500) NULL,
    thumbnail_url         VARCHAR(500) NULL,
    visibility            VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    is_draft              TINYINT(1) NOT NULL DEFAULT 0,
    moderation_status     VARCHAR(20) NOT NULL DEFAULT 'NOT_SCANNED',
    moderation_checked_at DATETIME(6) NULL,
    moderation_summary    VARCHAR(500) NULL,
    deleted_at            DATETIME(6) NULL,
    source_set_id         BIGINT UNSIGNED NULL, -- Set goc neu day la ban clone.
    cloned_at             DATETIME(6) NULL,
    last_synced_at        DATETIME(6) NULL,
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                          ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_flashcard_set_owner (owner_id, deleted_at),
    KEY idx_flashcard_set_public (visibility, deleted_at, created_at),
    KEY idx_flashcard_set_folder (folder_id, deleted_at),
    KEY idx_flashcard_set_source (source_set_id),
    CONSTRAINT fk_flashcard_set_owner FOREIGN KEY (owner_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_flashcard_set_folder FOREIGN KEY (folder_id)
        REFERENCES flashcard_folder(id) ON DELETE SET NULL,
    CONSTRAINT fk_flashcard_set_source FOREIGN KEY (source_set_id)
        REFERENCES flashcard_set(id) ON DELETE SET NULL,
    CONSTRAINT chk_flashcard_set_visibility CHECK (visibility IN ('PUBLIC','PRIVATE')),
    CONSTRAINT chk_flashcard_set_draft CHECK (is_draft IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Chan set tu clone chinh no; MySQL khong cho CHECK tren self FK co referential action.
DELIMITER $$
CREATE TRIGGER trg_flashcard_set_no_self_clone_insert
AFTER INSERT ON flashcard_set
FOR EACH ROW
BEGIN
    IF NEW.source_set_id IS NOT NULL AND NEW.source_set_id = NEW.id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A flashcard set cannot clone itself';
    END IF;
END$$

CREATE TRIGGER trg_flashcard_set_no_self_clone_update
BEFORE UPDATE ON flashcard_set
FOR EACH ROW
BEGIN
    IF NEW.source_set_id IS NOT NULL AND NEW.source_set_id = NEW.id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A flashcard set cannot clone itself';
    END IF;
END$$
DELIMITER ;

-- The flashcard tu do: front/back do user quyet dinh; metadata la tuy chon.
CREATE TABLE flashcard_card (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    set_id     BIGINT UNSIGNED NOT NULL,
    front      VARCHAR(500) NULL,
    back       VARCHAR(500) NULL,
    reading    VARCHAR(255) NULL,
    meaning    VARCHAR(500) NULL,
    han_viet   VARCHAR(255) NULL,
    image_url  VARCHAR(500) NULL,
    jlpt_level VARCHAR(10) NULL,
    word_type  VARCHAR(50) NULL,
    example_ja TEXT NULL,
    example_vi TEXT NULL,
    position   INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
               ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_flashcard_card_position (set_id, position),
    CONSTRAINT fk_flashcard_card_set FOREIGN KEY (set_id)
        REFERENCES flashcard_set(id) ON DELETE CASCADE,
    CONSTRAINT chk_flashcard_card_jlpt
        CHECK (jlpt_level IS NULL OR jlpt_level IN ('N5','N4','N3','N2','N1','CUSTOM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Favorite/rating/lan hoc dau theo mot user va set.
CREATE TABLE flashcard_user_set_state (
    user_id            BIGINT UNSIGNED NOT NULL,
    set_id             BIGINT UNSIGNED NOT NULL,
    is_favorite        TINYINT(1) NOT NULL DEFAULT 0,
    rating             TINYINT UNSIGNED NULL,
    first_completed_at DATETIME(6) NULL,
    last_accessed_at   DATETIME(6) NULL,
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                       ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, set_id),
    KEY idx_flashcard_user_set_state_set (set_id, is_favorite, rating),
    CONSTRAINT fk_flashcard_user_set_state_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_flashcard_user_set_state_set FOREIGN KEY (set_id)
        REFERENCES flashcard_set(id) ON DELETE CASCADE,
    CONSTRAINT chk_flashcard_user_set_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tien do spaced repetition cua tung card theo user.
CREATE TABLE flashcard_card_progress (
    user_id          BIGINT UNSIGNED NOT NULL,
    card_id          BIGINT UNSIGNED NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'NEW',
    review_count     INT UNSIGNED NOT NULL DEFAULT 0,
    last_quality     TINYINT UNSIGNED NULL,
    easiness_factor  DECIMAL(4,2) NOT NULL DEFAULT 2.50,
    repetition_count INT UNSIGNED NOT NULL DEFAULT 0,
    interval_days    INT UNSIGNED NOT NULL DEFAULT 0,
    last_reviewed_at DATETIME(6) NULL,
    next_review_at   DATETIME(6) NULL,
    is_starred       TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, card_id),
    KEY idx_flashcard_progress_card (card_id),
    KEY idx_flashcard_progress_due (user_id, next_review_at),
    CONSTRAINT fk_flashcard_progress_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_flashcard_progress_card FOREIGN KEY (card_id)
        REFERENCES flashcard_card(id) ON DELETE CASCADE,
    CONSTRAINT chk_flashcard_progress_status CHECK (status IN ('NEW','LEARNING','MASTERED')),
    CONSTRAINT chk_flashcard_progress_quality CHECK (last_quality IS NULL OR last_quality <= 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Mot phien hoc/quiz flashcard.
CREATE TABLE flashcard_study_session (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id      BIGINT UNSIGNED NOT NULL,
    set_id       BIGINT UNSIGNED NOT NULL,
    session_type VARCHAR(20) NOT NULL,
    creation_request_id VARCHAR(36) NULL,
    creation_fingerprint VARCHAR(64) NULL,
    mode         VARCHAR(30) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    started_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at  DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_flashcard_session_user (user_id, started_at),
    KEY idx_flashcard_session_set (set_id),
    CONSTRAINT fk_flashcard_session_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_flashcard_session_set FOREIGN KEY (set_id)
        REFERENCES flashcard_set(id) ON DELETE CASCADE,
    UNIQUE KEY uk_flashcard_session_creation (user_id, creation_request_id),
    KEY idx_flashcard_session_history (user_id, set_id, session_type, started_at),
    CONSTRAINT chk_flashcard_session_type CHECK (session_type IN ('FLASHCARD','QUIZ','SPEED_RECALL')),
    CONSTRAINT chk_flashcard_session_status
        CHECK (status IN ('IN_PROGRESS','COMPLETED','ABANDONED')),
    CONSTRAINT chk_flashcard_session_time CHECK (finished_at IS NULL OR finished_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Card xuat hien trong session va ket qua tra loi.
CREATE TABLE flashcard_study_session_item (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id      BIGINT UNSIGNED NOT NULL,
    card_id         BIGINT UNSIGNED NOT NULL,
    chosen_card_id  BIGINT UNSIGNED NULL,
    position        INT UNSIGNED NOT NULL,
    is_wrong_review TINYINT(1) NOT NULL DEFAULT 0,
    is_correct      TINYINT(1) NULL,
    answered_at     DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_flashcard_session_item_position (session_id, position),
    KEY idx_flashcard_session_item_card (card_id),
    CONSTRAINT fk_flashcard_session_item_session FOREIGN KEY (session_id)
        REFERENCES flashcard_study_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_flashcard_session_item_card FOREIGN KEY (card_id)
        REFERENCES flashcard_card(id) ON DELETE CASCADE,
    CONSTRAINT fk_flashcard_session_item_chosen FOREIGN KEY (chosen_card_id)
        REFERENCES flashcard_card(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- #############################################################################
-- 7) SHADOWING
-- Speed Recall extensions are declared here after the canonical flashcard tables.
CREATE TABLE flashcard_speed_recall_session (
    session_id BIGINT UNSIGNED NOT NULL,
    state VARCHAR(24) NOT NULL,
    paused_from VARCHAR(24) NULL,
    current_question_index INT NOT NULL DEFAULT 0,
    total_questions INT NOT NULL,
    correct_count INT NOT NULL DEFAULT 0,
    wrong_count INT NOT NULL DEFAULT 0,
    timeout_count INT NOT NULL DEFAULT 0,
    score INT NOT NULL DEFAULT 0,
    streak INT NOT NULL DEFAULT 0,
    best_streak INT NOT NULL DEFAULT 0,
    total_response_ms BIGINT NOT NULL DEFAULT 0,
    rules_version VARCHAR(32) NOT NULL,
    content_version VARCHAR(64) NOT NULL,
    selection_filter VARCHAR(16) NOT NULL,
    auto_submit BOOLEAN NOT NULL DEFAULT FALSE,
    retry_of_session_id BIGINT UNSIGNED NULL,
    feedback_until DATETIME(6) NULL,
    paused_remaining_ms BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    last_activity_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (session_id),
    KEY idx_sr_expiry (state, expires_at),
    CONSTRAINT fk_sr_study FOREIGN KEY (session_id) REFERENCES flashcard_study_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_sr_retry FOREIGN KEY (retry_of_session_id) REFERENCES flashcard_speed_recall_session(session_id) ON DELETE SET NULL,
    CONSTRAINT chk_sr_total CHECK (total_questions >= 1),
    CONSTRAINT chk_sr_counts CHECK (correct_count >= 0 AND wrong_count >= 0 AND timeout_count >= 0
        AND timeout_count <= wrong_count AND correct_count + wrong_count <= total_questions)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE flashcard_speed_recall_answer (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id BIGINT UNSIGNED NOT NULL,
    flashcard_card_id BIGINT UNSIGNED NULL,
    question_index INT NOT NULL,
    question_text VARCHAR(500) NOT NULL,
    expected_answer VARCHAR(500) NOT NULL,
    reading VARCHAR(255) NOT NULL,
    meaning TEXT NULL,
    outcome VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    user_answer VARCHAR(500) NULL,
    normalized_answer VARCHAR(500) NULL,
    response_ms INT NULL,
    score_awarded INT NOT NULL DEFAULT 0,
    time_limit_ms INT NULL,
    streak_before INT NULL,
    issued_at DATETIME(6) NULL,
    deadline_at DATETIME(6) NULL,
    resolved_at DATETIME(6) NULL,
    answer_request_id VARCHAR(36) NULL,
    request_fingerprint VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sr_question_position (session_id, question_index),
    KEY idx_sr_review (session_id, outcome),
    CONSTRAINT fk_sr_answer_session FOREIGN KEY (session_id) REFERENCES flashcard_speed_recall_session(session_id) ON DELETE CASCADE,
    CONSTRAINT fk_sr_answer_card FOREIGN KEY (flashcard_card_id) REFERENCES flashcard_card(id) ON DELETE SET NULL,
    CONSTRAINT chk_sr_answer_outcome CHECK (outcome IN ('PENDING','ACTIVE','CORRECT','WRONG','TIMEOUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- #############################################################################

-- Video/audio shadowing va trang thai xu ly.
CREATE TABLE video (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_id              BIGINT UNSIGNED NULL,
    title                 VARCHAR(255) NOT NULL,
    video_path            TEXT NULL,
    source_url            TEXT NULL,
    thumbnail_url         TEXT NULL,
    duration_seconds      DECIMAL(10,3) NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'READY',
    processing_step       VARCHAR(32) NULL,
    progress_percent      TINYINT UNSIGNED NOT NULL DEFAULT 0,
    error_message         TEXT NULL,
    target_language       VARCHAR(10) NOT NULL DEFAULT 'vi',
    generate_furigana     TINYINT(1) NOT NULL DEFAULT 0,
    visibility            VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    moderation_status     VARCHAR(20) NOT NULL DEFAULT 'NOT_SCANNED',
    moderation_checked_at DATETIME(6) NULL,
    moderation_summary    VARCHAR(500) NULL,
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                          ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_video_owner (owner_id),
    KEY idx_video_public (visibility, status, created_at),
    CONSTRAINT fk_video_owner FOREIGN KEY (owner_id)
        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_video_duration CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    CONSTRAINT chk_video_progress CHECK (progress_percent <= 100),
    CONSTRAINT chk_video_visibility CHECK (visibility IN ('PUBLIC','PRIVATE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Cau subtitle va du lieu AI furigana/dich.
CREATE TABLE subtitle (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    video_id    BIGINT UNSIGNED NOT NULL,
    start_time  DECIMAL(10,3) NOT NULL,
    end_time    DECIMAL(10,3) NOT NULL,
    text        TEXT NOT NULL,
    furigana    TEXT NULL,
    translation TEXT NULL,
    sort_order  INT UNSIGNED NOT NULL,
    ai_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_subtitle_video_order (video_id, sort_order),
    CONSTRAINT fk_subtitle_video FOREIGN KEY (video_id)
        REFERENCES video(id) ON DELETE CASCADE,
    CONSTRAINT chk_subtitle_time CHECK (start_time >= 0 AND end_time >= start_time),
    CONSTRAINT chk_subtitle_ai_status
        CHECK (ai_status IN ('PENDING','PROCESSING','COMPLETED','FAILED','SKIPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Bridge video voi tag loai VIDEO.
CREATE TABLE video_tag (
    video_id BIGINT UNSIGNED NOT NULL,
    tag_id   BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (video_id, tag_id),
    KEY idx_video_tag_tag (tag_id),
    CONSTRAINT fk_video_tag_video FOREIGN KEY (video_id)
        REFERENCES video(id) ON DELETE CASCADE,
    CONSTRAINT fk_video_tag_tag FOREIGN KEY (tag_id)
        REFERENCES tag(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Watch/rating/favorite video theo user.
CREATE TABLE video_user_state (
    user_id          BIGINT UNSIGNED NOT NULL,
    video_id         BIGINT UNSIGNED NOT NULL,
    max_position_sec DECIMAL(10,3) NOT NULL DEFAULT 0,
    counted_as_view  TINYINT(1) NOT NULL DEFAULT 0,
    rating           TINYINT UNSIGNED NULL,
    is_favorite      TINYINT(1) NOT NULL DEFAULT 0,
    last_watched_at  DATETIME(6) NULL,
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                     ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, video_id),
    KEY idx_video_user_state_video (video_id, counted_as_view, rating),
    CONSTRAINT fk_video_user_state_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_video_user_state_video FOREIGN KEY (video_id)
        REFERENCES video(id) ON DELETE CASCADE,
    CONSTRAINT chk_video_user_position CHECK (max_position_sec >= 0),
    CONSTRAINT chk_video_user_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- #############################################################################
-- 8) COMMENT, DICH CAU, MODERATION, QUOTA VA CREDIT
-- #############################################################################

-- Comment da hinh thay flashcard_comment va video_comment.
-- target ton tai duoc service/trigger kiem tra vi MySQL khong co FK da hinh.
CREATE TABLE user_notification (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,
    type        VARCHAR(40) NOT NULL,
    title       VARCHAR(160) NOT NULL,
    message     VARCHAR(1000) NOT NULL,
    target_url  VARCHAR(500) NULL,
    event_key   VARCHAR(160) NULL,
    read_at     DATETIME(6) NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_notification_event (user_id, event_key),
    KEY idx_user_notification_feed (user_id, created_at),
    KEY idx_user_notification_unread (user_id, read_at),
    CONSTRAINT fk_user_notification_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE content_comment (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    target_type VARCHAR(30) NOT NULL,       -- FLASHCARD_SET/VIDEO/LESSON/SCENARIO/TEXTBOOK.
    target_id   BIGINT UNSIGNED NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    content     TEXT NOT NULL,
    is_hidden   TINYINT(1) NOT NULL DEFAULT 0,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_content_comment_target (target_type, target_id, created_at),
    KEY idx_content_comment_user (user_id, created_at),
    CONSTRAINT fk_content_comment_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_content_comment_target_type
        CHECK (target_type IN ('FLASHCARD_SET','VIDEO','LESSON','SCENARIO','TEXTBOOK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ban dich ben vung cua cau user nhap tu do; khong phai cache TTL.
CREATE TABLE sentence_translation (
    source_hash     VARCHAR(64) NOT NULL,
    target_language VARCHAR(10) NOT NULL DEFAULT 'vi',
    source_text     MEDIUMTEXT NOT NULL,
    translated_text MEDIUMTEXT NOT NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                    ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (source_hash, target_language)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Log ket qua moderation; co expires_at de ho tro retention.
CREATE TABLE content_moderation_log (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    target_type      VARCHAR(40) NOT NULL,
    target_id        BIGINT UNSIGNED NULL,
    owner_user_id    BIGINT UNSIGNED NULL,
    content_hash     CHAR(64) NULL,
    source           VARCHAR(40) NOT NULL,
    verdict          VARCHAR(20) NOT NULL,
    primary_category VARCHAR(100) NULL,
    categories_json  JSON NULL,
    confidence       DECIMAL(5,4) NULL,
    risk_score       TINYINT UNSIGNED NOT NULL DEFAULT 0,
    model_name       VARCHAR(100) NULL,
    summary          VARCHAR(500) NULL,
    decision_reason  TEXT NULL,
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at       DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_moderation_target (target_type, target_id),
    KEY idx_moderation_owner_created (owner_user_id, created_at),
    KEY idx_moderation_verdict_created (verdict, created_at),
    KEY idx_moderation_expiry (expires_at),
    CONSTRAINT fk_moderation_owner FOREIGN KEY (owner_user_id)
        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_moderation_risk CHECK (risk_score <= 100),
    CONSTRAINT chk_moderation_confidence
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Cau hinh moderation theo term hoac threshold.
CREATE TABLE moderation_policy (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    policy_type VARCHAR(20) NOT NULL,
    policy_key  VARCHAR(255) NOT NULL,
    language    VARCHAR(10) NOT NULL DEFAULT 'ALL',
    action      VARCHAR(20) NULL,
    config_json JSON NULL,
    is_active   TINYINT(1) NOT NULL DEFAULT 1,
    updated_by  BIGINT UNSIGNED NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_moderation_policy (policy_type, policy_key, language),
    KEY idx_moderation_policy_active (policy_type, is_active),
    CONSTRAINT fk_moderation_policy_updater FOREIGN KEY (updated_by)
        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_moderation_policy_type CHECK (policy_type IN ('TERM','THRESHOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Case review/appeal moderation dung chung.
CREATE TABLE review_case (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    case_type         VARCHAR(30) NOT NULL,
    target_type       VARCHAR(40) NOT NULL,
    target_id         BIGINT UNSIGNED NULL,
    moderation_log_id BIGINT UNSIGNED NULL,
    requester_user_id BIGINT UNSIGNED NULL,
    owner_user_id     BIGINT UNSIGNED NULL,
    reviewer_id       BIGINT UNSIGNED NULL,
    title_snapshot    VARCHAR(255) NULL,
    reason            VARCHAR(1000) NULL,
    evidence_url      VARCHAR(500) NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolution_type   VARCHAR(100) NULL,
    resolution_note   VARCHAR(500) NULL,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resolved_at       DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_review_case_status_created (status, created_at),
    KEY idx_review_case_target (target_type, target_id),
    KEY idx_review_case_requester (requester_user_id, created_at),
    CONSTRAINT fk_review_case_log FOREIGN KEY (moderation_log_id)
        REFERENCES content_moderation_log(id) ON DELETE SET NULL,
    CONSTRAINT fk_review_case_requester FOREIGN KEY (requester_user_id)
        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_review_case_owner FOREIGN KEY (owner_user_id)
        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_review_case_reviewer FOREIGN KEY (reviewer_id)
        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_review_case_status
        CHECK (status IN ('OPEN','IN_REVIEW','APPROVED','REJECTED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Lich su mute/ban cua user.
CREATE TABLE user_sanction (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id       BIGINT UNSIGNED NOT NULL,
    created_by    BIGINT UNSIGNED NOT NULL,
    sanction_type VARCHAR(20) NOT NULL,
    reason        VARCHAR(500) NULL,
    starts_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ends_at       DATETIME(6) NULL,
    revoked_at    DATETIME(6) NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_user_sanction_active (user_id, sanction_type, ends_at, revoked_at),
    CONSTRAINT fk_user_sanction_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_sanction_creator FOREIGN KEY (created_by)
        REFERENCES users(id),
    CONSTRAINT chk_user_sanction_type CHECK (sanction_type IN ('MUTE','BAN')),
    CONSTRAINT chk_user_sanction_time CHECK (ends_at IS NULL OR ends_at > starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tong quota AI theo ngay; quota thang tinh SUM theo date range.
CREATE TABLE ai_usage_daily (
    user_id      BIGINT UNSIGNED NOT NULL,
    usage_date   DATE NOT NULL,
    minutes_used DECIMAL(10,3) NOT NULL DEFAULT 0,
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                 ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, usage_date),
    KEY idx_ai_usage_date (usage_date),
    CONSTRAINT fk_ai_usage_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_usage_minutes CHECK (minutes_used >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ledger credit; users.credit_balance la so du hien tai.
CREATE TABLE user_credit_transaction (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id        BIGINT UNSIGNED NOT NULL,
    amount         INT NOT NULL,                  -- So duong la cong, so am la tru.
    reason         VARCHAR(30) NOT NULL,
    reference_type VARCHAR(30) NULL,
    reference_id   BIGINT UNSIGNED NULL,
    note           VARCHAR(200) NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_credit_user_created (user_id, created_at),
    KEY idx_credit_reference (reference_type, reference_id),
    CONSTRAINT fk_credit_transaction_user FOREIGN KEY (user_id)
        REFERENCES users(id),
    CONSTRAINT chk_credit_transaction_amount CHECK (amount <> 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- #############################################################################
-- 9) MISSION, MUC TIEU NGAY VA STREAK
-- #############################################################################

-- Dinh nghia mission dung chung cho daily va streak.
CREATE TABLE mission_definition (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code           VARCHAR(40) NOT NULL,
    mission_type   VARCHAR(20) NOT NULL,       -- DAILY hoac STREAK.
    title          VARCHAR(120) NOT NULL,
    description    VARCHAR(300) NULL,
    target_count   INT UNSIGNED NOT NULL DEFAULT 1,
    reward_credits INT UNSIGNED NOT NULL DEFAULT 0,
    display_order  INT UNSIGNED NOT NULL DEFAULT 0,
    is_active      TINYINT(1) NOT NULL DEFAULT 1,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                   ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_mission_definition_code (code),
    KEY idx_mission_definition_active (mission_type, is_active, display_order),
    CONSTRAINT chk_mission_definition_type CHECK (mission_type IN ('DAILY','STREAK')),
    CONSTRAINT chk_mission_definition_target CHECK (target_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tien do va trang thai nhan thuong mission cua user.
CREATE TABLE user_mission (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id               BIGINT UNSIGNED NOT NULL,
    mission_definition_id BIGINT UNSIGNED NOT NULL,
    occurrence_key        VARCHAR(64) NOT NULL, -- Ngay YYYY-MM-DD hoac LIFETIME.
    progress_count        INT UNSIGNED NOT NULL DEFAULT 0,
    completed_at          DATETIME(6) NULL,
    claimed_at            DATETIME(6) NULL,
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                          ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_mission_occurrence
        (user_id, mission_definition_id, occurrence_key),
    KEY idx_user_mission_lookup (user_id, occurrence_key, claimed_at),
    CONSTRAINT fk_user_mission_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_mission_definition FOREIGN KEY (mission_definition_id)
        REFERENCES mission_definition(id),
    CONSTRAINT chk_user_mission_claim
        CHECK (claimed_at IS NULL OR completed_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Muc tieu hoc moi ngay cua user.
CREATE TABLE user_learning_targets (
    user_id             BIGINT UNSIGNED NOT NULL,
    daily_vocab_target  INT UNSIGNED NOT NULL DEFAULT 15,
    daily_quiz_target   INT UNSIGNED NOT NULL DEFAULT 1,
    daily_study_minutes INT UNSIGNED NOT NULL DEFAULT 20,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_learning_target_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_target_vocab CHECK (daily_vocab_target BETWEEN 5 AND 100),
    CONSTRAINT chk_learning_target_quiz CHECK (daily_quiz_target BETWEEN 1 AND 20),
    CONSTRAINT chk_learning_target_minutes CHECK (daily_study_minutes BETWEEN 5 AND 180)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tong hoat dong hoc theo ngay.
CREATE TABLE user_daily_progress (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id             BIGINT UNSIGNED NOT NULL,
    progress_date       DATE NOT NULL,
    vocabulary_learned  INT UNSIGNED NOT NULL DEFAULT 0,
    quizzes_completed   INT UNSIGNED NOT NULL DEFAULT 0,
    study_minutes       INT UNSIGNED NOT NULL DEFAULT 0,
    target_completed_at DATETIME(6) NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_daily_progress_user_date (user_id, progress_date),
    KEY idx_daily_progress_date (progress_date, user_id),
    CONSTRAINT fk_daily_progress_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Trang thai streak hien tai; thuong streak nam trong mission_definition.
CREATE TABLE user_streaks (
    user_id               BIGINT UNSIGNED NOT NULL,
    current_streak        INT UNSIGNED NOT NULL DEFAULT 0,
    longest_streak        INT UNSIGNED NOT NULL DEFAULT 0,
    last_completed_date   DATE NULL,
    freeze_count               INT UNSIGNED NOT NULL DEFAULT 0,
    selected_goal_days          INT NULL,
    onboarding_step             VARCHAR(32) NULL,
    onboarding_completed        BOOLEAN NOT NULL DEFAULT FALSE,
    onboarding_reward_granted   BOOLEAN NOT NULL DEFAULT FALSE,
    reminder_time               TIME NULL,
    reminder_enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    last_freeze_used_date        DATE NULL,
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                          ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    KEY idx_user_streak_last_completed (last_completed_date, current_streak),
    CONSTRAINT fk_user_streak_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_user_streak_longest CHECK (longest_streak >= current_streak)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- #############################################################################
-- 10) GAMES
-- Game lay cau hoi tu flashcard_card cua flashcard_set duoc chon.
-- #############################################################################

-- Thong tin mot tran; room runtime nam trong WebSocket/in-memory.
CREATE TABLE game_match (
    match_id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    flashcard_set_id  BIGINT UNSIGNED NOT NULL, -- Nguon card cua tran.
    room_id           VARCHAR(64) NOT NULL,
    room_code         VARCHAR(16) NOT NULL,
    game_type         VARCHAR(32) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    started_at        DATETIME(6) NOT NULL,
    ended_at          DATETIME(6) NULL,
    winner_user_id    BIGINT UNSIGNED NULL,
    winner_team       INT NULL,
    settings_snapshot JSON NULL,
    PRIMARY KEY (match_id),
    KEY idx_game_match_room_code (room_code),
    KEY idx_game_match_started_at (started_at),
    KEY idx_game_match_type_status (game_type, status),
    KEY idx_game_match_flashcard_set (flashcard_set_id),
    CONSTRAINT fk_game_match_flashcard_set FOREIGN KEY (flashcard_set_id)
        REFERENCES flashcard_set(id),
    CONSTRAINT fk_game_match_winner FOREIGN KEY (winner_user_id)
        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_game_match_type
        CHECK (game_type IN ('CANNON_BATTLE','MEMORY_MATCH','AIR_DEFENSE')),
    CONSTRAINT chk_game_match_status
        CHECK (status IN ('IN_PROGRESS','COMPLETED','ABORTED')),
    CONSTRAINT chk_game_match_time CHECK (ended_at IS NULL OR ended_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Snapshot nguoi choi trong tran.
CREATE TABLE game_match_player (
    match_player_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    match_id        BIGINT UNSIGNED NOT NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    display_name    VARCHAR(150) NOT NULL,
    slot            INT NOT NULL,
    team            INT NULL,
    score           INT NOT NULL DEFAULT 0,
    result          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    joined_at       DATETIME(6) NOT NULL,
    disconnected    TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (match_player_id),
    UNIQUE KEY uk_game_match_player (match_id, user_id),
    UNIQUE KEY uk_game_match_slot (match_id, slot),
    KEY idx_game_match_player_user (user_id),
    CONSTRAINT fk_game_match_player_match FOREIGN KEY (match_id)
        REFERENCES game_match(match_id) ON DELETE CASCADE,
    CONSTRAINT fk_game_match_player_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_game_match_player_result
        CHECK (result IN ('PENDING','WIN','LOSE','DRAW','FORFEIT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ket qua Memory Match cua tung user.
CREATE TABLE memory_match_result (
    result_id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id          VARCHAR(64) NOT NULL,
    match_id            BIGINT UNSIGNED NULL,
    room_id             VARCHAR(64) NULL,
    user_id             BIGINT UNSIGNED NOT NULL,
    play_mode           VARCHAR(16) NOT NULL,
    objective           VARCHAR(16) NOT NULL,
    pair_mode           VARCHAR(32) NOT NULL,
    jlpt_level          VARCHAR(10) NOT NULL,
    outcome             VARCHAR(20) NOT NULL,
    board_size          INT UNSIGNED NOT NULL,
    pairs_found         INT UNSIGNED NOT NULL DEFAULT 0,
    mistakes            INT UNSIGNED NOT NULL DEFAULT 0,
    moves               INT UNSIGNED NOT NULL DEFAULT 0,
    best_streak         INT UNSIGNED NOT NULL DEFAULT 0,
    accuracy_percent    INT UNSIGNED NULL,
    average_decision_ms INT UNSIGNED NULL,
    duration_ms         BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ranked              TINYINT(1) NOT NULL DEFAULT 0,
    winner              TINYINT(1) NOT NULL DEFAULT 0,
    finished_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (result_id),
    UNIQUE KEY uk_memory_result_session_user (session_id, user_id),
    KEY idx_memory_result_user_finished (user_id, finished_at),
    KEY idx_memory_result_match (match_id),
    CONSTRAINT fk_memory_result_match FOREIGN KEY (match_id)
        REFERENCES game_match(match_id) ON DELETE SET NULL,
    CONSTRAINT fk_memory_result_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_memory_result_accuracy
        CHECK (accuracy_percent IS NULL OR accuracy_percent <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ket qua Air Defense cua tung user.
CREATE TABLE air_defense_result (
    result_id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id          VARCHAR(64) NOT NULL,
    match_id            BIGINT UNSIGNED NULL,
    room_id             VARCHAR(64) NULL,
    user_id             BIGINT UNSIGNED NOT NULL,
    play_mode           VARCHAR(16) NOT NULL,
    objective           VARCHAR(24) NOT NULL,
    difficulty          VARCHAR(16) NOT NULL,
    answer_mode         VARCHAR(32) NOT NULL,
    jlpt_level          VARCHAR(10) NOT NULL,
    outcome             VARCHAR(24) NOT NULL,
    hp_remaining        INT UNSIGNED NOT NULL,
    score               INT NOT NULL DEFAULT 0,
    questions_answered  INT UNSIGNED NOT NULL DEFAULT 0,
    correct_answers     INT UNSIGNED NOT NULL DEFAULT 0,
    incorrect_answers   INT UNSIGNED NOT NULL DEFAULT 0,
    accuracy_percent    INT UNSIGNED NOT NULL DEFAULT 0,
    best_combo          INT UNSIGNED NOT NULL DEFAULT 0,
    average_response_ms INT UNSIGNED NULL,
    duration_ms         BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ranked              TINYINT(1) NOT NULL DEFAULT 0,
    winner              TINYINT(1) NOT NULL DEFAULT 0,
    finished_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (result_id),
    UNIQUE KEY uk_air_result_session_user (session_id, user_id),
    KEY idx_air_result_user_finished (user_id, finished_at),
    KEY idx_air_result_match (match_id),
    CONSTRAINT fk_air_result_match FOREIGN KEY (match_id)
        REFERENCES game_match(match_id) ON DELETE SET NULL,
    CONSTRAINT fk_air_result_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_air_result_accuracy CHECK (accuracy_percent <= 100),
    CONSTRAINT chk_air_result_answers
        CHECK (correct_answers + incorrect_answers <= questions_answered)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Catalog tau trong Air Defense.
CREATE TABLE air_defense_spaceships (
    ship_id            VARCHAR(64) NOT NULL,
    name               VARCHAR(128) NOT NULL,
    role               VARCHAR(32) NOT NULL DEFAULT 'BALANCED',
    description        TEXT NULL,
    price_coins        INT UNSIGNED NOT NULL DEFAULT 0,
    base_hp            INT UNSIGNED NOT NULL DEFAULT 100,
    speed_mult         DECIMAL(5,2) NOT NULL DEFAULT 1.00,
    passive_skill_code VARCHAR(64) NULL,
    color_theme        VARCHAR(32) NOT NULL DEFAULT 'cyan',
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (ship_id),
    CONSTRAINT chk_air_ship_hp CHECK (base_hp > 0),
    CONSTRAINT chk_air_ship_speed CHECK (speed_mult > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tau user da mua.
CREATE TABLE user_spaceships (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id      BIGINT UNSIGNED NOT NULL,
    ship_id      VARCHAR(64) NOT NULL,
    purchased_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_spaceship (user_id, ship_id),
    KEY idx_user_spaceship_ship (ship_id),
    CONSTRAINT fk_user_spaceship_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_spaceship_ship FOREIGN KEY (ship_id)
        REFERENCES air_defense_spaceships(ship_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Kinh te/nang cap vinh vien cua Air Defense, mot dong moi user.
CREATE TABLE user_permanent_upgrades (
    user_id             BIGINT UNSIGNED NOT NULL,
    coins_balance       INT UNSIGNED NOT NULL DEFAULT 0,
    extra_base_hp_level INT UNSIGNED NOT NULL DEFAULT 0,
    coin_bonus_level    INT UNSIGNED NOT NULL DEFAULT 0,
    reroll_count_level  INT UNSIGNED NOT NULL DEFAULT 0,
    fast_start_level    INT UNSIGNED NOT NULL DEFAULT 0,
    equipped_ship_id    VARCHAR(64) NOT NULL DEFAULT 'NOVA-01',
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_upgrade_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_upgrade_ship FOREIGN KEY (equipped_ship_id)
        REFERENCES air_defense_spaceships(ship_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- DATA RETENTION DA CO SPRING SCHEDULER:
-- Code: modules/maintenance/retention/DataRetentionScheduler.java
-- Mac dinh job chay dry-run luc 02:30; dat DATA_RETENTION_DRY_RUN=false de xoa that.
-- * auth_tokens: xoa token da het han qua thoi gian grace cau hinh.
-- * content_moderation_log: xoa theo expires_at, tru case OPEN/IN_REVIEW.
-- * conversation_session: xoa phien da ket thuc va cu hon thoi han cau hinh.
-- CAC NHOM SAU CHI LA LUU Y, KHONG CO JOB XOA:
-- * assessment_attempt/answer va cac subtype JLPT/Smart Test: giu de audit hoc tap.
-- * assessment_question da duoc dung trong attempt: archive bang is_active=0, khong hard-delete.
-- * payments: giu lau dai de doi soat, hoan tien va kiem tra lich su mua goi.
-- * user_credit_transaction: giu lau dai va backup.
-- * radical, kanji, kanji_reading, vocabulary_entry,
--   vocabulary_reading, vocabulary_variant, vocabulary_sense, vocabulary_gloss,
--   dictionary_part_of_speech, dictionary_domain va sentence_translation:
--   du lieu ben vung, khong TTL.
-- =============================================================================

-- =============================================================================
-- Shadowing series and comment ratings
-- =============================================================================
-- Shadowing playlists/series. Runtime initializer also creates these tables for existing installs.
CREATE TABLE IF NOT EXISTS shadowing_series (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    estimated_level VARCHAR(16) NULL,
    level_source VARCHAR(16) NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_shadowing_series_public (visibility, created_at),
    INDEX idx_shadowing_series_owner (owner_id)
);

CREATE TABLE IF NOT EXISTS shadowing_series_video (
    series_id BIGINT NOT NULL,
    video_id BIGINT NOT NULL,
    position INT NOT NULL,
    PRIMARY KEY (series_id, video_id),
    INDEX idx_shadowing_series_video_position (series_id, position)
);

CREATE TABLE IF NOT EXISTS content_comment_rating (
    comment_id BIGINT NOT NULL PRIMARY KEY,
    rating TINYINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);


-- =============================================================================
-- Pronunciation assessment history
-- =============================================================================
-- Pronunciation history for multi-device Shadowing practice.
-- Audio bytes stay outside MySQL; storage_key points to local storage now and S3 later.
CREATE TABLE IF NOT EXISTS pronunciation_attempt (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    video_id BIGINT UNSIGNED NOT NULL,
    subtitle_id BIGINT UNSIGNED NOT NULL,
    storage_key VARCHAR(700) NOT NULL,
    audio_content_type VARCHAR(100) NOT NULL,
    audio_size_bytes BIGINT NOT NULL,
    audio_duration_seconds DECIMAL(8,3) NOT NULL,
    recognition_status VARCHAR(30) NOT NULL,
    reference_text TEXT NOT NULL,
    recognized_text TEXT NULL,
    pronunciation_score DECIMAL(5,2) NULL,
    accuracy_score DECIMAL(5,2) NULL,
    fluency_score DECIMAL(5,2) NULL,
    completeness_score DECIMAL(5,2) NULL,
    prosody_score DECIMAL(5,2) NULL,
    feedback VARCHAR(1000) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_pronunciation_attempt_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_pronunciation_attempt_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE,
    CONSTRAINT fk_pronunciation_attempt_subtitle FOREIGN KEY (subtitle_id) REFERENCES subtitle(id) ON DELETE CASCADE,
    INDEX idx_pronunciation_attempt_user_video (user_id, video_id, id),
    INDEX idx_pronunciation_attempt_user_subtitle (user_id, subtitle_id, id)
);

CREATE TABLE IF NOT EXISTS pronunciation_word_result (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attempt_id BIGINT UNSIGNED NOT NULL,
    position_index INT NOT NULL,
    word_text VARCHAR(255) NOT NULL,
    accuracy_score DECIMAL(5,2) NULL,
    error_type VARCHAR(40) NULL,
    CONSTRAINT fk_pronunciation_word_attempt FOREIGN KEY (attempt_id)
        REFERENCES pronunciation_attempt(id) ON DELETE CASCADE,
    UNIQUE KEY uk_pronunciation_word_position (attempt_id, position_index),
    INDEX idx_pronunciation_word_attempt (attempt_id)
);

CREATE TABLE IF NOT EXISTS pronunciation_phoneme_result (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    word_result_id BIGINT UNSIGNED NOT NULL,
    position_index INT NOT NULL,
    phoneme_text VARCHAR(100) NOT NULL,
    accuracy_score DECIMAL(5,2) NULL,
    CONSTRAINT fk_pronunciation_phoneme_word FOREIGN KEY (word_result_id)
        REFERENCES pronunciation_word_result(id) ON DELETE CASCADE,
    UNIQUE KEY uk_pronunciation_phoneme_position (word_result_id, position_index),
    INDEX idx_pronunciation_phoneme_word (word_result_id)
);


-- =============================================================================
-- Shadowing listening practice
-- =============================================================================
-- Shadowing listening practice: cached exercise analysis and multi-device sessions.
-- This integrated section follows the core users/video/subtitle tables.

CREATE TABLE IF NOT EXISTS shadowing_exercise_analysis (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subtitle_id BIGINT UNSIGNED NOT NULL,
    source_hash CHAR(64) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    vocabulary_factor TINYINT UNSIGNED NOT NULL DEFAULT 0,
    grammar_factor TINYINT UNSIGNED NOT NULL DEFAULT 0,
    listening_speed_factor TINYINT UNSIGNED NOT NULL DEFAULT 0,
    script_complexity_factor TINYINT UNSIGNED NOT NULL DEFAULT 0,
    listening_traps_factor TINYINT UNSIGNED NOT NULL DEFAULT 0,
    sentence_difficulty TINYINT UNSIGNED NOT NULL,
    analysis_source VARCHAR(20) NOT NULL,
    analyzed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_shadowing_exercise_analysis_subtitle FOREIGN KEY (subtitle_id)
        REFERENCES subtitle(id) ON DELETE CASCADE,
    UNIQUE KEY uk_shadowing_exercise_analysis_subtitle (subtitle_id),
    INDEX idx_shadowing_exercise_analysis_difficulty (sentence_difficulty, subtitle_id),
    INDEX idx_shadowing_exercise_analysis_version (prompt_version, source_hash)
);

CREATE TABLE IF NOT EXISTS shadowing_cloze_candidate (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    analysis_id BIGINT UNSIGNED NOT NULL,
    candidate_order SMALLINT UNSIGNED NOT NULL,
    answer_text VARCHAR(500) NOT NULL,
    reading_text VARCHAR(500) NULL,
    occurrence_index SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    candidate_type VARCHAR(30) NOT NULL,
    jlpt_level VARCHAR(10) NULL,
    importance_factor TINYINT UNSIGNED NOT NULL DEFAULT 0,
    grammar_complexity_factor TINYINT UNSIGNED NOT NULL DEFAULT 0,
    context_predictability_factor TINYINT UNSIGNED NOT NULL DEFAULT 0,
    acoustic_confusability_factor TINYINT UNSIGNED NOT NULL DEFAULT 0,
    confidence_factor TINYINT UNSIGNED NOT NULL DEFAULT 0,
    difficulty_score TINYINT UNSIGNED NOT NULL,
    generation_source VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_shadowing_cloze_candidate_analysis FOREIGN KEY (analysis_id)
        REFERENCES shadowing_exercise_analysis(id) ON DELETE CASCADE,
    UNIQUE KEY uk_shadowing_cloze_candidate_order (analysis_id, candidate_order),
    INDEX idx_shadowing_cloze_candidate_difficulty (difficulty_score, analysis_id)
);

CREATE TABLE IF NOT EXISTS shadowing_practice_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    video_id BIGINT UNSIGNED NOT NULL,
    practice_mode VARCHAR(20) NOT NULL,
    question_order VARCHAR(20) NOT NULL,
    requested_count INT UNSIGNED NOT NULL,
    total_questions INT UNSIGNED NOT NULL,
    current_position INT UNSIGNED NOT NULL DEFAULT 0,
    correct_count INT UNSIGNED NOT NULL DEFAULT 0,
    score_total INT UNSIGNED NOT NULL DEFAULT 0,
    random_seed BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_shadowing_practice_session_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_shadowing_practice_session_video FOREIGN KEY (video_id)
        REFERENCES video(id) ON DELETE CASCADE,
    INDEX idx_shadowing_practice_session_user_video (user_id, video_id, started_at),
    INDEX idx_shadowing_practice_session_user_status (user_id, status, started_at)
);

CREATE TABLE IF NOT EXISTS shadowing_practice_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT UNSIGNED NOT NULL,
    subtitle_id BIGINT UNSIGNED NOT NULL,
    cloze_candidate_id BIGINT UNSIGNED NULL,
    position_index INT UNSIGNED NOT NULL,
    difficulty_score TINYINT UNSIGNED NOT NULL,
    answered_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_shadowing_practice_item_session FOREIGN KEY (session_id)
        REFERENCES shadowing_practice_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_shadowing_practice_item_subtitle FOREIGN KEY (subtitle_id)
        REFERENCES subtitle(id) ON DELETE CASCADE,
    CONSTRAINT fk_shadowing_practice_item_candidate FOREIGN KEY (cloze_candidate_id)
        REFERENCES shadowing_cloze_candidate(id) ON DELETE SET NULL,
    UNIQUE KEY uk_shadowing_practice_item_position (session_id, position_index),
    INDEX idx_shadowing_practice_item_subtitle (subtitle_id),
    INDEX idx_shadowing_practice_item_candidate (cloze_candidate_id)
);

CREATE TABLE IF NOT EXISTS shadowing_practice_answer (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    practice_item_id BIGINT UNSIGNED NOT NULL,
    attempt_number SMALLINT UNSIGNED NOT NULL,
    user_answer TEXT NOT NULL,
    normalized_answer TEXT NOT NULL,
    answer_result VARCHAR(20) NOT NULL,
    score TINYINT UNSIGNED NOT NULL,
    feedback VARCHAR(1000) NOT NULL,
    answered_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_shadowing_practice_answer_item FOREIGN KEY (practice_item_id)
        REFERENCES shadowing_practice_item(id) ON DELETE CASCADE,
    UNIQUE KEY uk_shadowing_practice_answer_attempt (practice_item_id, attempt_number),
    INDEX idx_shadowing_practice_answer_result (answer_result, answered_at)
);

-- End of canonical schema.
