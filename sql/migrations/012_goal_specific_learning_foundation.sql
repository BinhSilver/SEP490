-- [JLAS-W0108] Goal-specific curriculum and structured survey foundation.
-- Canonical lessons remain in lesson; these tables only classify and map them.

ALTER TABLE user_learning_path
    DROP CHECK chk_learning_path_goal,
    ADD COLUMN learning_mode VARCHAR(30) NULL AFTER goal,
    ADD CONSTRAINT chk_learning_path_goal CHECK
        (goal IS NULL OR goal IN ('JLPT','TRAVEL','DAILY_LIFE','BA','FROM_ZERO')),
    ADD CONSTRAINT chk_learning_path_mode CHECK
        (learning_mode IS NULL OR learning_mode IN
            ('FOUNDATION_FIRST','COMMUNICATION_FIRST','BALANCED'));

CREATE TABLE learning_goal (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    goal_code VARCHAR(30) NOT NULL,
    display_name_vi VARCHAR(255) NOT NULL,
    description_vi TEXT NULL,
    entry_policy VARCHAR(50) NOT NULL,
    requires_assessment BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    external_key VARCHAR(100) NULL,
    source_code VARCHAR(100) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_goal_code (goal_code),
    UNIQUE KEY uk_learning_goal_external_key (external_key),
    CONSTRAINT chk_learning_goal_review CHECK (review_status IN ('DRAFT','REVIEWED','APPROVED','REJECTED'))
);

