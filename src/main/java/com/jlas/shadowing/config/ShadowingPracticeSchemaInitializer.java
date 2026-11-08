package com.jlas.shadowing.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Local compatibility. Shared deployments should apply sql/shadowing_practice.sql explicitly. */
@Component
@Profile("!test")
@Order(3)
public class ShadowingPracticeSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public ShadowingPracticeSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_exercise_analysis ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, subtitle_id BIGINT UNSIGNED NOT NULL, "
                + "source_hash CHAR(64) NOT NULL, prompt_version VARCHAR(40) NOT NULL, "
                + "vocabulary_factor TINYINT UNSIGNED NOT NULL DEFAULT 0, grammar_factor TINYINT UNSIGNED NOT NULL DEFAULT 0, "
                + "listening_speed_factor TINYINT UNSIGNED NOT NULL DEFAULT 0, script_complexity_factor TINYINT UNSIGNED NOT NULL DEFAULT 0, "
                + "listening_traps_factor TINYINT UNSIGNED NOT NULL DEFAULT 0, sentence_difficulty TINYINT UNSIGNED NOT NULL, "
                + "analysis_source VARCHAR(20) NOT NULL, analyzed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), "
                + "CONSTRAINT fk_shadowing_exercise_analysis_subtitle FOREIGN KEY (subtitle_id) REFERENCES subtitle(id) ON DELETE CASCADE, "
                + "UNIQUE KEY uk_shadowing_exercise_analysis_subtitle (subtitle_id), "
                + "INDEX idx_shadowing_exercise_analysis_difficulty (sentence_difficulty, subtitle_id), "
                + "INDEX idx_shadowing_exercise_analysis_version (prompt_version, source_hash))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_cloze_candidate ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, analysis_id BIGINT UNSIGNED NOT NULL, "
                + "candidate_order SMALLINT UNSIGNED NOT NULL, answer_text VARCHAR(500) NOT NULL, reading_text VARCHAR(500) NULL, "
                + "occurrence_index SMALLINT UNSIGNED NOT NULL DEFAULT 1, candidate_type VARCHAR(30) NOT NULL, jlpt_level VARCHAR(10) NULL, "
                + "importance_factor TINYINT UNSIGNED NOT NULL DEFAULT 0, grammar_complexity_factor TINYINT UNSIGNED NOT NULL DEFAULT 0, "
                + "context_predictability_factor TINYINT UNSIGNED NOT NULL DEFAULT 0, acoustic_confusability_factor TINYINT UNSIGNED NOT NULL DEFAULT 0, "
                + "confidence_factor TINYINT UNSIGNED NOT NULL DEFAULT 0, difficulty_score TINYINT UNSIGNED NOT NULL, "
                + "generation_source VARCHAR(20) NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "CONSTRAINT fk_shadowing_cloze_candidate_analysis FOREIGN KEY (analysis_id) REFERENCES shadowing_exercise_analysis(id) ON DELETE CASCADE, "
                + "UNIQUE KEY uk_shadowing_cloze_candidate_order (analysis_id, candidate_order), "
                + "INDEX idx_shadowing_cloze_candidate_difficulty (difficulty_score, analysis_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_practice_session ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, video_id BIGINT UNSIGNED NOT NULL, "
                + "practice_mode VARCHAR(20) NOT NULL, question_order VARCHAR(20) NOT NULL, requested_count INT UNSIGNED NOT NULL, "
                + "total_questions INT UNSIGNED NOT NULL, current_position INT UNSIGNED NOT NULL DEFAULT 0, "
                + "correct_count INT UNSIGNED NOT NULL DEFAULT 0, score_total INT UNSIGNED NOT NULL DEFAULT 0, random_seed BIGINT NOT NULL, "
                + "status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), completed_at TIMESTAMP(6) NULL, "
                + "CONSTRAINT fk_shadowing_practice_session_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, "
                + "CONSTRAINT fk_shadowing_practice_session_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE, "
                + "INDEX idx_shadowing_practice_session_user_video (user_id, video_id, started_at), "
                + "INDEX idx_shadowing_practice_session_user_status (user_id, status, started_at))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_practice_item ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, session_id BIGINT UNSIGNED NOT NULL, subtitle_id BIGINT UNSIGNED NOT NULL, "
                + "cloze_candidate_id BIGINT UNSIGNED NULL, position_index INT UNSIGNED NOT NULL, difficulty_score TINYINT UNSIGNED NOT NULL, "
                + "answered_at TIMESTAMP(6) NULL, "
                + "CONSTRAINT fk_shadowing_practice_item_session FOREIGN KEY (session_id) REFERENCES shadowing_practice_session(id) ON DELETE CASCADE, "
                + "CONSTRAINT fk_shadowing_practice_item_subtitle FOREIGN KEY (subtitle_id) REFERENCES subtitle(id) ON DELETE CASCADE, "
                + "CONSTRAINT fk_shadowing_practice_item_candidate FOREIGN KEY (cloze_candidate_id) REFERENCES shadowing_cloze_candidate(id) ON DELETE SET NULL, "
                + "UNIQUE KEY uk_shadowing_practice_item_position (session_id, position_index), "
                + "INDEX idx_shadowing_practice_item_subtitle (subtitle_id), INDEX idx_shadowing_practice_item_candidate (cloze_candidate_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_practice_answer ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, practice_item_id BIGINT UNSIGNED NOT NULL, "
                + "attempt_number SMALLINT UNSIGNED NOT NULL, user_answer TEXT NOT NULL, normalized_answer TEXT NOT NULL, "
                + "answer_result VARCHAR(20) NOT NULL, score TINYINT UNSIGNED NOT NULL, feedback VARCHAR(1000) NOT NULL, "
                + "answered_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "CONSTRAINT fk_shadowing_practice_answer_item FOREIGN KEY (practice_item_id) REFERENCES shadowing_practice_item(id) ON DELETE CASCADE, "
                + "UNIQUE KEY uk_shadowing_practice_answer_attempt (practice_item_id, attempt_number), "
                + "INDEX idx_shadowing_practice_answer_result (answer_result, answered_at))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_preparation_job ("
                + "video_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, levels_csv VARCHAR(40) NOT NULL DEFAULT '', "
                + "kinds_csv VARCHAR(240) NOT NULL DEFAULT '', status VARCHAR(20) NOT NULL DEFAULT 'IDLE', "
                + "progress TINYINT UNSIGNED NOT NULL DEFAULT 0, error_message VARCHAR(500) NULL, "
                + "suggestions_csv VARCHAR(240) NULL, suggestion_hash CHAR(64) NULL, "
                + "targets_csv VARCHAR(300) NULL, capacities_csv VARCHAR(300) NULL, "
                + "analysis_version VARCHAR(40) NULL, evidence_plans_json MEDIUMTEXT NULL, "
                + "suggested_jlpt_level VARCHAR(2) NULL, selected_jlpt_level VARCHAR(2) NULL, "
                + "claim_token CHAR(36) NULL, lease_until TIMESTAMP(6) NULL, "
                + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "CONSTRAINT fk_shadowing_preparation_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE, "
                + "INDEX idx_shadowing_preparation_status (status, updated_at), "
                + "UNIQUE KEY uk_shadowing_preparation_claim (claim_token))");
        for (String column : new String[] {"suggested_jlpt_level", "selected_jlpt_level", "targets_csv", "capacities_csv", "analysis_version", "evidence_plans_json"}) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shadowing_preparation_job' "
                    + "AND COLUMN_NAME=?", Integer.class, column);
            if (count != null && count == 0) {
                jdbc.execute("ALTER TABLE shadowing_preparation_job ADD COLUMN " + column
                        + (column.equals("evidence_plans_json") ? " MEDIUMTEXT NULL"
                        : column.equals("analysis_version") ? " VARCHAR(40) NULL"
                        : column.endsWith("_csv") ? " VARCHAR(300) NULL" : " VARCHAR(2) NULL"));
            }
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_question_bank ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "video_id BIGINT UNSIGNED NOT NULL, subtitle_id BIGINT UNSIGNED NOT NULL, "
                + "source_ids_json TEXT NOT NULL, kind VARCHAR(40) NOT NULL, level_code VARCHAR(10) NOT NULL, "
                + "prompt_text TEXT NOT NULL, answer_text TEXT NOT NULL, choices_json TEXT NOT NULL, "
                + "explanation TEXT NULL, start_time DECIMAL(10,3) NOT NULL, end_time DECIMAL(10,3) NOT NULL, "
                + "source_hash CHAR(64) NOT NULL, occurrence_index SMALLINT UNSIGNED NOT NULL DEFAULT 1, "
                + "content_hash CHAR(64) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', "
                + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), "
                + "CONSTRAINT fk_shadowing_question_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE, "
                + "CONSTRAINT fk_shadowing_question_subtitle FOREIGN KEY (subtitle_id) REFERENCES subtitle(id) ON DELETE CASCADE, "
                + "UNIQUE KEY uk_shadowing_question_content (video_id, content_hash), "
                + "INDEX idx_shadowing_question_pick (video_id, status, level_code, kind))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_question_contribution ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "video_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL, "
                + "created_on DATE NOT NULL, "
                + "CONSTRAINT fk_shadowing_contribution_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE, "
                + "UNIQUE KEY uk_shadowing_contribution_day (video_id, user_id, created_on))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS shadowing_question_generation_issue ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "video_id BIGINT UNSIGNED NOT NULL, kind VARCHAR(40) NULL, level_code VARCHAR(10) NULL, "
                + "prompt_text TEXT NULL, answer_text TEXT NULL, choices_json TEXT NULL, "
                + "source_ids_json TEXT NULL, supplied_ids_json TEXT NULL, evidence_json MEDIUMTEXT NULL, "
                + "reason VARCHAR(300) NOT NULL, "
                + "start_time DECIMAL(10,3) NULL, attempt_number TINYINT UNSIGNED NOT NULL, "
                + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "CONSTRAINT fk_shadowing_generation_issue_video FOREIGN KEY (video_id) "
                + "REFERENCES video(id) ON DELETE CASCADE, "
                + "INDEX idx_shadowing_generation_issue_video (video_id, id))");
        for (String column : new String[] {"choices_json", "source_ids_json", "supplied_ids_json", "evidence_json"}) {
        Integer issueChoices = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shadowing_question_generation_issue' "
                + "AND COLUMN_NAME=?", Integer.class, column);
        if (issueChoices != null && issueChoices == 0)
            jdbc.execute("ALTER TABLE shadowing_question_generation_issue ADD COLUMN " + column
                    + (column.equals("evidence_json") ? " MEDIUMTEXT NULL" : " TEXT NULL"));
        }
        Integer columnCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shadowing_practice_item' "
                + "AND COLUMN_NAME='prepared_question_id'", Integer.class);
        if (columnCount != null && columnCount == 0) {
            jdbc.execute("ALTER TABLE shadowing_practice_item ADD COLUMN prepared_question_id BIGINT UNSIGNED NULL, "
                    + "ADD INDEX idx_shadowing_practice_item_prepared (prepared_question_id)");
        }
    }
}
