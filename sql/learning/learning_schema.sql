-- [JLAS-W0108] Learning flow schema - the ONE schema file of the Learning flow (owner 2026-11-01).
--
-- Scope: every table, column, index, foreign key and CHECK owned by the Learning flow, in its latest state:
--   * learning survey + Smart Test (user_learning_survey, smart_test_session*, v_smart_test_session_summary),
--   * learning path W0108 (user_learning_path*, learning_path_package, learning_activity_member, learning_quiz_session,
--     study/micro-check/grammar/dialogue progress, weakness + recommendation, quiz_attempt, generated questions),
--   * goal-specific curriculum and goal survey (learning_goal, learning_topic*, learning_competency, competency_level,
--     lesson_learning_scope, lesson_prerequisite, learning_survey_*, user_goal_survey_*, assessment_question_usage),
--   * Learning-owned holding tables (jlpt_mock_attempt, learning_practice_draft_question, learning_owned_source_draft),
--   * Learning-owned columns/CHECKs on shared tables (textbook, unit, lesson, assessment_question, content_vocabulary).
--   New Learning tables (jpath) are added to THIS file, not to a new migration.
--
-- Replaces: sql/migrations/004-012, 014, 015 (Learning parts) and 022_learning_owned_consolidated.sql.
-- jlas_schema.sql keeps its copy of the Learning tables until the team schema is merged; both describe the same shape.
--
-- How to run (MySQL 8.0.16+): select the target database, verify it, run this file, then the data files in order
-- (learning_data_common.sql, learning_data_N5.sql ... learning_data_N1.sql; see sql/learning/README.md):
--   mysql -u root -p <db> -e "SELECT DATABASE()"      -- must print the intended database
--   mysql -u root -p <db> < sql/learning/learning_schema.sql
-- There is no USE line on purpose. Re-runnable: tables use CREATE TABLE IF NOT EXISTS; every later column, index,
-- foreign key and CHECK change is guarded by information_schema (PART B), so a second run changes nothing.
-- Works on an empty database that already has the shared tables, on a database built from jlas_schema.sql, and on an
-- older live database (pre-V2 Learning tables are upgraded in place). Never run with --force.

SET NAMES utf8mb4;

-- =============================================================================
-- PART A. Learning tables in their final shape (CREATE TABLE IF NOT EXISTS).
-- Order follows jlas_schema.sql so every foreign key target already exists.
-- =============================================================================

