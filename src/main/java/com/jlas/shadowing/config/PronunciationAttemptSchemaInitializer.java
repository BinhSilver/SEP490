package com.jlas.shadowing.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Local-development compatibility; production should apply sql/pronunciation_attempt.sql explicitly. */
@Component
@Profile("!test")
@Order(2)
public class PronunciationAttemptSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public PronunciationAttemptSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS pronunciation_attempt ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, "
                + "video_id BIGINT UNSIGNED NOT NULL, subtitle_id BIGINT UNSIGNED NOT NULL, storage_key VARCHAR(700) NOT NULL, "
                + "audio_content_type VARCHAR(100) NOT NULL, audio_size_bytes BIGINT NOT NULL, "
                + "audio_duration_seconds DECIMAL(8,3) NOT NULL, recognition_status VARCHAR(30) NOT NULL, "
                + "reference_text TEXT NOT NULL, recognized_text TEXT NULL, pronunciation_score DECIMAL(5,2) NULL, "
                + "accuracy_score DECIMAL(5,2) NULL, fluency_score DECIMAL(5,2) NULL, "
                + "completeness_score DECIMAL(5,2) NULL, prosody_score DECIMAL(5,2) NULL, "
                + "feedback VARCHAR(1000) NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                + "CONSTRAINT fk_pronunciation_attempt_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, "
                + "CONSTRAINT fk_pronunciation_attempt_video FOREIGN KEY (video_id) REFERENCES video(id) ON DELETE CASCADE, "
                + "CONSTRAINT fk_pronunciation_attempt_subtitle FOREIGN KEY (subtitle_id) REFERENCES subtitle(id) ON DELETE CASCADE, "
                + "INDEX idx_pronunciation_attempt_user_video (user_id, video_id, id), "
                + "INDEX idx_pronunciation_attempt_user_subtitle (user_id, subtitle_id, id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS pronunciation_word_result ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, attempt_id BIGINT UNSIGNED NOT NULL, "
                + "position_index INT NOT NULL, word_text VARCHAR(255) NOT NULL, accuracy_score DECIMAL(5,2) NULL, "
                + "error_type VARCHAR(40) NULL, CONSTRAINT fk_pronunciation_word_attempt FOREIGN KEY (attempt_id) "
                + "REFERENCES pronunciation_attempt(id) ON DELETE CASCADE, "
                + "UNIQUE KEY uk_pronunciation_word_position (attempt_id, position_index), "
                + "INDEX idx_pronunciation_word_attempt (attempt_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS pronunciation_phoneme_result ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, word_result_id BIGINT UNSIGNED NOT NULL, "
                + "position_index INT NOT NULL, phoneme_text VARCHAR(100) NOT NULL, accuracy_score DECIMAL(5,2) NULL, "
                + "CONSTRAINT fk_pronunciation_phoneme_word FOREIGN KEY (word_result_id) "
                + "REFERENCES pronunciation_word_result(id) ON DELETE CASCADE, "
                + "UNIQUE KEY uk_pronunciation_phoneme_position (word_result_id, position_index), "
                + "INDEX idx_pronunciation_phoneme_word (word_result_id))");
    }
}