CREATE TABLE learning_topic (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    goal_id BIGINT UNSIGNED NOT NULL,
    parent_topic_id BIGINT UNSIGNED NULL,
    topic_code VARCHAR(100) NOT NULL,
    title_vi VARCHAR(255) NOT NULL,
    title_ja VARCHAR(255) NULL,
    topic_order INT UNSIGNED NOT NULL,
    description TEXT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    external_key VARCHAR(100) NULL,
    source_code VARCHAR(100) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_topic_goal_code (goal_id, topic_code),
    UNIQUE KEY uk_learning_topic_external_key (external_key),
    CONSTRAINT fk_learning_topic_goal FOREIGN KEY (goal_id) REFERENCES learning_goal(id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_topic_parent FOREIGN KEY (parent_topic_id) REFERENCES learning_topic(id) ON DELETE RESTRICT,
    CONSTRAINT chk_learning_topic_review CHECK (review_status IN ('DRAFT','REVIEWED','APPROVED','REJECTED'))
);

CREATE TABLE learning_competency (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    goal_id BIGINT UNSIGNED NOT NULL,
    competency_code VARCHAR(100) NOT NULL,
    display_name_vi VARCHAR(255) NOT NULL,
    description TEXT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    external_key VARCHAR(100) NULL,
    source_code VARCHAR(100) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_competency_goal_code (goal_id, competency_code),
    UNIQUE KEY uk_learning_competency_external_key (external_key),
    CONSTRAINT fk_learning_competency_goal FOREIGN KEY (goal_id) REFERENCES learning_goal(id) ON DELETE CASCADE,
    CONSTRAINT chk_learning_competency_review CHECK (review_status IN ('DRAFT','REVIEWED','APPROVED','REJECTED'))
);

CREATE TABLE competency_level (
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

CREATE TABLE learning_topic_level (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    topic_id BIGINT UNSIGNED NOT NULL,
    competency_level_id BIGINT UNSIGNED NOT NULL,
    outcome_vi TEXT NOT NULL,
    recommended_jlpt_level VARCHAR(10) NULL,
    external_key VARCHAR(100) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_competency_level (topic_id, competency_level_id),
    UNIQUE KEY uk_topic_level_external_key (external_key),
    CONSTRAINT fk_topic_level_topic FOREIGN KEY (topic_id) REFERENCES learning_topic(id) ON DELETE CASCADE,
    CONSTRAINT fk_topic_level_level FOREIGN KEY (competency_level_id) REFERENCES competency_level(id) ON DELETE RESTRICT,
    CONSTRAINT chk_topic_level_jlpt CHECK (recommended_jlpt_level IS NULL OR recommended_jlpt_level IN ('N5','N4','N3','N2','N1')),
    CONSTRAINT chk_topic_level_review CHECK (review_status IN ('DRAFT','REVIEWED','APPROVED','REJECTED'))
);

CREATE TABLE lesson_learning_scope (
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
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
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
    CONSTRAINT chk_lesson_scope_review CHECK (review_status IN ('DRAFT','REVIEWED','APPROVED','REJECTED'))
);

CREATE TABLE lesson_prerequisite (
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
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lesson_prerequisite_code (prerequisite_code),
    UNIQUE KEY uk_lesson_prerequisite_external_key (external_key),
    CONSTRAINT fk_lesson_prerequisite_lesson FOREIGN KEY (lesson_id) REFERENCES lesson(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_prerequisite_required_lesson FOREIGN KEY (required_lesson_id) REFERENCES lesson(id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_prerequisite_competency FOREIGN KEY (required_competency_id) REFERENCES learning_competency(id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_prerequisite_warmup FOREIGN KEY (warmup_lesson_id) REFERENCES lesson(id) ON DELETE RESTRICT,
    CONSTRAINT chk_lesson_prerequisite_type CHECK (prerequisite_type IN ('HARD_SEQUENCE','RECOMMENDED_FOUNDATION','JIT_WARMUP','OPTIONAL_ENRICHMENT')),
    CONSTRAINT chk_lesson_prerequisite_jlpt CHECK (required_jlpt_level IS NULL OR required_jlpt_level IN ('N5','N4','N3','N2','N1')),
    CONSTRAINT chk_lesson_prerequisite_review CHECK (review_status IN ('DRAFT','REVIEWED','APPROVED','REJECTED'))
);

CREATE TABLE learning_survey_question (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    goal_id BIGINT UNSIGNED NOT NULL,
    survey_question_code VARCHAR(100) NOT NULL,
    question_order INT UNSIGNED NOT NULL,
    question_vi TEXT NOT NULL,
    selection_type VARCHAR(20) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    external_key VARCHAR(100) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    PRIMARY KEY (id),
    UNIQUE KEY uk_survey_question_code (survey_question_code),
    UNIQUE KEY uk_survey_question_external_key (external_key),
    UNIQUE KEY uk_survey_question_order (goal_id, question_order),
    CONSTRAINT fk_survey_question_goal FOREIGN KEY (goal_id) REFERENCES learning_goal(id) ON DELETE CASCADE,
    CONSTRAINT chk_survey_selection_type CHECK (selection_type IN ('SINGLE','MULTIPLE')),
    CONSTRAINT chk_survey_question_review CHECK (review_status IN ('DRAFT','REVIEWED','APPROVED','REJECTED'))
);

CREATE TABLE learning_survey_option (
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
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    PRIMARY KEY (id),
    UNIQUE KEY uk_survey_option_code (survey_option_code),
    UNIQUE KEY uk_survey_option_external_key (external_key),
    UNIQUE KEY uk_survey_option_order (question_id, option_order),
    CONSTRAINT fk_survey_option_question FOREIGN KEY (question_id) REFERENCES learning_survey_question(id) ON DELETE CASCADE,
    CONSTRAINT fk_survey_option_topic FOREIGN KEY (topic_id) REFERENCES learning_topic(id) ON DELETE RESTRICT,
    CONSTRAINT fk_survey_option_competency FOREIGN KEY (competency_id) REFERENCES learning_competency(id) ON DELETE RESTRICT,
    CONSTRAINT chk_survey_option_mode CHECK (learning_mode IS NULL OR learning_mode IN ('FOUNDATION_FIRST','COMMUNICATION_FIRST','BALANCED')),
    CONSTRAINT chk_survey_option_review CHECK (review_status IN ('DRAFT','REVIEWED','APPROVED','REJECTED'))
);

CREATE TABLE user_goal_survey_submission (
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

CREATE TABLE user_goal_survey_selection (
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

CREATE TABLE assessment_question_usage (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    question_id BIGINT UNSIGNED NOT NULL,
    usage_code VARCHAR(40) NOT NULL,
    blueprint_code VARCHAR(100) NULL,
    section_code VARCHAR(100) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from DATETIME(6) NULL,
    effective_to DATETIME(6) NULL,
    external_key VARCHAR(100) NOT NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    PRIMARY KEY (id),
    UNIQUE KEY uk_assessment_usage_external_key (external_key),
    KEY idx_assessment_usage_selection (usage_code, blueprint_code, section_code, active),
    CONSTRAINT fk_assessment_usage_question FOREIGN KEY (question_id) REFERENCES assessment_question(id) ON DELETE CASCADE,
    CONSTRAINT chk_assessment_usage_code CHECK (usage_code IN ('JLPT_PLACEMENT','JLPT_MOCK_TEST','BRSE_FOUNDATION')),
    CONSTRAINT chk_assessment_usage_dates CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from),
    CONSTRAINT chk_assessment_usage_review CHECK (review_status IN ('DRAFT','REVIEWED','APPROVED','REJECTED'))
);