-- [JLAS-W0108] Snapshot khảo sát đầu vào (goal/target_level/self_reported_level + thời lượng học),
-- là đầu vào cho sinh learning path cá nhân hoá và khởi tạo Smart Test.
CREATE TABLE IF NOT EXISTS user_learning_survey (
    user_id BIGINT UNSIGNED NOT NULL,
    goal VARCHAR(30) NOT NULL,
    target_level VARCHAR(10) NULL,
    self_reported_level VARCHAR(20) NOT NULL,
    study_duration_months INT NULL,
    daily_study_minutes INT NULL,
    -- [JLAS-W0108] F1-3 (2026-10-27): optional JLPT exam date; a path that cannot finish before it requires CORE words only.
    exam_date DATE NULL,
    -- [JLAS-W0108] F1-5 (2026-10-28): up to two JLPT skills to focus on (VOCABULARY, READING, LISTENING), comma-separated;
    -- they are never trimmed when a path is squeezed by the exam date. NULL: no focus.
    focus_skills VARCHAR(40) NULL,
    skills VARCHAR(100) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_learning_survey_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Profile va ket qua cap cao cua Smart Test; user/status/thoi gian dung tu attempt cha.
CREATE TABLE IF NOT EXISTS smart_test_session (
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
CREATE TABLE IF NOT EXISTS smart_test_session_question (
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
CREATE TABLE IF NOT EXISTS smart_test_skill_result (
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

-- Lo trinh hoc dai han co version cua user.
CREATE TABLE IF NOT EXISTS user_learning_path (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    -- [JLAS-W0193] Explicit compatibility boundary for legacy and interactive paths.
    experience_version VARCHAR(30) NOT NULL DEFAULT 'LEGACY_V1',
    user_id      BIGINT UNSIGNED NOT NULL,
    title        VARCHAR(255) NOT NULL,
    goal         VARCHAR(20) NULL,
    -- [JLAS-W0108] Goal-specific delivery preference; NULL for legacy/JLPT paths.
    learning_mode VARCHAR(30) NULL,
    target_level VARCHAR(10) NULL,
    current_level VARCHAR(10) NULL,
    -- [JLAS-W0108] Snapshot nguồn sinh path (survey + Smart Test attempt) + policy version để
    -- tái sinh path hai pha (warm-up → goal) không mất lịch sử; không FK để tránh vòng tham chiếu.
    survey_id             BIGINT UNSIGNED NULL,  -- Survey snapshot reference (no FK yet).
    assessment_attempt_id BIGINT UNSIGNED NULL,  -- Smart Test attempt snapshot (no FK yet).
    assessment_revision   VARCHAR(50) NULL,
    policy_version        VARCHAR(100) NULL,
    minutes_per_day       INT UNSIGNED NULL,
    coverage_gaps_json    TEXT NULL,             -- JSON array of coverage gaps.
    -- [JLAS-W0113] Thoi diem vuot gate quiz tong ket warm-up; NULL = chua vuot, chan giai doan theo muc tieu.
    gate_passed_at        DATETIME(6) NULL,
    -- [JLAS-W0108] Thời điểm lộ trình hoàn thành (chặng cuối đạt final); NULL = chưa hoàn thành.
    -- Dùng làm ngày cấp chứng chỉ thành tựu nội bộ, không suy từ updated_at (có thể đổi vì lý do khác).
    completed_at          DATETIME(6) NULL,
    -- [JLAS-W0108] Mock Test attempt da xac nhan hoan thanh lo trinh (retry chi hop le voi dung attempt nay).
    completion_attempt_id BIGINT UNSIGNED NULL,
    -- [JLAS-W0108] Cache id bo flashcard warm-up gop Kanji+Tu vung sai Smart Test; NULL = chua sinh.
    -- Soft-link toi flashcard_set (khong FK, giu learning decoupled khoi flashcard).
    warm_up_flashcard_set_id BIGINT UNSIGNED NULL,
    -- [JLAS-W0108] P2d — fingerprint membership bo flashcard (identity expression+reading da khu trung,
    -- sap thu tu). Khac fingerprint hien tai => regenerate bo khi replan them muc yeu moi (muc moi chua
    -- nho, giu tien do the cu). TEXT vi membership co the dai (nhieu tu yeu).
    warm_up_flashcard_version TEXT NULL,
    -- [JLAS-W0108] Kế hoạch lộ trình cá nhân hoá do Gemini sinh (JSON: overview + phases[]);
    -- NULL = chưa sinh (Gemini lỗi/rate-limit), UI vẫn chạy bình thường không cần narrative.
    ai_plan_json TEXT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version      INT UNSIGNED NOT NULL DEFAULT 1,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                 ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- [JLAS-W0122] Bỏ UNIQUE KEY uk_learning_path_generation (user_id, assessment_attempt_id,
    -- assessment_revision, policy_version): replan tạo version mới với CÙNG generation identity
    -- nên key cũ chặn insert bản v2 (hàng SUPERSEDED còn giữ key). Uniqueness giờ do
    -- uk_learning_path_user_version (user_id, version) + idempotency check tầng service đảm bảo.
    UNIQUE KEY uk_learning_path_user_version (user_id, version),
    KEY idx_learning_path_user_status (user_id, status, updated_at),
    CONSTRAINT fk_learning_path_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_path_goal
        CHECK (goal IS NULL OR goal IN ('JLPT','TRAVEL','DAILY_LIFE','BA','FROM_ZERO')),
    CONSTRAINT chk_learning_path_mode CHECK (learning_mode IS NULL OR learning_mode IN
        ('FOUNDATION_FIRST','COMMUNICATION_FIRST','BALANCED')),
    CONSTRAINT chk_learning_path_status CHECK (status IN ('ACTIVE','SUPERSEDED','COMPLETED')),
    CONSTRAINT chk_learning_path_experience
        CHECK (experience_version IN ('LEGACY_V1','INTERACTIVE_V2')),
    CONSTRAINT chk_learning_path_target
        CHECK (target_level IS NULL OR target_level IN ('N5','N4','N3','N2','N1'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0193] A resumable day package owned by one interactive path.
CREATE TABLE IF NOT EXISTS learning_path_package (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    learning_path_id BIGINT UNSIGNED NOT NULL,
    source_lesson_id BIGINT UNSIGNED NULL,
    source_segment_index INT UNSIGNED NOT NULL DEFAULT 1,
    content_revision VARCHAR(64) NULL,
    stage_id BIGINT UNSIGNED NULL,
    phase VARCHAR(20) NOT NULL,
    day_number INT UNSIGNED NOT NULL,
    order_index INT UNSIGNED NOT NULL,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'LOCKED',
    estimated_minutes INT UNSIGNED NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    -- [JLAS-W0193] Task 8 — computed package score once completed; NULL until then. Derived from real
    -- learning_quiz_session correct/total counts, never hard-coded; Dialogue never contributes to it.
    score_percent INT UNSIGNED NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- [JLAS-W0108] D3-05 (owner 2026-10-24): a JLPT day is filled by minutes, so two chapters may share one day;
    -- day_number is indexed, not unique. idx_learning_package_path_day is added in PART B (B4) on every database,
    -- so the index order matches databases that received it as an upgrade.
    UNIQUE KEY uk_learning_package_path_order (learning_path_id, order_index),
    UNIQUE KEY uk_learning_package_path_source_segment (learning_path_id, source_lesson_id, source_segment_index),
    KEY idx_learning_package_status (learning_path_id, status, order_index),
    CONSTRAINT fk_learning_package_path FOREIGN KEY (learning_path_id)
        REFERENCES user_learning_path(id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_package_source_lesson FOREIGN KEY (source_lesson_id)
        REFERENCES lesson(id) ON DELETE RESTRICT,
    CONSTRAINT chk_learning_package_phase CHECK (phase IN ('WARM_UP','GOAL_LEARNING')),
    CONSTRAINT chk_learning_package_status CHECK (status IN ('LOCKED','AVAILABLE','IN_PROGRESS','COMPLETED')),
    CONSTRAINT chk_learning_package_minutes CHECK (estimated_minutes > 0),
    CONSTRAINT chk_learning_package_score CHECK (score_percent IS NULL OR score_percent BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Lesson trong learning path; cho phep cung lesson xuat hien lai de review.
CREATE TABLE IF NOT EXISTS user_learning_path_item (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    learning_path_id BIGINT UNSIGNED NOT NULL,
    package_id BIGINT UNSIGNED NULL,
    activity_type VARCHAR(20) NULL,
    activity_state VARCHAR(20) NULL,
    -- [JLAS-W0125] Ordered skill parts inside a stable package; legacy rows remain part 1.
    part_index INT UNSIGNED NOT NULL DEFAULT 1,
    -- [JLAS-W0108] P2c — bài nguồn có thể NULL khi activity trỏ tài nguyên khác (grammar_point /
    -- vocabulary_entry / reading-listening content_item) mà không cần một lesson_id giả.
    lesson_id        BIGINT UNSIGNED NULL,
    -- [JLAS-W0108] Item warm-up trỏ tới đúng kiến thức đã sai (nullable; NULL = cả bài).
    grammar_point_id  BIGINT UNSIGNED NULL,       -- Warm-up GRAMMAR: mẫu ngữ pháp đã sai.
    vocabulary_entry_id BIGINT UNSIGNED NULL,     -- Warm-up KANJI/VOCAB: từ vựng đã sai.
    order_index      INT UNSIGNED NOT NULL,
    -- [JLAS-W0108] P6 — chặng mà activity thuộc về (soft-link id của user_learning_stage);
    -- NULL cho item cũ (path một cấp trước P6) để giữ tương thích không phá lịch sử.
    stage_id         BIGINT UNSIGNED NULL,
    reason           TEXT NULL,
    -- [JLAS-W0108] Item hai pha (WARM_UP → GOAL_LEARNING): phase + reason_code + day_number
    -- sắp lịch lộ trình; kind để NULL cho item hai pha, giữ tương thích item cũ.
    kind             VARCHAR(20) NULL,           -- CURRICULUM/REVIEW (legacy); NULL for two-phase items.
    phase            VARCHAR(20) NULL,           -- WARM_UP / GOAL_LEARNING.
    reason_code      VARCHAR(50) NULL,
    day_number       INT UNSIGNED NULL,
    estimated_minutes INT UNSIGNED NULL,
    matched_tag_codes TEXT NULL,                 -- JSON array of matched knowledge tag codes.
    -- [JLAS-W0108] P2 — tiến độ độc lập theo activity (không theo source lesson):
    -- study = nội dung (NOT_STARTED → IN_PROGRESS → STUDIED);
    -- assessment = đánh giá (NOT_READY/READY → ISSUED → SUBMITTED → PASSED/NEEDS_REVIEW).
    study_status      VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    assessment_status VARCHAR(20) NOT NULL DEFAULT 'NOT_READY',
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_path_item_order (learning_path_id, order_index),
    KEY idx_learning_path_item_lesson (lesson_id),
    KEY idx_learning_path_item_grammar (grammar_point_id),
    KEY idx_learning_path_item_vocab (vocabulary_entry_id),
    UNIQUE KEY uk_learning_path_item_package_part (package_id, activity_type, part_index),
    KEY idx_learning_path_item_package_order (package_id, order_index),
    CONSTRAINT fk_learning_path_item_path FOREIGN KEY (learning_path_id)
        REFERENCES user_learning_path(id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_path_item_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_path_item_grammar FOREIGN KEY (grammar_point_id)
        REFERENCES grammar_point(id) ON DELETE SET NULL,
    CONSTRAINT fk_learning_path_item_vocab FOREIGN KEY (vocabulary_entry_id)
        REFERENCES vocabulary_entry(id) ON DELETE SET NULL,
    CONSTRAINT fk_learning_path_item_package FOREIGN KEY (package_id)
        REFERENCES learning_path_package(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_path_item_kind CHECK (kind IS NULL OR kind IN ('CURRICULUM','REVIEW')),
    CONSTRAINT chk_learning_path_item_study_status
        CHECK (study_status IN ('NOT_STARTED','IN_PROGRESS','STUDIED')),
    CONSTRAINT chk_learning_path_item_assessment_status
        CHECK (assessment_status IN ('NOT_READY','READY','ISSUED','SUBMITTED','PASSED','NEEDS_REVIEW')),
    CONSTRAINT chk_learning_path_item_activity_type CHECK
        -- [JLAS-W0193] Interactive V2 comprehension activities reference canonical assessment questions.
        -- [JLAS-W0108] FOUNDATION = bảng chữ cái, chỉ trong package WARM_UP cho người học from-zero.
        (activity_type IS NULL OR activity_type IN ('VOCABULARY','KANJI','GRAMMAR','DIALOGUE','READING','LISTENING','FOUNDATION')),
    CONSTRAINT chk_learning_path_item_activity_state CHECK
        (activity_state IS NULL OR activity_state IN ('LOCKED','STUDYING','CHECK_READY','PASSED','COOLDOWN','COMPLETED'))
    -- [JLAS-W0108] P2c — KHÔNG đặt CHECK "ít nhất một tham chiếu hợp lệ" (lesson/grammar/vocab) ở đây:
    -- MySQL 8.0.16+ cấm cột trong CHECK đồng thời nằm trong FK có referential action
    -- (grammar_point_id / vocabulary_entry_id dùng ON DELETE SET NULL) → lỗi 3823.
    -- Ràng buộc này do tầng ứng dụng đảm bảo (LearningServiceImpl chọn đúng anchor).
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0193] Immutable canonical membership; content remains dictionary-owned.
CREATE TABLE IF NOT EXISTS learning_activity_member (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_item_id BIGINT UNSIGNED NOT NULL,
    member_type VARCHAR(30) NOT NULL,
    canonical_id BIGINT UNSIGNED NOT NULL,
    order_index INT UNSIGNED NOT NULL,
    source_revision VARCHAR(64) NOT NULL,
    -- [JLAS-W0120] Contextual display snapshot of the member (formerly migration 022 learning_vocabulary_context).
    content_snapshot_json TEXT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_activity_member_identity (activity_item_id, member_type, canonical_id),
    KEY idx_learning_activity_member_order (activity_item_id, order_index),
    CONSTRAINT fk_learning_activity_member_item FOREIGN KEY (activity_item_id)
        REFERENCES user_learning_path_item(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_activity_member_type CHECK
        (member_type IN ('VOCABULARY_ENTRY','KANJI','GRAMMAR_POINT','DIALOGUE_LINE','COMPREHENSION_QUESTION',
            'FOUNDATION_CHARACTER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0108] P6 — một chặng (stage) trong lộ trình JLPT nhiều cấp (D5). Mỗi chặng gắn đúng một
-- cấp mục tiêu, xếp tuần tự theo stage_index; curriculum_version/assessment_cycle riêng từng chặng.
-- Path một cấp (N5→N4) vẫn có đúng một chặng để giữ cùng contract với path nhiều cấp (N5→N3 = N4 rồi N3).
CREATE TABLE IF NOT EXISTS user_learning_stage (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    learning_path_id  BIGINT UNSIGNED NOT NULL,
    level             VARCHAR(30) NOT NULL,       -- JLPT/foundation hoặc communication level theo goal.
    stage_index       INT UNSIGNED NOT NULL,      -- Thứ tự chặng, bắt đầu từ 0.
    curriculum_version VARCHAR(100) NULL,         -- Fingerprint bộ sách đã duyệt; P8 ghim manifest thật.
    assessment_cycle  VARCHAR(50) NULL,           -- Đợt đánh giá cuối chặng; 1 = đợt đầu, tăng khi retest.
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                      ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_stage_order (learning_path_id, stage_index),
    KEY idx_learning_stage_path (learning_path_id),
    CONSTRAINT fk_learning_stage_path FOREIGN KEY (learning_path_id)
        REFERENCES user_learning_path(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_stage_level
        CHECK (level IN ('FOUNDATION','N5','N4','N3','N2','N1',
            'BASIC','FUNCTIONAL','PROBLEM_SOLVING','FLEXIBLE')),
    CONSTRAINT chk_learning_stage_status CHECK (status IN ('PENDING','ACTIVE','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0108] Câu hỏi quiz do Gemini sinh để BÙ phần ngân hàng câu thiếu, scoped theo learning path.
-- Tách bạch khỏi assessment_question (ngân hàng Smart Test) để không nhiễm nội dung AI vào ngân hàng.
CREATE TABLE IF NOT EXISTS learning_generated_question (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    learning_path_id BIGINT UNSIGNED NOT NULL,
    -- Anchor nội dung đã ôn mà câu hỏi bám vào: GRAMMAR_POINT / VOCABULARY_ENTRY / KNOWLEDGE_TAG.
    anchor_type      VARCHAR(30) NOT NULL,
    -- Id (số, dạng chuỗi) cho GRAMMAR_POINT/VOCABULARY_ENTRY; code cho KNOWLEDGE_TAG. Soft-link, không FK.
    anchor_id        VARCHAR(64) NOT NULL,
    skill            VARCHAR(20) NOT NULL DEFAULT 'GRAMMAR',
    jlpt_level       VARCHAR(10) NULL,
    question_text    TEXT NOT NULL,
    option_1         VARCHAR(255) NOT NULL,
    option_2         VARCHAR(255) NOT NULL,
    option_3         VARCHAR(255) NOT NULL,
    option_4         VARCHAR(255) NOT NULL,
    correct_option   INT NOT NULL,           -- Chỉ số đáp án đúng từ 1 đến 4.
    explanation      TEXT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                     ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- Idempotent: mỗi anchor chỉ sinh đúng một câu cho một path (getGateQuiz tái gọi không nhân bản).
    UNIQUE KEY uk_learning_generated_question (learning_path_id, anchor_type, anchor_id),
    KEY idx_learning_generated_question_path (learning_path_id),
    CONSTRAINT fk_learning_generated_question_path FOREIGN KEY (learning_path_id)
        REFERENCES user_learning_path(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_generated_question_correct
        CHECK (correct_option BETWEEN 1 AND 4),
    CONSTRAINT chk_learning_generated_question_anchor
        CHECK (anchor_type IN ('GRAMMAR_POINT','VOCABULARY_ENTRY','KNOWLEDGE_TAG'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Diem yeu tong hop theo tag KNOWLEDGE; answer la bang chung chi tiet.
CREATE TABLE IF NOT EXISTS user_knowledge_weakness (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id       BIGINT UNSIGNED NOT NULL,
    tag_id        BIGINT UNSIGNED NULL,        -- [JLAS-W0108] nullable: KANJI/VOCAB key theo canonical_item.
    skill         VARCHAR(20) NULL,            -- Fallback khi chua tim duoc lesson khop tag.
    jlpt_level    VARCHAR(10) NULL,            -- [JLAS-W0108]
    topic_tag_id  BIGINT UNSIGNED NULL,        -- [JLAS-W0108]
    outcome_id    BIGINT UNSIGNED NULL,        -- [JLAS-W0108]
    canonical_item VARCHAR(255) NULL,          -- [JLAS-W0108] KANJI:<c> / VOCAB:<c> / GP:<id>.
    wrong_count   INT NOT NULL DEFAULT 0,      -- [JLAS-W0108]
    mastery_score DECIMAL(5,2) NULL,           -- [JLAS-W0108]
    score_percent DECIMAL(5,2) NULL,
    severity      VARCHAR(30) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'OPEN', -- [JLAS-W0108] OPEN/REVIEWED/MASTERED.
    source        VARCHAR(255) NULL,
    detected_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_reviewed_at DATETIME(6) NULL,         -- [JLAS-W0108]
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                  ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_knowledge_weakness (
        user_id,
        (COALESCE(tag_id, 0)),
        (COALESCE(canonical_item, '')),
        (COALESCE(jlpt_level, '')),
        (COALESCE(topic_tag_id, 0)),
        (COALESCE(outcome_id, 0))
    ),
    KEY idx_weakness_tag_severity (tag_id, severity),
    KEY idx_weakness_level (jlpt_level),       -- [JLAS-W0108]
    KEY idx_weakness_topic (topic_tag_id),     -- [JLAS-W0108]
    KEY idx_weakness_outcome (outcome_id),     -- [JLAS-W0108]
    CONSTRAINT fk_weakness_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_weakness_tag FOREIGN KEY (tag_id)
        REFERENCES tag(id) ON DELETE CASCADE,
    CONSTRAINT fk_weakness_topic FOREIGN KEY (topic_tag_id)      -- [JLAS-W0108]
        REFERENCES tag(id) ON DELETE SET NULL,
    CONSTRAINT fk_weakness_outcome FOREIGN KEY (outcome_id)      -- [JLAS-W0108]
        REFERENCES outcome(id) ON DELETE SET NULL,
    CONSTRAINT chk_weakness_score
        CHECK (score_percent IS NULL OR score_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_weakness_severity
        CHECK (severity IN ('WEAK','NEEDS_REINFORCEMENT')),
    CONSTRAINT chk_weakness_status CHECK (status IN ('OPEN','REVIEWED','MASTERED')), -- [JLAS-W0108]
    CONSTRAINT chk_weakness_level CHECK (jlpt_level IS NULL OR jlpt_level IN ('N5','N4','N3','N2','N1')), -- [JLAS-W0108]
    CONSTRAINT chk_weakness_skill CHECK (
        skill IS NULL OR skill IN (
            'VOCABULARY','GRAMMAR','READING','LISTENING','SPEAKING','ONYOMI','KUNYOMI','KANJI' -- [JLAS-W0108] them KANJI
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0108] Gioi han 5 luot quiz/ngay (gate quiz + lesson quiz); log moi lan nop.
CREATE TABLE IF NOT EXISTS quiz_attempt (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id      BIGINT UNSIGNED NOT NULL,
    quiz_type    VARCHAR(20) NOT NULL COMMENT 'GATE/LESSON',
    lesson_id    BIGINT UNSIGNED NULL COMMENT 'null cho GATE',
    attempted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_quiz_attempt_user (user_id, quiz_type, lesson_id, attempted_at),
    CONSTRAINT fk_quiz_attempt_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_quiz_attempt_type CHECK (quiz_type IN ('GATE','LESSON'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0108] Hop dong de thi bat bien: khi phat quiz, server chup snapshot cau hoi + dap an vao session;
-- luc nop chi cham theo snapshot (mau so = toan bo cau da phat), nop lai tra ket qua da luu, khong ton them luot.
CREATE TABLE IF NOT EXISTS learning_quiz_session (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id          BIGINT UNSIGNED NOT NULL,
    -- [JLAS-W0108] Task 10: VARCHAR(30) - LISTENING_MICRO_CHECK (21) and FOUNDATION_MICRO_CHECK (22) did not fit 20.
    quiz_type        VARCHAR(30) NOT NULL COMMENT 'GATE/LESSON/GRAMMAR/FLASHCARD/*_MICRO_CHECK/PRACTICE_REPLAY',
    lesson_id        BIGINT UNSIGNED NULL COMMENT 'null cho GATE/GRAMMAR/FLASHCARD',
    path_id          BIGINT UNSIGNED NULL COMMENT 'null cho LESSON/GRAMMAR',
    activity_id      BIGINT UNSIGNED NULL COMMENT '[JLAS-W0108] grammarPointId cho GRAMMAR quiz; null cho loai khac',
    batch_index      INT UNSIGNED NULL COMMENT '[JLAS-W0193] thu tu batch micro-check; null cho quiz legacy',
    path_version     INT NULL COMMENT 'snapshot version cua lo trinh khi phat GATE quiz',
    status           VARCHAR(20) NOT NULL DEFAULT 'ISSUED' COMMENT 'ISSUED/SUBMITTED',
    questions_json   TEXT NOT NULL COMMENT 'JSON mang cau hoi da phat (khong chua dap an)',
    answer_key_json  TEXT NOT NULL COMMENT 'JSON map questionId -> {correctOption, explanation}',
    -- [JLAS-W0108] source_snapshot_json: passage/audio dong bang cua batch Reading/Listening; NULL voi loai khac va session cu.
    source_snapshot_json TEXT NULL,
    -- [JLAS-W0113] Autosave of an in-progress micro-check (questionId -> selectedOption); written only while ISSUED,
    -- never read by grading, quota or rewards (formerly migration 025_learning_quiz_session_draft_answers).
    draft_answers_json TEXT NULL,
    question_count   INT NOT NULL COMMENT 'mau so: tong so cau da phat',
    correct_count    INT NULL,
    score_percent    INT NULL,
    passed           TINYINT(1) NULL,
    result_json      TEXT NULL COMMENT 'JSON ket qua da cham de tra lai khi nop lai (idempotent)',
    issued_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    submitted_at     DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_quiz_session_user (user_id, quiz_type, lesson_id, status),
    CONSTRAINT fk_quiz_session_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    -- [JLAS-W0193] KANJI_MICRO_CHECK (Task 5) va GRAMMAR_MICRO_CHECK (Task 6) duoc them vao day de khop
    -- dung voi cac quiz_type ma InteractiveStudyEngine da phat sinh; Task 5 tung bo sot KANJI_MICRO_CHECK
    -- trong CHECK constraint nay (H2 ddl-auto=create-drop sinh schema tu JPA entity, khong tu SQL nay nen
    -- khong bat duoc bang test) — sua cung migration voi Task 6 vi ca hai deu dung chung constraint.
    -- [JLAS-W0193] RC Task 6: READING_MICRO_CHECK/LISTENING_MICRO_CHECK bi bo sot cung ly do (RC Task 4 da
    -- phat sinh cac quiz_type nay qua AssessmentComprehensionContentAdapter.microCheckType(), nhung RC Task 2
    -- (migration 016) chi mo rong activity_type/member_type, khong mo rong constraint nay) — sua tai day,
    -- migration 017.
    -- [JLAS-W0108] FOUNDATION_MICRO_CHECK them cho bang chu cai (InteractiveFoundationServiceImpl).
    -- [JLAS-W0108] Task 10: PRACTICE_REPLAY = luyen tap lai khong tinh tien do (khong bao gio la micro-check).
    -- [JLAS-W0108] 2.32 T4: EXTRA_PRACTICE = luyen them tu kho, gan activity Doc/Nghe bat buoc; khong tinh diem/mo khoa/CP.
    CONSTRAINT chk_quiz_session_type CHECK
        (quiz_type IN ('GATE','LESSON','GRAMMAR','FLASHCARD','VOCAB_MICRO_CHECK','KANJI_MICRO_CHECK',
            'GRAMMAR_MICRO_CHECK','READING_MICRO_CHECK','LISTENING_MICRO_CHECK','FOUNDATION_MICRO_CHECK',
            'PRACTICE_REPLAY','EXTRA_PRACTICE')),
    CONSTRAINT chk_quiz_session_status CHECK (status IN ('ISSUED','SUBMITTED')),
    CONSTRAINT chk_quiz_session_score CHECK (score_percent IS NULL OR score_percent BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0193] Study session cua mot activity tu vung/Kanji; giu membership revision dang hoc de reload
-- resume dung session va replan (revision moi) reset sach. "Da nho" la study evidence, khong phai mastery.
CREATE TABLE IF NOT EXISTS learning_study_session (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_item_id BIGINT UNSIGNED NOT NULL,
    source_revision  VARCHAR(64) NOT NULL,
    row_version      BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                     ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_study_session_activity (activity_item_id),
    CONSTRAINT fk_learning_study_session_item FOREIGN KEY (activity_item_id)
        REFERENCES user_learning_path_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Trang thai flip/remember cua tung membership target trong mot study session.
CREATE TABLE IF NOT EXISTS learning_study_card (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    study_session_id BIGINT UNSIGNED NOT NULL,
    canonical_id     BIGINT UNSIGNED NOT NULL,
    order_index      INT UNSIGNED NOT NULL,
    flipped          TINYINT(1) NOT NULL DEFAULT 0,
    remembered       TINYINT(1) NOT NULL DEFAULT 0,
    flipped_at       DATETIME(6) NULL,
    remembered_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_study_card_identity (study_session_id, canonical_id),
    KEY idx_learning_study_card_order (study_session_id, order_index),
    CONSTRAINT fk_learning_study_card_session FOREIGN KEY (study_session_id)
        REFERENCES learning_study_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0193] Tong ket so luot micro-check theo (activity, batch): 3 luot/cycle, cooldown 60 phut,
-- bat buoc review truoc cycle moi. Pass quyet dinh o day; snapshot bieu bat bien nam o learning_quiz_session.
CREATE TABLE IF NOT EXISTS learning_micro_check_state (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_item_id  BIGINT UNSIGNED NOT NULL,
    batch_index       INT UNSIGNED NOT NULL DEFAULT 0,
    cycle_number      INT UNSIGNED NOT NULL DEFAULT 1,
    attempts_used     INT UNSIGNED NOT NULL DEFAULT 0,
    passed            TINYINT(1) NOT NULL DEFAULT 0,
    first_score       INT NULL,
    latest_score      INT NULL,
    best_score        INT NULL,
    cooldown_until    DATETIME(6) NULL,
    review_required   TINYINT(1) NOT NULL DEFAULT 0,
    row_version       BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                      ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_micro_check_activity_batch (activity_item_id, batch_index),
    CONSTRAINT fk_learning_micro_check_activity FOREIGN KEY (activity_item_id)
        REFERENCES user_learning_path_item(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_micro_check_attempts CHECK (attempts_used <= 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0193] Task 6 — tien do luyen tap sap xep cau + dien khuyet cua tung grammar point trong mot
-- activity GRAMMAR. Grammar khong co buoc lat/nho flashcard; bang nay la "cong" rieng cua Grammar ma
-- InteractiveStudyEngine tham van truoc khi cho phat micro-check (qua InteractiveContentAdapter.
-- assertPracticeGateSatisfied), thay vi flip/remember cua Vocabulary/Kanji. source_revision di theo
-- learning_activity_member.source_revision: composer sinh revision moi thi tien do cu bi coi la chua
-- lam xong voi noi dung moi, khong tu dong mang sang.
CREATE TABLE IF NOT EXISTS learning_grammar_practice_progress (
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

-- [JLAS-W0193] Task 7 - bang chung hoan thanh Dialogue: 1 dong/activity DIALOGUE. accumulated_seconds chi
-- tang bang gia tri server tu tinh (cap 20s/heartbeat, reset chuoi khi khoang cach heartbeat > 30s) - khong
-- bao gio nhan tong so giay hoac co "completed" tu client. reached_bottom_at la bang chung cuon-den-cuoi
-- rieng, ghi mot lan qua endpoint /reached-bottom (yeu cau da co it nhat 1 heartbeat truoc do). completed_at
-- chi duoc set khi ca hai dieu kien (>=180s VA reached_bottom_at khac null) cung dung, va khong bao gio bi
-- xoa/ghi de sau do. last_heartbeat_token la nonce client-sinh de tu choi mot heartbeat gui lai (replay)
-- khong cho cong them thoi gian lan thu hai. source_revision di theo learning_activity_member.source_revision
-- cung quy tac reset-khi-doi-revision nhu learning_grammar_practice_progress.
CREATE TABLE IF NOT EXISTS learning_dialogue_progress (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_item_id       BIGINT UNSIGNED NOT NULL,
    source_revision        VARCHAR(64) NOT NULL,
    accumulated_seconds    INT UNSIGNED NOT NULL DEFAULT 0,
    last_heartbeat_at      DATETIME(6) NULL,
    last_heartbeat_token   VARCHAR(80) NULL,
    reached_bottom_at      DATETIME(6) NULL,
    completed_at           DATETIME(6) NULL,
    row_version            BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                           ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dialogue_progress_activity (activity_item_id),
    CONSTRAINT fk_dialogue_progress_activity FOREIGN KEY (activity_item_id)
        REFERENCES user_learning_path_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0108] 2026-10-19 (owner C2/C4/G1): J-LAS supplement aids of one communication dialogue lesson, generated
-- from learning-content.json: furigana per line ({漢字|かな} groups; the base text equals the line), "Từ & cụm từ"
-- taken from the lines, and the optional "chọn câu đáp đúng" challenge whose answer key never leaves the server.
-- A content change resets review_status to NEEDS_REVIEW.
CREATE TABLE IF NOT EXISTS learning_dialogue_aid (
    lesson_id       BIGINT UNSIGNED NOT NULL,
    aid_json        MEDIUMTEXT NOT NULL,
    content_sha256  CHAR(64) NOT NULL,
    source_code     VARCHAR(64) NOT NULL,
    review_status   VARCHAR(20) NOT NULL DEFAULT 'NEEDS_REVIEW',
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                    ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (lesson_id),
    CONSTRAINT fk_dialogue_aid_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT chk_dialogue_aid_review
        CHECK (review_status IN ('NEEDS_REVIEW','APPROVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0108] 2026-10-19 (owner G2/G3): first clear of a dialogue lesson's optional challenge. The row is the
-- idempotency guard for the one-time 2 CP reward; replays never award again.
CREATE TABLE IF NOT EXISTS learning_dialogue_challenge_clear (
    user_id         BIGINT UNSIGNED NOT NULL,
    lesson_id       BIGINT UNSIGNED NOT NULL,
    correct_count   INT UNSIGNED NOT NULL,
    question_count  INT UNSIGNED NOT NULL,
    cleared_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, lesson_id),
    KEY idx_dialogue_challenge_lesson (lesson_id),
    CONSTRAINT fk_dialogue_challenge_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_dialogue_challenge_lesson FOREIGN KEY (lesson_id)
        REFERENCES lesson(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Goi y lesson on tap tu mot weakness.
CREATE TABLE IF NOT EXISTS user_lesson_recommendation (
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

-- [JLAS-W0108] Goal-specific curriculum and structured survey foundation.
-- Canonical lessons remain in lesson; these tables only classify and map them.
CREATE TABLE IF NOT EXISTS learning_goal (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    goal_code VARCHAR(30) NOT NULL,
    display_name_vi VARCHAR(255) NOT NULL,
    description_vi TEXT NULL,
    entry_policy VARCHAR(50) NOT NULL,
    requires_assessment BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    external_key VARCHAR(100) NULL,
    source_code VARCHAR(100) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REVIEWED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_goal_code (goal_code),
    UNIQUE KEY uk_learning_goal_external_key (external_key),
    CONSTRAINT chk_learning_goal_review CHECK (review_status IN ('NOT_REVIEWED','NEEDS_REVIEW','APPROVED','REJECTED'))
);

CREATE TABLE IF NOT EXISTS learning_topic (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    goal_id BIGINT UNSIGNED NOT NULL,
    parent_topic_id BIGINT UNSIGNED NULL,
    topic_code VARCHAR(100) NOT NULL,
    title_vi VARCHAR(255) NOT NULL,
    title_ja VARCHAR(255) NULL,
    topic_order INT UNSIGNED NOT NULL,
    -- [JLAS-W0108] 2026-10-19: approximate JLPT level of a communication dialogue situation (N5..N1);
    -- NULL for vocabulary topics. Used only to order/label situations easy -> hard, never to lock them.
    jlpt_level VARCHAR(10) NULL,
    description TEXT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    external_key VARCHAR(100) NULL,
    source_code VARCHAR(100) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REVIEWED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_topic_goal_code (goal_id, topic_code),
    UNIQUE KEY uk_learning_topic_external_key (external_key),
    CONSTRAINT fk_learning_topic_goal FOREIGN KEY (goal_id) REFERENCES learning_goal(id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_topic_parent FOREIGN KEY (parent_topic_id) REFERENCES learning_topic(id) ON DELETE RESTRICT,
    CONSTRAINT chk_learning_topic_review CHECK (review_status IN ('NOT_REVIEWED','NEEDS_REVIEW','APPROVED','REJECTED'))
);

CREATE TABLE IF NOT EXISTS learning_competency (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    goal_id BIGINT UNSIGNED NOT NULL,
    competency_code VARCHAR(100) NOT NULL,
    display_name_vi VARCHAR(255) NOT NULL,
    description TEXT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    external_key VARCHAR(100) NULL,
    source_code VARCHAR(100) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REVIEWED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_competency_goal_code (goal_id, competency_code),
    UNIQUE KEY uk_learning_competency_external_key (external_key),
    CONSTRAINT fk_learning_competency_goal FOREIGN KEY (goal_id) REFERENCES learning_goal(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_competency_review CHECK (review_status IN ('NOT_REVIEWED','NEEDS_REVIEW','APPROVED','REJECTED'))
);

CREATE TABLE IF NOT EXISTS competency_level (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    level_code VARCHAR(50) NOT NULL,
    level_order INT UNSIGNED NOT NULL,
    display_name_vi VARCHAR(255) NOT NULL,
    description_vi TEXT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_competency_level_code (level_code),
    UNIQUE KEY uk_competency_level_order (level_order),
    CONSTRAINT chk_competency_level_code CHECK
        (level_code IN ('BASIC','FUNCTIONAL','PROBLEM_SOLVING','FLEXIBLE'))
);

CREATE TABLE IF NOT EXISTS learning_topic_level (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    topic_id BIGINT UNSIGNED NOT NULL,
    competency_level_id BIGINT UNSIGNED NOT NULL,
    outcome_vi TEXT NOT NULL,
    recommended_jlpt_level VARCHAR(10) NULL,
    external_key VARCHAR(100) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REVIEWED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_competency_level (topic_id, competency_level_id),
    UNIQUE KEY uk_topic_level_external_key (external_key),
    CONSTRAINT fk_topic_level_topic FOREIGN KEY (topic_id) REFERENCES learning_topic(id) ON DELETE CASCADE,
    CONSTRAINT fk_topic_level_level FOREIGN KEY (competency_level_id) REFERENCES competency_level(id) ON DELETE RESTRICT,
    CONSTRAINT chk_topic_level_jlpt CHECK (recommended_jlpt_level IS NULL OR recommended_jlpt_level IN ('N5','N4','N3','N2','N1')),
    CONSTRAINT chk_topic_level_review CHECK (review_status IN ('NOT_REVIEWED','NEEDS_REVIEW','APPROVED','REJECTED'))
);

CREATE TABLE IF NOT EXISTS lesson_learning_scope (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lesson_id BIGINT UNSIGNED NOT NULL,
    goal_id BIGINT UNSIGNED NOT NULL,
    topic_id BIGINT UNSIGNED NULL,
    competency_id BIGINT UNSIGNED NULL,
    competency_level_id BIGINT UNSIGNED NULL,
    learning_mode VARCHAR(30) NULL,
    required BOOLEAN NOT NULL DEFAULT TRUE,
    scope_order INT UNSIGNED NOT NULL,
    external_key VARCHAR(100) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REVIEWED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lesson_scope_external_key (external_key),
    KEY idx_lesson_scope_selection (goal_id, topic_id, competency_id, competency_level_id, scope_order),
    CONSTRAINT fk_lesson_scope_lesson FOREIGN KEY (lesson_id) REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_scope_goal FOREIGN KEY (goal_id) REFERENCES learning_goal(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_scope_topic FOREIGN KEY (topic_id) REFERENCES learning_topic(id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_scope_competency FOREIGN KEY (competency_id) REFERENCES learning_competency(id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_scope_level FOREIGN KEY (competency_level_id) REFERENCES competency_level(id) ON DELETE RESTRICT,
    CONSTRAINT chk_lesson_scope_target CHECK (topic_id IS NOT NULL OR competency_id IS NOT NULL),
    CONSTRAINT chk_lesson_scope_mode CHECK (learning_mode IS NULL OR learning_mode IN ('FOUNDATION_FIRST','COMMUNICATION_FIRST','BALANCED')),
    CONSTRAINT chk_lesson_scope_review CHECK (review_status IN ('NOT_REVIEWED','NEEDS_REVIEW','APPROVED','REJECTED'))
);

CREATE TABLE IF NOT EXISTS lesson_prerequisite (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    prerequisite_code VARCHAR(100) NOT NULL,
    lesson_id BIGINT UNSIGNED NOT NULL,
    prerequisite_type VARCHAR(40) NOT NULL,
    required_lesson_id BIGINT UNSIGNED NULL,
    required_jlpt_level VARCHAR(10) NULL,
    required_competency_id BIGINT UNSIGNED NULL,
    blocking_access BOOLEAN NOT NULL DEFAULT FALSE,
    warmup_lesson_id BIGINT UNSIGNED NULL,
    reason_vi TEXT NULL,
    external_key VARCHAR(100) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REVIEWED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lesson_prerequisite_code (prerequisite_code),
    UNIQUE KEY uk_lesson_prerequisite_external_key (external_key),
    CONSTRAINT fk_lesson_prerequisite_lesson FOREIGN KEY (lesson_id) REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_prerequisite_required_lesson FOREIGN KEY (required_lesson_id) REFERENCES lesson(id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_prerequisite_competency FOREIGN KEY (required_competency_id) REFERENCES learning_competency(id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_prerequisite_warmup FOREIGN KEY (warmup_lesson_id) REFERENCES lesson(id) ON DELETE RESTRICT,
    CONSTRAINT chk_lesson_prerequisite_type CHECK (prerequisite_type IN ('HARD_SEQUENCE','RECOMMENDED_FOUNDATION','JIT_WARMUP','OPTIONAL_ENRICHMENT')),
    CONSTRAINT chk_lesson_prerequisite_jlpt CHECK (required_jlpt_level IS NULL OR required_jlpt_level IN ('N5','N4','N3','N2','N1')),
    CONSTRAINT chk_lesson_prerequisite_review CHECK (review_status IN ('NOT_REVIEWED','NEEDS_REVIEW','APPROVED','REJECTED'))
);

CREATE TABLE IF NOT EXISTS learning_survey_question (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    goal_id BIGINT UNSIGNED NOT NULL,
    survey_question_code VARCHAR(100) NOT NULL,
    question_order INT UNSIGNED NOT NULL,
    question_vi TEXT NOT NULL,
    selection_type VARCHAR(20) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    external_key VARCHAR(100) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REVIEWED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_survey_question_code (survey_question_code),
    UNIQUE KEY uk_survey_question_external_key (external_key),
    UNIQUE KEY uk_survey_question_order (goal_id, question_order),
    CONSTRAINT fk_survey_question_goal FOREIGN KEY (goal_id) REFERENCES learning_goal(id) ON DELETE CASCADE,
    CONSTRAINT chk_survey_selection_type CHECK (selection_type IN ('SINGLE','MULTIPLE')),
    CONSTRAINT chk_survey_question_review CHECK (review_status IN ('NOT_REVIEWED','NEEDS_REVIEW','APPROVED','REJECTED'))
);

CREATE TABLE IF NOT EXISTS learning_survey_option (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    question_id BIGINT UNSIGNED NOT NULL,
    survey_option_code VARCHAR(100) NOT NULL,
    option_order INT UNSIGNED NOT NULL,
    label_vi VARCHAR(255) NOT NULL,
    value_code VARCHAR(100) NOT NULL,
    topic_id BIGINT UNSIGNED NULL,
    competency_id BIGINT UNSIGNED NULL,
    learning_mode VARCHAR(30) NULL,
    external_key VARCHAR(100) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REVIEWED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_survey_option_code (survey_option_code),
    UNIQUE KEY uk_survey_option_external_key (external_key),
    UNIQUE KEY uk_survey_option_order (question_id, option_order),
    CONSTRAINT fk_survey_option_question FOREIGN KEY (question_id) REFERENCES learning_survey_question(id) ON DELETE CASCADE,
    CONSTRAINT fk_survey_option_topic FOREIGN KEY (topic_id) REFERENCES learning_topic(id) ON DELETE RESTRICT,
    CONSTRAINT fk_survey_option_competency FOREIGN KEY (competency_id) REFERENCES learning_competency(id) ON DELETE RESTRICT,
    CONSTRAINT chk_survey_option_mode CHECK (learning_mode IS NULL OR learning_mode IN ('FOUNDATION_FIRST','COMMUNICATION_FIRST','BALANCED')),
    CONSTRAINT chk_survey_option_review CHECK (review_status IN ('NOT_REVIEWED','NEEDS_REVIEW','APPROVED','REJECTED'))
);

CREATE TABLE IF NOT EXISTS user_goal_survey_submission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    goal_id BIGINT UNSIGNED NOT NULL,
    learning_mode VARCHAR(30) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    version INT UNSIGNED NOT NULL DEFAULT 1,
    submitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_goal_survey_user_version (user_id, goal_id, version),
    CONSTRAINT fk_goal_survey_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_goal_survey_goal FOREIGN KEY (goal_id) REFERENCES learning_goal(id) ON DELETE RESTRICT,
    CONSTRAINT chk_goal_survey_mode CHECK (learning_mode IS NULL OR learning_mode IN ('FOUNDATION_FIRST','COMMUNICATION_FIRST','BALANCED')),
    CONSTRAINT chk_goal_survey_status CHECK (status IN ('DRAFT','SUBMITTED','SUPERSEDED'))
);

CREATE TABLE IF NOT EXISTS user_goal_survey_selection (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    submission_id BIGINT UNSIGNED NOT NULL,
    question_id BIGINT UNSIGNED NOT NULL,
    option_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_goal_survey_selection (submission_id, question_id, option_id),
    CONSTRAINT fk_goal_selection_submission FOREIGN KEY (submission_id) REFERENCES user_goal_survey_submission(id) ON DELETE CASCADE,
    CONSTRAINT fk_goal_selection_question FOREIGN KEY (question_id) REFERENCES learning_survey_question(id) ON DELETE RESTRICT,
    CONSTRAINT fk_goal_selection_option FOREIGN KEY (option_id) REFERENCES learning_survey_option(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS assessment_question_usage (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    question_id BIGINT UNSIGNED NOT NULL,
    usage_code VARCHAR(40) NOT NULL,
    blueprint_code VARCHAR(100) NULL,
    section_code VARCHAR(100) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from DATETIME(6) NULL,
    effective_to DATETIME(6) NULL,
    external_key VARCHAR(100) NOT NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REVIEWED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_assessment_usage_external_key (external_key),
    KEY idx_assessment_usage_selection (usage_code, blueprint_code, section_code, active),
    CONSTRAINT fk_assessment_usage_question FOREIGN KEY (question_id) REFERENCES assessment_question(id) ON DELETE CASCADE,
    CONSTRAINT chk_assessment_usage_code CHECK (usage_code IN ('JLPT_PLACEMENT','JLPT_MOCK_TEST','BRSE_FOUNDATION')),
    CONSTRAINT chk_assessment_usage_dates CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from),
    CONSTRAINT chk_assessment_usage_review CHECK (review_status IN ('NOT_REVIEWED','NEEDS_REVIEW','APPROVED','REJECTED'))
);

-- [JLAS-W0193] Re-runnable live-DB addition for server-verified JLPT Mock Test results.
CREATE TABLE IF NOT EXISTS jlpt_mock_attempt (
    attempt_id BIGINT UNSIGNED NOT NULL,
    exam_code VARCHAR(30) NOT NULL,
    jlpt_level VARCHAR(10) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    correct_count INT UNSIGNED NOT NULL,
    question_count INT UNSIGNED NOT NULL,
    score_percent DECIMAL(5,2) NOT NULL,
    passed TINYINT(1) NOT NULL,
    section_results_json JSON NOT NULL,
    answers_json JSON NOT NULL,
    PRIMARY KEY (attempt_id),
    KEY idx_jlpt_mock_exam (exam_code, jlpt_level),
    CONSTRAINT fk_jlpt_mock_attempt_attempt FOREIGN KEY (attempt_id)
        REFERENCES assessment_attempt(id) ON DELETE CASCADE,
    CONSTRAINT fk_jlpt_mock_attempt_exam FOREIGN KEY (exam_code)
        REFERENCES jlpt_exam(exam_code),
    CONSTRAINT chk_jlpt_mock_level CHECK (jlpt_level IN ('N1','N2','N3','N4','N5')),
    CONSTRAINT chk_jlpt_mock_score CHECK (score_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_jlpt_mock_counts CHECK (correct_count <= question_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0114] AI draft questions are isolated from scored Learning/Assessment banks.
-- Additive and re-runnable on MySQL 8. Select the intended database before executing.
CREATE TABLE IF NOT EXISTS learning_practice_draft_question (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    draft_code VARCHAR(100) NOT NULL,
    lesson_source_code VARCHAR(100) NOT NULL,
    goal_code VARCHAR(20) NOT NULL,
    jlpt_level VARCHAR(10) NULL,
    grammar_code VARCHAR(64) NOT NULL,
    question_type VARCHAR(16) NOT NULL,
    source_status VARCHAR(20) NOT NULL DEFAULT 'AI_DRAFT',
    review_status VARCHAR(30) NOT NULL DEFAULT 'NEEDS_HUMAN_REVIEW',
    payload_json JSON NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_practice_draft_code (draft_code),
    KEY idx_learning_practice_draft_lesson (lesson_source_code, review_status),
    CONSTRAINT chk_learning_practice_draft_goal
        CHECK (goal_code IN ('JLPT','BA','TRAVEL','DAILY_LIFE')),
    CONSTRAINT chk_learning_practice_draft_type
        CHECK (question_type IN ('MCQ','FILL','ORDER')),
    CONSTRAINT chk_learning_practice_draft_status
        CHECK (source_status = 'AI_DRAFT' AND review_status = 'NEEDS_HUMAN_REVIEW')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0108] Additive task-owned holding area. Does not publish lessons,
-- alter source-team textbooks, or write to any scored question bank.
CREATE TABLE IF NOT EXISTS learning_owned_source_draft (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_pack VARCHAR(64) NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    item_type VARCHAR(20) NOT NULL,
    goal VARCHAR(20) NOT NULL,
    level VARCHAR(10) NULL,
    part_code VARCHAR(100) NOT NULL,
    item_order INT UNSIGNED NOT NULL,
    audio_state VARCHAR(50) NOT NULL,
    review_status VARCHAR(30) NOT NULL DEFAULT 'NEEDS_HUMAN_REVIEW',
    source_json_sha256 CHAR(64) NOT NULL,
    source_payload JSON NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_owned_draft_source (source_pack, source_id),
    KEY idx_learning_owned_draft_part (part_code, item_order),
    CONSTRAINT chk_learning_owned_draft_type
        CHECK (item_type IN ('VOCABULARY','GRAMMAR','KANJI','DIALOGUE')),
    CONSTRAINT chk_learning_owned_draft_goal
        CHECK (goal IN ('JLPT','BA','TRAVEL','DAILY_LIFE')),
    CONSTRAINT chk_learning_owned_draft_review
        CHECK (review_status = 'NEEDS_HUMAN_REVIEW')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- [JLAS-W0108] Smart Test per-session summary (assessment_answer is the shared answer table).
CREATE OR REPLACE VIEW v_smart_test_session_summary AS
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

-- =============================================================================
-- PART B. Guarded in-place upgrades for databases whose Learning tables are older than PART A.
-- Each step reads information_schema first and does nothing when the object is already in its final shape.
-- Former sources: migrations 004 (pre-V2 -> V2), 005, 006, 007, 008, 009, 010, 011, 012, 014, 015, 022 and the
-- Learning CHECKs of the shared 020. Helper procedures are temporary and dropped at the end of this part.
-- =============================================================================

DELIMITER //
DROP PROCEDURE IF EXISTS jlas_ls_exec//
CREATE PROCEDURE jlas_ls_exec(IN p_sql TEXT)
BEGIN
    SET @jlas_ls_sql = p_sql;
    PREPARE jlas_ls_stmt FROM @jlas_ls_sql;
    EXECUTE jlas_ls_stmt;
    DEALLOCATE PREPARE jlas_ls_stmt;
END//

DROP PROCEDURE IF EXISTS jlas_ls_add_column//
CREATE PROCEDURE jlas_ls_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition TEXT)
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND TABLE_TYPE = 'BASE TABLE')
       AND NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column) THEN
        CALL jlas_ls_exec(CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition));
    END IF;
END//

-- Re-defines a column only when its type, nullability or (when given) comment differs from the final shape.
DROP PROCEDURE IF EXISTS jlas_ls_modify_column//
CREATE PROCEDURE jlas_ls_modify_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_type VARCHAR(100),
                                       IN p_nullable VARCHAR(3), IN p_comment TEXT, IN p_definition TEXT)
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
                 AND (COLUMN_TYPE <> p_type OR IS_NULLABLE <> p_nullable
                      OR (p_comment IS NOT NULL AND COLUMN_COMMENT <> p_comment))) THEN
        CALL jlas_ls_exec(CONCAT('ALTER TABLE `', p_table, '` MODIFY COLUMN `', p_column, '` ', p_definition));
    END IF;
END//

DROP PROCEDURE IF EXISTS jlas_ls_add_index//
CREATE PROCEDURE jlas_ls_add_index(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_definition TEXT)
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND TABLE_TYPE = 'BASE TABLE')
       AND NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index) THEN
        CALL jlas_ls_exec(CONCAT('ALTER TABLE `', p_table, '` ADD ', p_definition));
    END IF;
END//

DROP PROCEDURE IF EXISTS jlas_ls_drop_index//
CREATE PROCEDURE jlas_ls_drop_index(IN p_table VARCHAR(64), IN p_index VARCHAR(64))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index) THEN
        CALL jlas_ls_exec(CONCAT('ALTER TABLE `', p_table, '` DROP INDEX `', p_index, '`'));
    END IF;
END//

DROP PROCEDURE IF EXISTS jlas_ls_add_fk//
CREATE PROCEDURE jlas_ls_add_fk(IN p_table VARCHAR(64), IN p_fk VARCHAR(64), IN p_definition TEXT)
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND TABLE_TYPE = 'BASE TABLE')
       AND NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                       WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = p_table
                         AND CONSTRAINT_NAME = p_fk AND CONSTRAINT_TYPE = 'FOREIGN KEY') THEN
        CALL jlas_ls_exec(CONCAT('ALTER TABLE `', p_table, '` ADD CONSTRAINT `', p_fk, '` ', p_definition));
    END IF;
END//

-- Installs a CHECK, or replaces it when its clause lacks p_marker (a value only the final clause has).
-- Every replacement in this file only widens the allowed set, so existing rows stay valid.
-- p_skip_like (optional): leave the CHECK alone when its clause matches it (another file owns that step).
DROP PROCEDURE IF EXISTS jlas_ls_check//
CREATE PROCEDURE jlas_ls_check(IN p_table VARCHAR(64), IN p_check VARCHAR(64), IN p_marker VARCHAR(100),
                               IN p_skip_like VARCHAR(100), IN p_expression TEXT)
BEGIN
    DECLARE v_clause LONGTEXT;
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND TABLE_TYPE = 'BASE TABLE') THEN
        SET v_clause = (SELECT cc.CHECK_CLAUSE
                        FROM information_schema.CHECK_CONSTRAINTS cc
                        JOIN information_schema.TABLE_CONSTRAINTS tc
                          ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
                        WHERE cc.CONSTRAINT_SCHEMA = DATABASE() AND tc.TABLE_NAME = p_table
                          AND tc.CONSTRAINT_TYPE = 'CHECK' AND cc.CONSTRAINT_NAME = p_check
                        LIMIT 1);
        IF (v_clause IS NULL OR LOCATE(p_marker, v_clause) = 0)
           AND NOT (v_clause IS NOT NULL AND p_skip_like IS NOT NULL AND v_clause LIKE p_skip_like) THEN
            IF v_clause IS NOT NULL THEN
                CALL jlas_ls_exec(CONCAT('ALTER TABLE `', p_table, '` DROP CHECK `', p_check, '`'));
            END IF;
            CALL jlas_ls_exec(CONCAT('ALTER TABLE `', p_table, '` ADD CONSTRAINT `', p_check, '` CHECK (', p_expression, ')'));
        END IF;
    END IF;
END//
DELIMITER ;

-- --- B1. Learning-owned columns and CHECKs on shared tables ------------------------------------------
-- [JLAS-W0108] (former 008) stable nullable curriculum import keys keep the Learning seed re-runnable.
CALL jlas_ls_add_column('textbook', 'source_code', 'VARCHAR(64) NULL AFTER id');
CALL jlas_ls_add_index('textbook', 'uk_textbook_source_code', 'UNIQUE KEY uk_textbook_source_code (source_code)');
CALL jlas_ls_add_column('unit', 'source_code', 'VARCHAR(64) NULL AFTER textbook_id');
CALL jlas_ls_add_index('unit', 'uk_unit_source_code', 'UNIQUE KEY uk_unit_source_code (textbook_id, source_code)');
CALL jlas_ls_add_column('lesson', 'source_code', 'VARCHAR(64) NULL AFTER unit_id');
CALL jlas_ls_add_index('lesson', 'uk_lesson_source_code', 'UNIQUE KEY uk_lesson_source_code (unit_id, source_code)');
CALL jlas_ls_add_column('assessment_question', 'source_code', 'VARCHAR(100) NULL AFTER id');
CALL jlas_ls_add_index('assessment_question', 'uk_assessment_question_source_code',
    'UNIQUE KEY uk_assessment_question_source_code (source_code)');
-- [JLAS-W0108] (former 005) is_learning_path_source = 1 marks the textbooks the path composer may use.
-- The flag values themselves are data (learning_data_common.sql); this only adds the column.
CALL jlas_ls_add_column('textbook', 'is_learning_path_source', 'TINYINT(1) NOT NULL DEFAULT 0 AFTER version');
CALL jlas_ls_add_index('textbook', 'idx_textbook_learning_path_source',
    'KEY idx_textbook_learning_path_source (is_learning_path_source, jlpt_level, status)');
-- [JLAS-W0120] (former 022 learning_vocabulary_context) contextual vocabulary display for Learning lessons.
CALL jlas_ls_add_column('content_vocabulary', 'learning_meaning_vi', 'TEXT NULL AFTER vocabulary_id');
CALL jlas_ls_add_column('content_vocabulary', 'learning_example_ja', 'TEXT NULL AFTER learning_meaning_vi');
CALL jlas_ls_add_column('content_vocabulary', 'learning_example_vi', 'TEXT NULL AFTER learning_example_ja');
-- [JLAS-W0108] goal Daily Life: communication lessons carry situation_tag DAILY_LIFE. Live databases already have this
-- CHECK; it was never in a tracked file. A clause that still allows WORK is left to sql/migrations/019 (WORK -> BA).
CALL jlas_ls_check('lesson', 'chk_lesson_situation_tag', 'DAILY_LIFE', '%WORK%',
    'situation_tag IS NULL OR situation_tag IN (''TRAVEL'',''BA'',''FOUNDATION'',''DAILY_LIFE'')');

-- --- B2. Learning survey -----------------------------------------------------------------------------
-- [JLAS-W0108] F1-3 / F1-5 (owner 2026-10-27/28): optional JLPT exam date and up to two focus skills.
CALL jlas_ls_add_column('user_learning_survey', 'exam_date', 'DATE NULL AFTER daily_study_minutes');
CALL jlas_ls_add_column('user_learning_survey', 'focus_skills', 'VARCHAR(40) NULL AFTER exam_date');

-- --- B3. user_learning_path --------------------------------------------------------------------------
CALL jlas_ls_add_column('user_learning_path', 'experience_version',
    'VARCHAR(30) NOT NULL DEFAULT ''LEGACY_V1'' AFTER id');
CALL jlas_ls_add_column('user_learning_path', 'learning_mode', 'VARCHAR(30) NULL AFTER goal');
CALL jlas_ls_add_column('user_learning_path', 'completed_at', 'DATETIME(6) NULL AFTER gate_passed_at');
CALL jlas_ls_add_column('user_learning_path', 'completion_attempt_id', 'BIGINT UNSIGNED NULL AFTER completed_at');
CALL jlas_ls_add_column('user_learning_path', 'warm_up_flashcard_version', 'TEXT NULL AFTER warm_up_flashcard_set_id');
CALL jlas_ls_check('user_learning_path', 'chk_learning_path_experience', 'INTERACTIVE_V2', NULL,
    'experience_version IN (''LEGACY_V1'',''INTERACTIVE_V2'')');
CALL jlas_ls_check('user_learning_path', 'chk_learning_path_goal', 'DAILY_LIFE', NULL,
    'goal IS NULL OR goal IN (''JLPT'',''TRAVEL'',''DAILY_LIFE'',''BA'',''FROM_ZERO'')');
CALL jlas_ls_check('user_learning_path', 'chk_learning_path_mode', 'BALANCED', NULL,
    'learning_mode IS NULL OR learning_mode IN (''FOUNDATION_FIRST'',''COMMUNICATION_FIRST'',''BALANCED'')');
CALL jlas_ls_check('user_learning_path', 'chk_learning_path_status', 'COMPLETED', NULL,
    'status IN (''ACTIVE'',''SUPERSEDED'',''COMPLETED'')');

-- --- B4. learning_path_package (day packages; former 014, and 022 (ex-052)) --------------------------------
-- [JLAS-W0193] one curriculum lesson may continue over ordered day segments; the new uniqueness is installed before
-- the old one is removed.
CALL jlas_ls_add_column('learning_path_package', 'source_segment_index',
    'INT UNSIGNED NOT NULL DEFAULT 1 AFTER source_lesson_id');
CALL jlas_ls_add_index('learning_path_package', 'uk_learning_package_path_source_segment',
    'UNIQUE KEY uk_learning_package_path_source_segment (learning_path_id, source_lesson_id, source_segment_index)');
CALL jlas_ls_drop_index('learning_path_package', 'uk_learning_package_path_source');
-- [JLAS-W0108] D3-05 (owner 2026-10-24): two packages of one path may share a day_number (minutes-based days).
CALL jlas_ls_add_index('learning_path_package', 'idx_learning_package_path_day',
    'KEY idx_learning_package_path_day (learning_path_id, day_number)');
CALL jlas_ls_drop_index('learning_path_package', 'uk_learning_package_path_day');

-- --- B5. user_learning_path_item ---------------------------------------------------------------------
-- [JLAS-W0108] (former 004 P2) study/assessment progress independent of the source lesson. Items of a pre-V2 database
-- that were completed through lesson_progress start as STUDIED (only when the column is added by this run).
SET @jlas_ls_backfill_study = (
    EXISTS (SELECT 1 FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path_item')
    AND NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_learning_path_item'
                      AND COLUMN_NAME = 'study_status'));
CALL jlas_ls_add_column('user_learning_path_item', 'study_status',
    'VARCHAR(20) NOT NULL DEFAULT ''NOT_STARTED'' AFTER matched_tag_codes');
CALL jlas_ls_add_column('user_learning_path_item', 'assessment_status',
    'VARCHAR(20) NOT NULL DEFAULT ''NOT_READY'' AFTER study_status');
CALL jlas_ls_exec(IF(@jlas_ls_backfill_study,
    'UPDATE user_learning_path_item i JOIN lesson_progress p ON p.lesson_id = i.lesson_id AND p.status = ''COMPLETED'' SET i.study_status = ''STUDIED'' WHERE i.study_status = ''NOT_STARTED''',
    'DO 0'));
-- (former 004 P2c/P6, V2) an activity may anchor to grammar/vocabulary/content instead of a lesson; stages; packages.
CALL jlas_ls_modify_column('user_learning_path_item', 'lesson_id', 'bigint unsigned', 'YES', NULL, 'BIGINT UNSIGNED NULL');
CALL jlas_ls_add_column('user_learning_path_item', 'stage_id', 'BIGINT UNSIGNED NULL AFTER order_index');
CALL jlas_ls_add_column('user_learning_path_item', 'package_id', 'BIGINT UNSIGNED NULL AFTER learning_path_id');
CALL jlas_ls_add_column('user_learning_path_item', 'activity_type', 'VARCHAR(20) NULL AFTER package_id');
CALL jlas_ls_add_column('user_learning_path_item', 'activity_state', 'VARCHAR(20) NULL AFTER activity_type');
-- [JLAS-W0125] (former 015) one activity may be split into ordered parts within a stable package.
CALL jlas_ls_add_column('user_learning_path_item', 'part_index', 'INT UNSIGNED NOT NULL DEFAULT 1 AFTER activity_state');
CALL jlas_ls_add_index('user_learning_path_item', 'uk_learning_path_item_package_part',
    'UNIQUE KEY uk_learning_path_item_package_part (package_id, activity_type, part_index)');
CALL jlas_ls_drop_index('user_learning_path_item', 'uk_learning_path_item_package_type');
CALL jlas_ls_add_index('user_learning_path_item', 'idx_learning_path_item_package_order',
    'KEY idx_learning_path_item_package_order (package_id, order_index)');
CALL jlas_ls_add_fk('user_learning_path_item', 'fk_learning_path_item_package',
    'FOREIGN KEY (package_id) REFERENCES learning_path_package(id) ON DELETE CASCADE');
CALL jlas_ls_check('user_learning_path_item', 'chk_learning_path_item_study_status', 'STUDIED', NULL,
    'study_status IN (''NOT_STARTED'',''IN_PROGRESS'',''STUDIED'')');
CALL jlas_ls_check('user_learning_path_item', 'chk_learning_path_item_assessment_status', 'NEEDS_REVIEW', NULL,
    'assessment_status IN (''NOT_READY'',''READY'',''ISSUED'',''SUBMITTED'',''PASSED'',''NEEDS_REVIEW'')');
-- [JLAS-W0108] (former 006) FOUNDATION = kana alphabet activity, only in the WARM_UP package.
CALL jlas_ls_check('user_learning_path_item', 'chk_learning_path_item_activity_type', 'FOUNDATION', NULL,
    'activity_type IS NULL OR activity_type IN (''VOCABULARY'',''KANJI'',''GRAMMAR'',''DIALOGUE'',''READING'',''LISTENING'',''FOUNDATION'')');
CALL jlas_ls_check('user_learning_path_item', 'chk_learning_path_item_activity_state', 'COOLDOWN', NULL,
    'activity_state IS NULL OR activity_state IN (''LOCKED'',''STUDYING'',''CHECK_READY'',''PASSED'',''COOLDOWN'',''COMPLETED'')');
CALL jlas_ls_check('user_learning_path_item', 'chk_learning_path_item_kind', 'is null', NULL,
    'kind IS NULL OR kind IN (''CURRICULUM'',''REVIEW'')');

-- --- B6. learning_activity_member --------------------------------------------------------------------
CALL jlas_ls_add_column('learning_activity_member', 'content_snapshot_json', 'TEXT NULL AFTER source_revision');
CALL jlas_ls_check('learning_activity_member', 'chk_learning_activity_member_type', 'FOUNDATION_CHARACTER', NULL,
    'member_type IN (''VOCABULARY_ENTRY'',''KANJI'',''GRAMMAR_POINT'',''DIALOGUE_LINE'',''COMPREHENSION_QUESTION'',''FOUNDATION_CHARACTER'')');

-- --- B7. user_learning_stage (former 007 and 012: FOUNDATION and communication stage levels) -------------------
CALL jlas_ls_modify_column('user_learning_stage', 'level', 'varchar(30)', 'NO', NULL, 'VARCHAR(30) NOT NULL');
CALL jlas_ls_check('user_learning_stage', 'chk_learning_stage_level', 'FLEXIBLE', NULL,
    'level IN (''FOUNDATION'',''N5'',''N4'',''N3'',''N2'',''N1'',''BASIC'',''FUNCTIONAL'',''PROBLEM_SOLVING'',''FLEXIBLE'')');

-- --- B8. learning_quiz_session -----------------------------------------------------------------------
-- (former 004 P4, 009, 011, 022) activity anchor, micro-check batch, frozen Reading/Listening source, autosave draft,
-- quiz_type widened to 30 characters, EXTRA_PRACTICE (§2.32 T4).
CALL jlas_ls_add_column('learning_quiz_session', 'activity_id',
    'BIGINT UNSIGNED NULL COMMENT ''[JLAS-W0108] grammarPointId cho GRAMMAR quiz; null cho loai khac'' AFTER path_id');
CALL jlas_ls_add_column('learning_quiz_session', 'batch_index',
    'INT UNSIGNED NULL COMMENT ''[JLAS-W0193] thu tu batch micro-check; null cho quiz legacy'' AFTER activity_id');
CALL jlas_ls_add_column('learning_quiz_session', 'source_snapshot_json', 'TEXT NULL AFTER answer_key_json');
CALL jlas_ls_add_column('learning_quiz_session', 'draft_answers_json', 'TEXT NULL AFTER source_snapshot_json');
CALL jlas_ls_modify_column('learning_quiz_session', 'quiz_type', 'varchar(30)', 'NO',
    'GATE/LESSON/GRAMMAR/FLASHCARD/*_MICRO_CHECK/PRACTICE_REPLAY',
    'VARCHAR(30) NOT NULL COMMENT ''GATE/LESSON/GRAMMAR/FLASHCARD/*_MICRO_CHECK/PRACTICE_REPLAY''');
CALL jlas_ls_modify_column('learning_quiz_session', 'lesson_id', 'bigint unsigned', 'YES', 'null cho GATE/GRAMMAR/FLASHCARD',
    'BIGINT UNSIGNED NULL COMMENT ''null cho GATE/GRAMMAR/FLASHCARD''');
CALL jlas_ls_modify_column('learning_quiz_session', 'path_id', 'bigint unsigned', 'YES', 'null cho LESSON/GRAMMAR',
    'BIGINT UNSIGNED NULL COMMENT ''null cho LESSON/GRAMMAR''');
CALL jlas_ls_check('learning_quiz_session', 'chk_quiz_session_type', 'EXTRA_PRACTICE', NULL,
    'quiz_type IN (''GATE'',''LESSON'',''GRAMMAR'',''FLASHCARD'',''VOCAB_MICRO_CHECK'',''KANJI_MICRO_CHECK'',''GRAMMAR_MICRO_CHECK'',''READING_MICRO_CHECK'',''LISTENING_MICRO_CHECK'',''FOUNDATION_MICRO_CHECK'',''PRACTICE_REPLAY'',''EXTRA_PRACTICE'')');

-- --- B9. Other Learning tables -----------------------------------------------------------------------
CALL jlas_ls_modify_column('learning_generated_question', 'correct_option', 'int', 'NO', NULL, 'INT NOT NULL');
-- [JLAS-W0108] (also in shared 020) Smart Test reports KANJI weaknesses.
CALL jlas_ls_check('user_knowledge_weakness', 'chk_weakness_skill', 'KANJI', NULL,
    'skill IS NULL OR skill IN (''VOCABULARY'',''GRAMMAR'',''READING'',''LISTENING'',''SPEAKING'',''ONYOMI'',''KUNYOMI'',''KANJI'')');
-- [JLAS-W0108] 2026-10-19 (owner decision): communication situations carry an approximate JLPT level.
CALL jlas_ls_add_column('learning_topic', 'jlpt_level', 'VARCHAR(10) NULL AFTER topic_order');

-- jlas_ls_exec, jlas_ls_modify_column, jlas_ls_add_column and jlas_ls_check stay until the end of PART C (jpath
-- upgrades use them).
DROP PROCEDURE IF EXISTS jlas_ls_add_fk;
DROP PROCEDURE IF EXISTS jlas_ls_drop_index;
DROP PROCEDURE IF EXISTS jlas_ls_add_index;

-- =============================================================================
-- PART C. jpath (JLPT path v2, Plan 2): the 27 jpath_* tables as CREATE TABLE IF NOT EXISTS (needs users).
-- =============================================================================

-- [JLAS-W0108] Lộ trình JLPT theo chủ đề: 27 bảng jpath_*, thứ tự theo hợp đồng dữ liệu của Kế hoạch 2.
-- Chạy lại an toàn (CREATE TABLE IF NOT EXISTS); không đụng bảng cũ. Cột/index/CHECK thêm sau này đi qua helper có
-- kiểm information_schema như PART B, đặt ở cuối phần này.
-- Bảng nội dung (1-12) do seed ghi, ứng dụng chỉ đọc (quản trị Báo sai được sửa). Bảng 13-27 là trạng thái người học.
-- Mọi cột mã là VARCHAR(24). Cờ thường active_flag/open_flag (không dùng cột sinh STORED, vì MySQL 8 không cho FK
-- ON DELETE CASCADE trên cột gốc của cột sinh) + UNIQUE + CHECK bảo đảm một lộ trình ACTIVE / một báo OPEN mỗi người.
-- CHECK cờ dùng <=> (so sánh an toàn với NULL): với "= 1", dòng ACTIVE có cờ NULL cho kết quả UNKNOWN, CHECK vẫn cho qua
-- và UNIQUE không chặn được NULL, nên hai lộ trình ACTIVE cùng người sẽ lọt.
-- Cột SRS của jpath_user_item lưu giờ địa phương của người học; mọi cột thời gian khác lưu giờ server.

-- 1. Cấp JLPT và trạng thái phát hành.
CREATE TABLE IF NOT EXISTS jpath_level (
    level VARCHAR(2) NOT NULL,
    sort_order TINYINT UNSIGNED NOT NULL,
    released TINYINT(1) NOT NULL DEFAULT 0,
    released_at DATETIME(6) NULL,
    seed_version VARCHAR(16) NULL,
    release_note VARCHAR(500) NULL,
    planned_vocab INT UNSIGNED NOT NULL DEFAULT 0,
    planned_kanji INT UNSIGNED NOT NULL DEFAULT 0,
    planned_grammar INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (level),
    CONSTRAINT chk_jpath_level_level CHECK (level IN ('N5','N4','N3','N2','N1'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Chủ đề từ vựng của một cấp ('khac' = "Từ thông dụng khác").
CREATE TABLE IF NOT EXISTS jpath_vocab_topic (
    level VARCHAR(2) NOT NULL,
    topic_key VARCHAR(60) NOT NULL,
    title_vi VARCHAR(120) NOT NULL,
    sort_order SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (level, topic_key),
    CONSTRAINT fk_jpath_vocab_topic_level FOREIGN KEY (level) REFERENCES jpath_level(level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Từ vựng. dictionary_entry_id trỏ mềm sang từ điển (không FK).
CREATE TABLE IF NOT EXISTS jpath_vocabulary (
    code VARCHAR(24) NOT NULL,
    level VARCHAR(2) NOT NULL,
    topic_key VARCHAR(60) NOT NULL,
    sort_order INT UNSIGNED NOT NULL,
    priority INT UNSIGNED NOT NULL,
    tier VARCHAR(10) NOT NULL DEFAULT 'CORE',
    expression VARCHAR(120) NOT NULL,
    reading VARCHAR(120) NOT NULL,
    normalized_expression VARCHAR(120) NOT NULL,
    normalized_reading VARCHAR(120) NOT NULL,
    romaji VARCHAR(160) NULL,
    kana_only TINYINT(1) NOT NULL DEFAULT 0,
    variants_json JSON NULL,
    meaning_vi VARCHAR(255) NULL,
    word_type_label VARCHAR(40) NULL,
    mnemonic_vi VARCHAR(500) NULL,
    dictionary_entry_id BIGINT UNSIGNED NULL,
    audio_path VARCHAR(255) NULL,
    audio_sha256 CHAR(64) NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'DRAFT',
    report_hidden TINYINT(1) NOT NULL DEFAULT 0,
    authored TINYINT(1) NOT NULL DEFAULT 0,
    seed_version VARCHAR(16) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (code),
    KEY idx_jpath_vocab_queue (level, status, sort_order),
    KEY idx_jpath_vocab_topic (level, topic_key, sort_order),
    KEY idx_jpath_vocab_dict (dictionary_entry_id),
    KEY idx_jpath_vocab_norm (normalized_expression, normalized_reading),
    CONSTRAINT fk_jpath_vocabulary_topic FOREIGN KEY (level, topic_key) REFERENCES jpath_vocab_topic(level, topic_key),
    CONSTRAINT chk_jpath_vocabulary_tier CHECK (tier IN ('CORE','EXTENDED')),
    CONSTRAINT chk_jpath_vocabulary_status CHECK (status IN ('REVIEWED','AI_CHECKED','DRAFT','HOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. Kanji. `character` so khớp nhị phân (utf8mb4_bin) để hai chữ khác nhau không bị coi là trùng.
CREATE TABLE IF NOT EXISTS jpath_kanji (
    code VARCHAR(24) NOT NULL,
    level VARCHAR(2) NOT NULL,
    `character` VARCHAR(4) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    sort_order INT UNSIGNED NOT NULL,
    han_viet VARCHAR(40) NULL,
    han_viet_alt_json JSON NULL,
    meanings_vi VARCHAR(255) NULL,
    readings_on_json JSON NULL,
    readings_kun_json JSON NULL,
    level_readings_json JSON NULL,
    -- [JLAS-W0108] Nguồn của level_readings (LEVEL_WORDS, DICTIONARY_WORDS, FIRST_READING…): FIRST_READING là cách đọc
    -- đầu tiên chỉ để hiện trên thẻ, không đem ra hỏi trong bài kiểm cách đọc.
    level_reading_source VARCHAR(20) NULL,
    stroke_count TINYINT UNSIGNED NULL,
    radical_number SMALLINT UNSIGNED NULL,
    radical_name_vi VARCHAR(40) NULL,
    frequency INT UNSIGNED NULL,
    mnemonic_vi VARCHAR(500) NULL,
    dictionary_kanji_id BIGINT UNSIGNED NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'DRAFT',
    report_hidden TINYINT(1) NOT NULL DEFAULT 0,
    authored TINYINT(1) NOT NULL DEFAULT 0,
    seed_version VARCHAR(16) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (code),
    UNIQUE KEY uk_jpath_kanji_char (`character`),
    KEY idx_jpath_kanji_queue (level, status, sort_order),
    KEY idx_jpath_kanji_radical (radical_number),
    CONSTRAINT chk_jpath_kanji_status CHECK (status IN ('REVIEWED','AI_CHECKED','DRAFT','HOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. Từ ghép của một Kanji (tối đa 5, xóa-chèn lại theo cấp khi seed).
CREATE TABLE IF NOT EXISTS jpath_kanji_compound (
    kanji_code VARCHAR(24) NOT NULL,
    vocab_code VARCHAR(24) NOT NULL,
    sort_order TINYINT UNSIGNED NOT NULL,
    PRIMARY KEY (kanji_code, vocab_code),
    CONSTRAINT fk_jpath_kanji_compound_kanji FOREIGN KEY (kanji_code) REFERENCES jpath_kanji(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. Bài (unit) ngữ pháp của một cấp.
CREATE TABLE IF NOT EXISTS jpath_grammar_unit (
    level VARCHAR(2) NOT NULL,
    unit_no SMALLINT UNSIGNED NOT NULL,
    title_vi VARCHAR(120) NULL,
    PRIMARY KEY (level, unit_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. Mẫu ngữ pháp.
CREATE TABLE IF NOT EXISTS jpath_grammar (
    code VARCHAR(24) NOT NULL,
    level VARCHAR(2) NOT NULL,
    unit_no SMALLINT UNSIGNED NOT NULL,
    sort_order INT UNSIGNED NOT NULL,
    priority INT UNSIGNED NOT NULL,
    pattern VARCHAR(120) NOT NULL,
    pattern_variants_json JSON NULL,
    family_key VARCHAR(120) NOT NULL,
    meaning_vi VARCHAR(1000) NULL,
    formation VARCHAR(255) NULL,
    usage_vi TEXT NULL,
    nuance_vi TEXT NULL,
    common_mistakes_vi TEXT NULL,
    related_codes_json JSON NULL,
    -- mẫu phụ: mã mẫu chính nó gắn vào (không học riêng, hiện trong bài mẫu chính); NULL = mẫu chính
    parent_code VARCHAR(24) NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'DRAFT',
    report_hidden TINYINT(1) NOT NULL DEFAULT 0,
    authored TINYINT(1) NOT NULL DEFAULT 0,
    seed_version VARCHAR(16) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (code),
    KEY idx_jpath_grammar_queue (level, status, unit_no, sort_order),
    CONSTRAINT fk_jpath_grammar_unit FOREIGN KEY (level, unit_no) REFERENCES jpath_grammar_unit(level, unit_no),
    CONSTRAINT chk_jpath_grammar_status CHECK (status IN ('REVIEWED','AI_CHECKED','DRAFT','HOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. Câu ví dụ của từ / Kanji / mẫu ngữ pháp (MAIN = ví dụ 1). ja_furigana dạng "父親(ちちおや)は…".
CREATE TABLE IF NOT EXISTS jpath_example (
    code VARCHAR(24) NOT NULL,
    owner_type VARCHAR(8) NOT NULL,
    owner_code VARCHAR(24) NOT NULL,
    level VARCHAR(2) NOT NULL,
    sort_order TINYINT UNSIGNED NOT NULL,
    role VARCHAR(5) NOT NULL,
    ja VARCHAR(255) NOT NULL,
    ja_furigana VARCHAR(600) NULL,
    vi VARCHAR(500) NULL,
    audio_path VARCHAR(255) NULL,
    audio_sha256 CHAR(64) NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'DRAFT',
    report_hidden TINYINT(1) NOT NULL DEFAULT 0,
    authored TINYINT(1) NOT NULL DEFAULT 0,
    seed_version VARCHAR(16) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (code),
    KEY idx_jpath_example_owner (owner_type, owner_code, sort_order),
    CONSTRAINT chk_jpath_example_owner_type CHECK (owner_type IN ('VOCAB','KANJI','GRAMMAR')),
    CONSTRAINT chk_jpath_example_role CHECK (role IN ('MAIN','EXTRA')),
    CONSTRAINT chk_jpath_example_status CHECK (status IN ('REVIEWED','AI_CHECKED','DRAFT','HOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. Câu hỏi ngữ pháp. Đáp án (answer_index, answer_order_json, answer_text, accept_json) không bao giờ ra API trước khi
-- câu bị khóa. accept_json (FILL_TEXT): mảng đáp án đồng nghĩa cũng được chấm đúng; NULL khi không có.
CREATE TABLE IF NOT EXISTS jpath_grammar_question (
    code VARCHAR(24) NOT NULL,
    grammar_code VARCHAR(24) NOT NULL,
    level VARCHAR(2) NOT NULL,
    bank VARCHAR(6) NOT NULL,
    question_type VARCHAR(12) NOT NULL,
    sort_order TINYINT UNSIGNED NOT NULL,
    prompt_ja VARCHAR(500) NOT NULL,
    prompt_furigana VARCHAR(800) NULL,
    options_json JSON NULL,
    answer_index TINYINT UNSIGNED NULL,
    pieces_json JSON NULL,
    answer_order_json JSON NULL,
    answer_text VARCHAR(255) NULL,
    accept_json JSON NULL,
    explanation_vi VARCHAR(1000) NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'DRAFT',
    report_hidden TINYINT(1) NOT NULL DEFAULT 0,
    authored TINYINT(1) NOT NULL DEFAULT 0,
    seed_version VARCHAR(16) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (code),
    KEY idx_jpath_gq_bank (grammar_code, bank, sort_order),
    CONSTRAINT fk_jpath_grammar_question_grammar FOREIGN KEY (grammar_code) REFERENCES jpath_grammar(code),
    CONSTRAINT chk_jpath_grammar_question_bank CHECK (bank IN ('CHECK','ORDER','FILL','EXTRA','SITU')),
    CONSTRAINT chk_jpath_grammar_question_type CHECK (question_type IN ('FILL','ORDER_STAR','MEANING','ERROR','ORDER','FILL_TEXT','SITUATION')),
    CONSTRAINT chk_jpath_grammar_question_status CHECK (status IN ('REVIEWED','AI_CHECKED','DRAFT','HOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. Cặp dễ nhầm (code_a < code_b; level = cấp cao hơn của hai mục).
CREATE TABLE IF NOT EXISTS jpath_confusable_pair (
    item_type VARCHAR(8) NOT NULL,
    code_a VARCHAR(24) NOT NULL,
    code_b VARCHAR(24) NOT NULL,
    level VARCHAR(2) NOT NULL,
    PRIMARY KEY (item_type, code_a, code_b),
    KEY idx_jpath_confusable_b (item_type, code_b),
    CONSTRAINT chk_jpath_confusable_item_type CHECK (item_type IN ('VOCAB','KANJI')),
    CONSTRAINT chk_jpath_confusable_order CHECK (code_a < code_b)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. Hàng kana (Hiragana 1-18, Katakana 19-36).
CREATE TABLE IF NOT EXISTS jpath_kana_row (
    code VARCHAR(24) NOT NULL,
    script VARCHAR(8) NOT NULL,
    sort_order TINYINT UNSIGNED NOT NULL,
    title_vi VARCHAR(60) NOT NULL,
    row_kind VARCHAR(10) NOT NULL,
    PRIMARY KEY (code),
    CONSTRAINT chk_jpath_kana_row_script CHECK (script IN ('HIRAGANA','KATAKANA')),
    CONSTRAINT chk_jpath_kana_row_kind CHECK (row_kind IN ('BASIC','DAKUTEN','HANDAKUTEN','YOON'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 12. Chữ kana. kana so khớp nhị phân (utf8mb4_bin): は/ば/ぱ là ba chữ khác nhau.
CREATE TABLE IF NOT EXISTS jpath_kana (
    code VARCHAR(24) NOT NULL,
    row_code VARCHAR(24) NOT NULL,
    script VARCHAR(8) NOT NULL,
    sort_order SMALLINT UNSIGNED NOT NULL,
    kana VARCHAR(4) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    romaji VARCHAR(8) NOT NULL,
    PRIMARY KEY (code),
    UNIQUE KEY uk_jpath_kana (script, kana),
    CONSTRAINT fk_jpath_kana_row FOREIGN KEY (row_code) REFERENCES jpath_kana_row(code),
    CONSTRAINT chk_jpath_kana_script CHECK (script IN ('HIRAGANA','KATAKANA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 13. Câu trả lời lúc vào lộ trình.
CREATE TABLE IF NOT EXISTS jpath_entry_preference (
    user_id BIGINT UNSIGNED NOT NULL,
    studied_before TINYINT(1) NOT NULL,
    kana_choice VARCHAR(10) NOT NULL,
    skill_days VARCHAR(16) NOT NULL DEFAULT 'WED,SAT',
    rest_days VARCHAR(16) NOT NULL DEFAULT '',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_jpath_entry_pref_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_entry_pref_kana_choice CHECK (kana_choice IN ('REMEMBER','REVIEW','NONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 14. Lộ trình của người học. active_flag = 1 khi ACTIVE, NULL khi khác: UNIQUE (user_id, active_flag) cho phép nhiều
-- lộ trình cũ nhưng chỉ một lộ trình ACTIVE mỗi người.
CREATE TABLE IF NOT EXISTS jpath_user_path (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    open_level VARCHAR(2) NOT NULL,
    target_level VARCHAR(2) NOT NULL,
    exam_date DATE NULL,
    daily_minutes SMALLINT UNSIGNED NOT NULL,
    skill_days VARCHAR(16) NOT NULL,
    rest_days VARCHAR(16) NOT NULL DEFAULT '',
    third_skill_day VARCHAR(3) NULL,
    study_mode VARCHAR(8) NOT NULL,
    suggested_mode VARCHAR(8) NOT NULL,
    suggestion_reason VARCHAR(20) NOT NULL,
    kana_tab TINYINT(1) NOT NULL DEFAULT 0,
    kana_completed_at DATETIME(6) NULL,
    entry_source VARCHAR(16) NOT NULL,
    assessment_attempt_id BIGINT UNSIGNED NULL,
    smart_vocab_score DECIMAL(5,2) NULL,
    smart_kanji_score DECIMAL(5,2) NULL,
    smart_grammar_score DECIMAL(5,2) NULL,
    smart_ability_band VARCHAR(40) NULL,
    study_duration_months SMALLINT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    archived_at DATETIME(6) NULL,
    active_flag TINYINT(1) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jpath_path_active (user_id, active_flag),
    KEY idx_jpath_path_user_status (user_id, status),
    CONSTRAINT fk_jpath_path_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_path_status CHECK (status IN ('ACTIVE','PAUSED','ARCHIVED')),
    CONSTRAINT chk_jpath_path_study_mode CHECK (study_mode IN ('COMBINED','SPLIT')),
    CONSTRAINT chk_jpath_path_suggested_mode CHECK (suggested_mode IN ('COMBINED','SPLIT')),
    CONSTRAINT chk_jpath_path_entry_source CHECK (entry_source IN ('SMART_TEST','FROM_ZERO','ABANDONED_TEST')),
    CONSTRAINT chk_jpath_path_active_flag CHECK ((status = 'ACTIVE' AND active_flag <=> 1) OR (status <> 'ACTIVE' AND active_flag IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 15. Cài đặt hiển thị của người học. quiz_timer_on: đồng hồ bài V/K (mặc định tắt); grammar_timer_on: đồng hồ ngữ pháp
-- (mặc định bật); hai cột riêng để mỗi nút tắt được nhớ.
CREATE TABLE IF NOT EXISTS jpath_user_settings (
    user_id BIGINT UNSIGNED NOT NULL,
    furigana_hidden_all TINYINT(1) NOT NULL DEFAULT 0,
    romaji_mode VARCHAR(4) NOT NULL DEFAULT 'AUTO',
    font_size VARCHAR(1) NOT NULL DEFAULT 'M',
    sound_on TINYINT(1) NOT NULL DEFAULT 1,
    autoplay_audio TINYINT(1) NOT NULL DEFAULT 1,
    quiz_timer_on TINYINT(1) NOT NULL DEFAULT 0,
    grammar_timer_on TINYINT(1) NOT NULL DEFAULT 1,
    reminder_web TINYINT(1) NOT NULL DEFAULT 1,
    reminder_email TINYINT(1) NOT NULL DEFAULT 0,
    reminder_time TIME NOT NULL DEFAULT '20:00:00',
    vocab_list_view VARCHAR(6) NOT NULL DEFAULT 'TOPICS',
    tour_done_at DATETIME(6) NULL,
    swipe_hint_done_at DATETIME(6) NULL,
    team_notebook_id BIGINT UNSIGNED NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_jpath_settings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_settings_romaji_mode CHECK (romaji_mode IN ('AUTO','ON','OFF')),
    CONSTRAINT chk_jpath_settings_font_size CHECK (font_size IN ('S','M','L')),
    CONSTRAINT chk_jpath_settings_vocab_list_view CHECK (vocab_list_view IN ('TOPICS','FLAT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 16. Một cấp trong lộ trình: giai đoạn, thanh trượt và số lần chỉnh (tối đa 2).
CREATE TABLE IF NOT EXISTS jpath_user_level (
    path_id BIGINT UNSIGNED NOT NULL,
    level VARCHAR(2) NOT NULL,
    stage VARCHAR(16) NOT NULL DEFAULT 'STUDY',
    slider_vocab TINYINT UNSIGNED NOT NULL,
    slider_kanji TINYINT UNSIGNED NOT NULL,
    slider_grammar TINYINT UNSIGNED NOT NULL,
    adjustments_used TINYINT UNSIGNED NOT NULL DEFAULT 0,
    first_drag_used TINYINT(1) NOT NULL DEFAULT 0,
    priority_mode TINYINT(1) NOT NULL DEFAULT 0,
    opened_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    skill_practice_at DATETIME(6) NULL,
    passed_at DATETIME(6) NULL,
    mock_attempt_id BIGINT UNSIGNED NULL,
    PRIMARY KEY (path_id, level),
    CONSTRAINT fk_jpath_user_level_path FOREIGN KEY (path_id) REFERENCES jpath_user_path(id) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_user_level_stage CHECK (stage IN ('STUDY','SKILL_PRACTICE','EXAM','PASSED')),
    CONSTRAINT chk_jpath_user_level_adjustments CHECK (adjustments_used <= 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 17. Lịch sử chỉnh thanh trượt.
CREATE TABLE IF NOT EXISTS jpath_slider_adjustment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    path_id BIGINT UNSIGNED NOT NULL,
    level VARCHAR(2) NOT NULL,
    kind VARCHAR(14) NOT NULL,
    counted TINYINT(1) NOT NULL,
    vocab_before TINYINT UNSIGNED NULL,
    vocab_after TINYINT UNSIGNED NOT NULL,
    kanji_before TINYINT UNSIGNED NULL,
    kanji_after TINYINT UNSIGNED NOT NULL,
    grammar_before TINYINT UNSIGNED NULL,
    grammar_after TINYINT UNSIGNED NOT NULL,
    daily_minutes SMALLINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_jpath_slider_path (path_id, level, created_at),
    CONSTRAINT fk_jpath_slider_path FOREIGN KEY (path_id) REFERENCES jpath_user_path(id) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_slider_kind CHECK (kind IN ('INITIAL','CONFIRM','FIRST_DRAG','UPDATE','REBALANCE','TARGET_CHANGE','LEVEL_START'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 18. Trạng thái học của từng mục (không có dòng = "Chưa học"). Cột SRS lưu giờ địa phương của người học.
-- known_source: USER|SKIP_CHECK|TOPIC_CHECK; 'TOPIC_PEND' trên dòng LEARNING = mục chủ đề chưa hỏi, chờ xác nhận.
CREATE TABLE IF NOT EXISTS jpath_user_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    item_type VARCHAR(8) NOT NULL,
    item_code VARCHAR(24) NOT NULL,
    level VARCHAR(2) NULL,
    state VARCHAR(10) NOT NULL,
    repetitions SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    interval_days INT UNSIGNED NOT NULL DEFAULT 0,
    easiness DECIMAL(4,2) NOT NULL DEFAULT 2.50,
    next_review_at DATETIME(6) NULL,
    last_reviewed_at DATETIME(6) NULL,
    first_learned_at DATETIME(6) NULL,
    counted_at DATETIME(6) NULL,
    forget_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    correct_streak SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    notebook_source VARCHAR(12) NULL,
    notebook_since DATETIME(6) NULL,
    notebook_hits TINYINT UNSIGNED NOT NULL DEFAULT 0,
    notebook_last_hit_day DATE NULL,
    known_at DATETIME(6) NULL,
    known_source VARCHAR(12) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_jpath_item_user_code (user_id, item_type, item_code),
    KEY idx_jpath_item_due (user_id, item_type, state, next_review_at),
    KEY idx_jpath_item_progress (user_id, level, item_type, counted_at),
    CONSTRAINT fk_jpath_item_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_item_type CHECK (item_type IN ('VOCAB','KANJI','GRAMMAR','KANA')),
    CONSTRAINT chk_jpath_item_state CHECK (state IN ('LEARNING','NOTEBOOK','KNOWN')),
    CONSTRAINT chk_jpath_item_grammar_notebook CHECK (NOT (item_type = 'GRAMMAR' AND state = 'NOTEBOOK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 19. Kế hoạch một ngày học (study_day = ngày của người học). cp_awarded: số CP hook đã trả cho ngày này (không gồm huy hiệu).
CREATE TABLE IF NOT EXISTS jpath_daily_plan (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    path_id BIGINT UNSIGNED NOT NULL,
    study_day DATE NOT NULL,
    level VARCHAR(2) NOT NULL,
    day_kind VARCHAR(12) NOT NULL,
    kana_phase TINYINT(1) NOT NULL DEFAULT 0,
    skill_makeup TINYINT(1) NOT NULL DEFAULT 0,
    reviews_cleared_at DATETIME(6) NULL,
    all_done_at DATETIME(6) NULL,
    cp_awarded SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_jpath_plan_user_day (user_id, study_day),
    CONSTRAINT fk_jpath_plan_path FOREIGN KEY (path_id) REFERENCES jpath_user_path(id) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_plan_day_kind CHECK (day_kind IN ('STUDY','SKILL','REST','REVIEW_ONLY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 20. Một tab trong kế hoạch ngày.
CREATE TABLE IF NOT EXISTS jpath_daily_tab (
    plan_id BIGINT UNSIGNED NOT NULL,
    tab VARCHAR(8) NOT NULL,
    new_target SMALLINT UNSIGNED NOT NULL,
    new_done SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    extra_done SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    review_cap SMALLINT UNSIGNED NOT NULL,
    review_done SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    check_passed_at DATETIME(6) NULL,
    done_at DATETIME(6) NULL,
    too_fast TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (plan_id, tab),
    CONSTRAINT fk_jpath_daily_tab_plan FOREIGN KEY (plan_id) REFERENCES jpath_daily_plan(id) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_daily_tab_tab CHECK (tab IN ('VOCAB','KANJI','GRAMMAR','KANA','SKILL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 21. Phiên học (hàng đợi bước học đã đóng băng).
CREATE TABLE IF NOT EXISTS jpath_study_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    plan_id BIGINT UNSIGNED NULL,
    tab VARCHAR(8) NOT NULL,
    mode VARCHAR(12) NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    queue_json JSON NOT NULL,
    `position` SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    total SMALLINT UNSIGNED NOT NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_jpath_session_user (user_id, status, plan_id, tab),
    CONSTRAINT fk_jpath_session_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_session_status CHECK (status IN ('OPEN','COMPLETED','ABANDONED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 22. Sự kiện học (client_event_id chống gửi trùng khi phát lại hàng đợi offline).
CREATE TABLE IF NOT EXISTS jpath_study_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    session_id BIGINT UNSIGNED NULL,
    client_event_id VARCHAR(40) NOT NULL,
    item_type VARCHAR(8) NOT NULL,
    item_code VARCHAR(24) NOT NULL,
    action VARCHAR(12) NOT NULL,
    rating VARCHAR(10) NULL,
    quality TINYINT UNSIGNED NULL,
    correct TINYINT(1) NULL,
    elapsed_ms INT UNSIGNED NULL,
    study_day DATE NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_jpath_event_client (user_id, client_event_id),
    KEY idx_jpath_event_day (user_id, study_day),
    CONSTRAINT fk_jpath_event_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 23. Đề đã phát. issue_json: echo công khai của yêu cầu phát đề. answers_json không bao giờ trả ra API.
CREATE TABLE IF NOT EXISTS jpath_check_set (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    token CHAR(36) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    plan_id BIGINT UNSIGNED NULL,
    session_id BIGINT UNSIGNED NULL,
    kind VARCHAR(16) NOT NULL,
    tab VARCHAR(8) NOT NULL,
    level VARCHAR(2) NULL,
    attempt_no TINYINT UNSIGNED NOT NULL DEFAULT 1,
    status VARCHAR(10) NOT NULL DEFAULT 'ISSUED',
    timer_on TINYINT(1) NOT NULL DEFAULT 0,
    issue_json JSON NULL,
    questions_json JSON NOT NULL,
    answers_json JSON NOT NULL,
    progress_json JSON NULL,
    item_codes_json JSON NOT NULL,
    issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_answer_at DATETIME(6) NULL,
    submitted_at DATETIME(6) NULL,
    submit_key VARCHAR(40) NULL,
    correct_count SMALLINT UNSIGNED NULL,
    total_count SMALLINT UNSIGNED NOT NULL,
    score_pct DECIMAL(5,2) NULL,
    passed TINYINT(1) NULL,
    too_fast TINYINT(1) NOT NULL DEFAULT 0,
    result_json JSON NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jpath_check_token (token),
    KEY idx_jpath_check_session (user_id, session_id, kind, status),
    KEY idx_jpath_check_submitted (user_id, submitted_at),
    CONSTRAINT fk_jpath_check_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_check_kind CHECK (kind IN ('MICRO_CHECK','GROUP_EXERCISE','SKIP_ONE','TOPIC_KNOWN','FREE_QUIZ','CONFUSABLE','KANA_ROW','GRAMMAR_PRACTICE','GRAMMAR_EXTRA','GRAMMAR_REVIEW','SKILL_LESSON','SKILL_PRACTICE','SKILL_DICTATION','SKILL_SECTION','WEEK_KNOWN')),
    CONSTRAINT chk_jpath_check_status CHECK (status IN ('ISSUED','SUBMITTED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 24. Báo sai nội dung. open_flag = 1 khi OPEN, NULL khi khác: mỗi người chỉ một báo OPEN cho một mục.
CREATE TABLE IF NOT EXISTS jpath_content_report (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    reporter_user_id BIGINT UNSIGNED NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_code VARCHAR(24) NOT NULL,
    item_type VARCHAR(8) NULL,
    item_code VARCHAR(24) NOT NULL,
    level VARCHAR(2) NULL,
    error_type VARCHAR(8) NOT NULL,
    note VARCHAR(500) NULL,
    page_context VARCHAR(40) NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    resolved_by BIGINT UNSIGNED NULL,
    resolved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    open_flag TINYINT(1) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jpath_report_open (target_type, target_code, reporter_user_id, open_flag),
    KEY idx_jpath_report_target (target_type, target_code, status),
    KEY idx_jpath_report_queue (status, level, created_at),
    CONSTRAINT fk_jpath_report_user FOREIGN KEY (reporter_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_report_target_type CHECK (target_type IN ('VOCAB','KANJI','GRAMMAR','KANA','EXAMPLE','QUESTION','SKILL_SET','SKILL_QUESTION')),
    CONSTRAINT chk_jpath_report_error_type CHECK (error_type IN ('MEANING','EXAMPLE','AUDIO','KANJI','OTHER')),
    CONSTRAINT chk_jpath_report_status CHECK (status IN ('OPEN','RESOLVED','REJECTED')),
    CONSTRAINT chk_jpath_report_open_flag CHECK ((status = 'OPEN' AND open_flag <=> 1) OR (status <> 'OPEN' AND open_flag IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 25. Nhật ký sửa nội dung (editor_user_id = 0: hệ thống); exported_at: đã xuất ngược về nguồn.
CREATE TABLE IF NOT EXISTS jpath_content_edit (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    editor_user_id BIGINT UNSIGNED NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_code VARCHAR(24) NOT NULL,
    level VARCHAR(2) NULL,
    field_name VARCHAR(40) NOT NULL,
    old_value MEDIUMTEXT NULL,
    new_value MEDIUMTEXT NULL,
    report_id BIGINT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    exported_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_jpath_edit_export (level, exported_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 26. Huy hiệu đã nhận.
CREATE TABLE IF NOT EXISTS jpath_user_badge (
    user_id BIGINT UNSIGNED NOT NULL,
    badge_code VARCHAR(30) NOT NULL,
    earned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, badge_code),
    CONSTRAINT fk_jpath_badge_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 27. Nhắc học đã gửi (reminder_day = ngày của người học).
CREATE TABLE IF NOT EXISTS jpath_reminder_log (
    user_id BIGINT UNSIGNED NOT NULL,
    reminder_day DATE NOT NULL,
    sent_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    channels VARCHAR(20) NOT NULL,
    PRIMARY KEY (user_id, reminder_day),
    CONSTRAINT fk_jpath_reminder_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- [JLAS-W0108] Kế hoạch 3: Đọc · Nghe, Thi thử — bảng 28-36.
-- 28-32 là nội dung kỹ năng (seed ghi vào vùng "jpath-skills" của learning_data_<L>.sql, ứng dụng chỉ đọc);
-- 33-36 là trạng thái người học. Câu luyện/chép chính tả/luyện thi đi qua jpath_check_set (kind SKILL_*), nên đáp án
-- (answer_index, chữ câu chép, lời thoại) chỉ ra API sau khi câu/bộ bị khóa.
-- =============================================================================

-- 28. Dạng câu JLPT của một cấp (読解/聴解). sort_order = thứ tự dạng trong đề.
CREATE TABLE IF NOT EXISTS jpath_skill_type (
    code VARCHAR(24) NOT NULL,
    level VARCHAR(2) NOT NULL,
    skill VARCHAR(10) NOT NULL,
    name_vi VARCHAR(120) NOT NULL,
    name_ja VARCHAR(60) NOT NULL,
    sort_order TINYINT UNSIGNED NOT NULL,
    questions_in_exam TINYINT UNSIGNED NOT NULL,
    needs_image TINYINT(1) NOT NULL DEFAULT 0,
    chars_min SMALLINT UNSIGNED NULL,
    chars_max SMALLINT UNSIGNED NULL,
    speakers TINYINT UNSIGNED NULL,
    seconds_typical SMALLINT UNSIGNED NULL,
    seed_version VARCHAR(16) NULL,
    PRIMARY KEY (code),
    KEY idx_jpath_skilltype_level (level, skill, sort_order),
    CONSTRAINT fk_jpath_skilltype_level FOREIGN KEY (level) REFERENCES jpath_level(level),
    CONSTRAINT chk_jpath_skill_type_skill CHECK (skill IN ('READING','LISTENING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 29. Bài chiến thuật của một dạng (1-1). example_json không chứa đáp án: example_answer_index và check_answer_index
-- chỉ ra API sau khi người học chọn đúng câu kiểm.
CREATE TABLE IF NOT EXISTS jpath_skill_lesson (
    type_code VARCHAR(24) NOT NULL,
    title_vi VARCHAR(200) NOT NULL,
    goal_vi VARCHAR(500) NOT NULL,
    steps_json JSON NOT NULL,
    traps_json JSON NOT NULL,
    tips_json JSON NOT NULL,
    example_json JSON NOT NULL,
    example_answer_index TINYINT UNSIGNED NOT NULL,
    check_question_vi VARCHAR(500) NOT NULL,
    check_options_json JSON NOT NULL,
    check_answer_index TINYINT UNSIGNED NOT NULL,
    check_explanation_vi VARCHAR(1000) NOT NULL,
    example_audio_path VARCHAR(255) NULL,
    example_audio_sha256 CHAR(64) NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'DRAFT',
    report_hidden TINYINT(1) NOT NULL DEFAULT 0,
    authored TINYINT(1) NOT NULL DEFAULT 0,
    seed_version VARCHAR(16) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (type_code),
    CONSTRAINT fk_jpath_skilllesson_type FOREIGN KEY (type_code) REFERENCES jpath_skill_type(code),
    CONSTRAINT chk_jpath_skill_lesson_status CHECK (status IN ('REVIEWED','AI_CHECKED','DRAFT','HOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 30. Bộ luyện (một đoạn đọc hoặc một bài nghe + câu hỏi). Bộ Nghe chưa có audio_path không hiện cho người học.
CREATE TABLE IF NOT EXISTS jpath_skill_set (
    code VARCHAR(24) NOT NULL,
    type_code VARCHAR(24) NOT NULL,
    level VARCHAR(2) NOT NULL,
    skill VARCHAR(10) NOT NULL,
    sort_order SMALLINT UNSIGNED NOT NULL,
    passage_ja TEXT NULL,
    passage_vi TEXT NULL,
    script_json JSON NULL,
    script_vi TEXT NULL,
    image_description_vi VARCHAR(1000) NULL,
    new_words_json JSON NOT NULL,
    char_count SMALLINT UNSIGNED NOT NULL,
    question_count TINYINT UNSIGNED NOT NULL,
    audio_path VARCHAR(255) NULL,
    audio_sha256 CHAR(64) NULL,
    audio_seconds SMALLINT UNSIGNED NULL,
    translation_json JSON NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'DRAFT',
    report_hidden TINYINT(1) NOT NULL DEFAULT 0,
    authored TINYINT(1) NOT NULL DEFAULT 0,
    seed_version VARCHAR(16) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (code),
    KEY idx_jpath_skillset_pick (type_code, status, sort_order),
    CONSTRAINT fk_jpath_skillset_type FOREIGN KEY (type_code) REFERENCES jpath_skill_type(code),
    CONSTRAINT chk_jpath_skill_set_skill CHECK (skill IN ('READING','LISTENING')),
    CONSTRAINT chk_jpath_skill_set_status CHECK (status IN ('REVIEWED','AI_CHECKED','DRAFT','HOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 31. Câu hỏi của một bộ (mã <setId>-Q<n>). answer_index không ra API trước khi câu bị khóa.
CREATE TABLE IF NOT EXISTS jpath_skill_question (
    code VARCHAR(28) NOT NULL,
    set_code VARCHAR(24) NOT NULL,
    sort_order TINYINT UNSIGNED NOT NULL,
    question_ja VARCHAR(500) NOT NULL,
    options_json JSON NOT NULL,
    answer_index TINYINT UNSIGNED NOT NULL,
    explanation_vi VARCHAR(1000) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'DRAFT',
    report_hidden TINYINT(1) NOT NULL DEFAULT 0,
    authored TINYINT(1) NOT NULL DEFAULT 0,
    seed_version VARCHAR(16) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (code),
    KEY idx_jpath_skillq_set (set_code, sort_order),
    CONSTRAINT fk_jpath_skillq_set FOREIGN KEY (set_code) REFERENCES jpath_skill_set(code) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_skill_question_status CHECK (status IN ('REVIEWED','AI_CHECKED','DRAFT','HOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 32. Câu chép chính tả của bộ Nghe (mã <setId>-D<n>). text_ja chính là đáp án: không ra API trước khi khóa.
-- blanks_json = [{start, end, answer, accept:[...]}].
CREATE TABLE IF NOT EXISTS jpath_skill_dictation (
    code VARCHAR(28) NOT NULL,
    set_code VARCHAR(24) NOT NULL,
    sort_order TINYINT UNSIGNED NOT NULL,
    text_ja VARCHAR(255) NOT NULL,
    blanks_json JSON NOT NULL,
    audio_path VARCHAR(255) NULL,
    audio_sha256 CHAR(64) NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'DRAFT',
    report_hidden TINYINT(1) NOT NULL DEFAULT 0,
    authored TINYINT(1) NOT NULL DEFAULT 0,
    seed_version VARCHAR(16) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (code),
    KEY idx_jpath_skilldict_set (set_code, sort_order),
    CONSTRAINT fk_jpath_skilldict_set FOREIGN KEY (set_code) REFERENCES jpath_skill_set(code) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_skill_dictation_status CHECK (status IN ('REVIEWED','AI_CHECKED','DRAFT','HOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 33. Buổi kỹ năng. open_flag = 1 khi chưa xong, NULL khi đã xong: mỗi kỹ năng chỉ một buổi mở mỗi kế hoạch ngày và kind.
CREATE TABLE IF NOT EXISTS jpath_skill_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    path_id BIGINT UNSIGNED NULL,
    plan_id BIGINT UNSIGNED NULL,
    level VARCHAR(2) NOT NULL,
    skill VARCHAR(10) NOT NULL,
    kind VARCHAR(8) NOT NULL,
    type_code VARCHAR(24) NULL,
    step VARCHAR(10) NOT NULL DEFAULT 'THEORY',
    set_codes_json JSON NOT NULL,
    reused TINYINT(1) NOT NULL DEFAULT 0,
    lesson_passed_at DATETIME(6) NULL,
    practice_token CHAR(36) NULL,
    dictation_token CHAR(36) NULL,
    section_token CHAR(36) NULL,
    study_day DATE NOT NULL,
    score_pct DECIMAL(5,2) NULL,
    cp_awarded TINYINT UNSIGNED NOT NULL DEFAULT 0,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    open_flag TINYINT(1) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jpath_skill_open (user_id, plan_id, skill, kind, open_flag),
    KEY idx_jpath_skillsess_day (user_id, study_day),
    KEY idx_jpath_skillsess_done (user_id, kind, completed_at),
    CONSTRAINT fk_jpath_skillsess_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_skill_session_skill CHECK (skill IN ('READING','LISTENING')),
    CONSTRAINT chk_jpath_skill_session_kind CHECK (kind IN ('DAY','FREE','SECTION')),
    CONSTRAINT chk_jpath_skill_session_step CHECK (step IN ('THEORY','PRACTICE','DICTATION','DONE')),
    CONSTRAINT chk_jpath_skill_session_open_flag CHECK ((completed_at IS NULL AND open_flag <=> 1) OR (completed_at IS NOT NULL AND open_flag IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 34. Thống kê theo dạng của người học (chọn dạng hôm nay, #37).
CREATE TABLE IF NOT EXISTS jpath_skill_type_stat (
    user_id BIGINT UNSIGNED NOT NULL,
    type_code VARCHAR(24) NOT NULL,
    sessions_done SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    questions_done INT UNSIGNED NOT NULL DEFAULT 0,
    correct_done INT UNSIGNED NOT NULL DEFAULT 0,
    last_pct DECIMAL(5,2) NULL,
    last_session_day DATE NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, type_code),
    CONSTRAINT fk_jpath_typestat_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 35. Bộ đã dùng (chọn bộ, dùng lại sau 8 tuần, #39). last_used_day = ngày của người học.
CREATE TABLE IF NOT EXISTS jpath_skill_set_use (
    user_id BIGINT UNSIGNED NOT NULL,
    set_code VARCHAR(24) NOT NULL,
    uses SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    last_used_day DATE NOT NULL,
    PRIMARY KEY (user_id, set_code),
    KEY idx_jpath_setuse_day (user_id, last_used_day),
    CONSTRAINT fk_jpath_setuse_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 36. Kết quả Thi thử đã áp vào lộ trình. Khóa là id lượt thi: một lượt chỉ áp đúng một lần.
CREATE TABLE IF NOT EXISTS jpath_mock_result (
    mock_attempt_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    path_id BIGINT UNSIGNED NOT NULL,
    level VARCHAR(2) NOT NULL,
    outcome VARCHAR(8) NOT NULL,
    score_pct TINYINT UNSIGNED NOT NULL,
    sections_json JSON NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    cp_awarded SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (mock_attempt_id),
    KEY idx_jpath_mock_user (user_id, level, submitted_at),
    CONSTRAINT fk_jpath_mock_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_jpath_mock_outcome CHECK (outcome IN ('PASSED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [JLAS-W0108] Nâng cấp tại chỗ cho DB đã có bảng jpath_* từ trước (kiểm information_schema, chạy lại không đổi gì).
-- jpath_kanji.level_reading_source (Plan 2 Task 9, sửa vòng 1).
SET @jpath_ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE jpath_kanji ADD COLUMN level_reading_source VARCHAR(20) NULL AFTER level_readings_json', 'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'jpath_kanji' AND COLUMN_NAME = 'level_reading_source');
PREPARE jpath_ddl_stmt FROM @jpath_ddl;
EXECUTE jpath_ddl_stmt;
DEALLOCATE PREPARE jpath_ddl_stmt;

-- [JLAS-W0108] Kế hoạch 3: mã báo sai SKILL_QUESTION dài 14 ký tự -> target_type rộng 16 (chỉ nới, dữ liệu cũ vẫn hợp lệ).
CALL jlas_ls_modify_column('jpath_content_report', 'target_type', 'varchar(16)', 'NO', NULL, 'VARCHAR(16) NOT NULL');
CALL jlas_ls_modify_column('jpath_content_edit', 'target_type', 'varchar(16)', 'NO', NULL, 'VARCHAR(16) NOT NULL');
-- Kế hoạch 3 Task 24: báo sai bài Đọc/Nghe không có loại mục thẻ (item_type rỗng; item_code = mã bộ).
CALL jlas_ls_modify_column('jpath_content_report', 'item_type', 'varchar(8)', 'YES', NULL, 'VARCHAR(8) NULL');
-- 04/11: mẫu ngữ pháp phụ gắn vào mẫu chính (quyết định 02/11: mẫu phụ đi kèm mẫu chính, nội dung nhẹ).
CALL jlas_ls_add_column('jpath_grammar', 'parent_code', 'VARCHAR(24) NULL AFTER related_codes_json');
-- Kế hoạch 3: 4 kind câu kỹ năng và 2 đích báo sai mới (thay CHECK khi mệnh đề thiếu giá trị cuối; chỉ nới).
CALL jlas_ls_check('jpath_check_set', 'chk_jpath_check_kind', 'SKILL_SECTION', NULL,
    'kind IN (''MICRO_CHECK'',''GROUP_EXERCISE'',''SKIP_ONE'',''TOPIC_KNOWN'',''FREE_QUIZ'',''CONFUSABLE'',''KANA_ROW'',''GRAMMAR_PRACTICE'',''GRAMMAR_EXTRA'',''GRAMMAR_REVIEW'',''SKILL_LESSON'',''SKILL_PRACTICE'',''SKILL_DICTATION'',''SKILL_SECTION'')');
-- 07/11: bài kiểm tra bỏ qua tuần (WEEK_KNOWN) cho người vào từ Smart Test mức Căn bản trở lên (chỉ nới).
CALL jlas_ls_check('jpath_check_set', 'chk_jpath_check_kind', 'WEEK_KNOWN', NULL,
    'kind IN (''MICRO_CHECK'',''GROUP_EXERCISE'',''SKIP_ONE'',''TOPIC_KNOWN'',''FREE_QUIZ'',''CONFUSABLE'',''KANA_ROW'',''GRAMMAR_PRACTICE'',''GRAMMAR_EXTRA'',''GRAMMAR_REVIEW'',''SKILL_LESSON'',''SKILL_PRACTICE'',''SKILL_DICTATION'',''SKILL_SECTION'',''WEEK_KNOWN'')');
CALL jlas_ls_check('jpath_content_report', 'chk_jpath_report_target_type', 'SKILL_QUESTION', NULL,
    'target_type IN (''VOCAB'',''KANJI'',''GRAMMAR'',''KANA'',''EXAMPLE'',''QUESTION'',''SKILL_SET'',''SKILL_QUESTION'')');
-- 05/11: câu hỏi ngữ pháp kiểu đề theo tình huống (nhóm SITU, loại SITUATION; chỉ nới).
CALL jlas_ls_check('jpath_grammar_question', 'chk_jpath_grammar_question_bank', 'SITU', NULL,
    'bank IN (''CHECK'',''ORDER'',''FILL'',''EXTRA'',''SITU'')');
CALL jlas_ls_check('jpath_grammar_question', 'chk_jpath_grammar_question_type', 'SITUATION', NULL,
    'question_type IN (''FILL'',''ORDER_STAR'',''MEANING'',''ERROR'',''ORDER'',''FILL_TEXT'',''SITUATION'')');
-- 06/11: bản dịch câu hỏi, lựa chọn, từng câu bài đọc / từng dòng bài nghe (trang luyện Đọc · Nghe).
CALL jlas_ls_add_column('jpath_skill_set', 'translation_json', 'JSON NULL AFTER audio_seconds');

-- ==== 06/11 Lộ trình giao tiếp (đợt 2) ====
-- [JLAS-W0108] Phase 2 communication path tables (owner 06/11). Content (cpath_goal … cpath_line) comes from
-- data/learning-path/communication-compiled/<GOAL>.json through the seed; learner tables are written by the app.

CREATE TABLE IF NOT EXISTS cpath_goal (
    code VARCHAR(16) NOT NULL,              -- TRAVEL | DAILY_LIFE | BA | WORK
    name_vi VARCHAR(120) NOT NULL,
    sort_order TINYINT UNSIGNED NOT NULL,
    fields_json JSON NULL,                  -- WORK: [{code,labelVi,topicCodes}]
    test_scenario_slug VARCHAR(120) NULL,   -- kaiwa scenario of the goal test (cpath-test-…)
    seed_version VARCHAR(16) NULL,
    PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cpath_topic (
    code VARCHAR(32) NOT NULL,
    goal_code VARCHAR(16) NOT NULL,
    sort_order SMALLINT UNSIGNED NOT NULL,
    level VARCHAR(2) NULL,
    name_ja VARCHAR(120) NULL,
    name_vi VARCHAR(160) NOT NULL,
    seed_version VARCHAR(16) NULL,
    PRIMARY KEY (code),
    KEY idx_cpath_topic_goal (goal_code, sort_order),
    CONSTRAINT fk_cpath_topic_goal FOREIGN KEY (goal_code) REFERENCES cpath_goal(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cpath_situation (
    code VARCHAR(24) NOT NULL,              -- dialogue id, e.g. TRV-01
    topic_code VARCHAR(32) NOT NULL,
    sort_order SMALLINT UNSIGNED NOT NULL,
    level VARCHAR(2) NULL,
    title_ja VARCHAR(200) NULL,
    title_vi VARCHAR(200) NULL,
    situation_vi VARCHAR(1000) NULL,
    challenge_json JSON NULL,               -- role-play turns {learnerSpeaker, turns:[{line, distractors:[{ja,vi,furigana}], why_vi}]}
    scenario_slug VARCHAR(120) NULL,        -- kaiwa scenario for "Nói chuyện tự do với AI" (cpath-…)
    seed_version VARCHAR(16) NULL,
    PRIMARY KEY (code),
    KEY idx_cpath_situation_topic (topic_code, sort_order),
    CONSTRAINT fk_cpath_situation_topic FOREIGN KEY (topic_code) REFERENCES cpath_topic(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cpath_line (
    situation_code VARCHAR(24) NOT NULL,
    line_order SMALLINT UNSIGNED NOT NULL,
    speaker VARCHAR(40) NOT NULL,
    learner TINYINT(1) NOT NULL DEFAULT 0,   -- speaker = リン
    ja VARCHAR(500) NOT NULL,
    furigana VARCHAR(800) NULL,
    vi VARCHAR(500) NULL,
    audio_path VARCHAR(255) NULL,
    seed_version VARCHAR(16) NULL,
    PRIMARY KEY (situation_code, line_order),
    CONSTRAINT fk_cpath_line_situation FOREIGN KEY (situation_code) REFERENCES cpath_situation(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cpath_item (
    code VARCHAR(32) NOT NULL,              -- word id (TRV-001) or phrase id (TRV-01-P01)
    kind VARCHAR(8) NOT NULL,               -- WORD | PHRASE
    topic_code VARCHAR(32) NOT NULL,
    situation_code VARCHAR(24) NULL,        -- phrases: the situation that uses it
    sort_order SMALLINT UNSIGNED NOT NULL,
    ja VARCHAR(255) NOT NULL,
    reading VARCHAR(255) NULL,
    vi VARCHAR(500) NULL,
    note_vi VARCHAR(500) NULL,
    audio_path VARCHAR(255) NULL,
    seed_version VARCHAR(16) NULL,
    PRIMARY KEY (code),
    KEY idx_cpath_item_topic (topic_code, kind, sort_order),
    CONSTRAINT chk_cpath_item_kind CHECK (kind IN ('WORD','PHRASE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- learner side (written by the app, never by the seed)
CREATE TABLE IF NOT EXISTS cpath_user_path (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    goal_code VARCHAR(16) NOT NULL,
    role VARCHAR(10) NOT NULL,              -- MAIN | SECONDARY
    status VARCHAR(10) NOT NULL,            -- ACTIVE | PAUSED | DONE
    self_level VARCHAR(16) NOT NULL,        -- BEGINNER | ELEMENTARY | INTERMEDIATE
    fields_json JSON NULL,
    priority_topics_json JSON NULL,
    daily_situations TINYINT UNSIGNED NOT NULL DEFAULT 1,
    rest_days VARCHAR(40) NULL,
    finish_date DATE NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_cpath_user_path_user (user_id, status),
    CONSTRAINT chk_cpath_user_path_role CHECK (role IN ('MAIN','SECONDARY')),
    CONSTRAINT chk_cpath_user_path_status CHECK (status IN ('ACTIVE','PAUSED','DONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cpath_user_situation (
    path_id BIGINT UNSIGNED NOT NULL,
    situation_code VARCHAR(24) NOT NULL,
    study_day DATE NULL,                    -- the day it was planned / learned
    steps_done VARCHAR(40) NOT NULL DEFAULT '',  -- comma list of WORDS,LISTEN,ROLEPLAY,SPEAK
    roleplay_correct TINYINT UNSIGNED NULL,
    roleplay_total TINYINT UNSIGNED NULL,
    speak_best SMALLINT UNSIGNED NULL,      -- best mean line score 0–100
    done_at DATETIME(6) NULL,
    PRIMARY KEY (path_id, situation_code),
    CONSTRAINT fk_cpath_user_situation_path FOREIGN KEY (path_id) REFERENCES cpath_user_path(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cpath_user_topic (
    path_id BIGINT UNSIGNED NOT NULL,
    topic_code VARCHAR(32) NOT NULL,
    quiz_best SMALLINT UNSIGNED NULL,       -- %
    speak_best SMALLINT UNSIGNED NULL,      -- 0–100
    passed_at DATETIME(6) NULL,             -- quiz ≥ 80 and speak ≥ 70
    PRIMARY KEY (path_id, topic_code),
    CONSTRAINT fk_cpath_user_topic_path FOREIGN KEY (path_id) REFERENCES cpath_user_path(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cpath_user_goal_test (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    path_id BIGINT UNSIGNED NOT NULL,
    conversation_session_id BIGINT UNSIGNED NULL,  -- kaiwa conversation_session.id (read only)
    score SMALLINT UNSIGNED NULL,
    passed TINYINT(1) NULL,
    taken_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_cpath_goal_test_path (path_id, taken_at),
    CONSTRAINT fk_cpath_goal_test_path FOREIGN KEY (path_id) REFERENCES cpath_user_path(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS jlas_ls_check;
DROP PROCEDURE IF EXISTS jlas_ls_modify_column;
DROP PROCEDURE IF EXISTS jlas_ls_add_column;
DROP PROCEDURE IF EXISTS jlas_ls_exec;
