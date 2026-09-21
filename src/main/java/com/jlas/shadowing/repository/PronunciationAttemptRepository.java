package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.PronunciationAttempt;
import com.jlas.shadowing.model.PronunciationPhonemeResult;
import com.jlas.shadowing.model.PronunciationWordResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class PronunciationAttemptRepository {
    private static final String ATTEMPT_COLUMNS = "id, user_id, video_id, subtitle_id, storage_key, "
            + "audio_content_type, audio_size_bytes, audio_duration_seconds, recognition_status, "
            + "reference_text, recognized_text, pronunciation_score, accuracy_score, fluency_score, "
            + "completeness_score, prosody_score, feedback, created_at";

    private final JdbcTemplate jdbc;

    public PronunciationAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long save(PronunciationAttempt attempt) {
        KeyHolder attemptKey = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pronunciation_attempt (user_id, video_id, subtitle_id, storage_key, "
                            + "audio_content_type, audio_size_bytes, audio_duration_seconds, recognition_status, "
                            + "reference_text, recognized_text, pronunciation_score, accuracy_score, fluency_score, "
                            + "completeness_score, prosody_score, feedback) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, attempt.getUserId());
            statement.setLong(2, attempt.getVideoId());
            statement.setLong(3, attempt.getSubtitleId());
            statement.setString(4, attempt.getStorageKey());
            statement.setString(5, attempt.getAudioContentType());
            statement.setLong(6, attempt.getAudioSizeBytes());
            statement.setDouble(7, attempt.getAudioDurationSeconds());
            statement.setString(8, attempt.getRecognitionStatus());
            statement.setString(9, attempt.getReferenceText());
            statement.setString(10, attempt.getRecognizedText());
            setDouble(statement, 11, attempt.getPronunciationScore());
            setDouble(statement, 12, attempt.getAccuracyScore());
            setDouble(statement, 13, attempt.getFluencyScore());
            setDouble(statement, 14, attempt.getCompletenessScore());
            setDouble(statement, 15, attempt.getProsodyScore());
            statement.setString(16, attempt.getFeedback());
            return statement;
        }, attemptKey);

        long attemptId = requireKey(attemptKey);
        attempt.setId(attemptId);
        for (int wordPosition = 0; wordPosition < attempt.getWords().size(); wordPosition++) {
            PronunciationWordResult word = attempt.getWords().get(wordPosition);
            word.setAttemptId(attemptId);
            word.setPosition(wordPosition);
            KeyHolder wordKey = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO pronunciation_word_result "
                                + "(attempt_id, position_index, word_text, accuracy_score, error_type) VALUES (?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, attemptId);
                statement.setInt(2, word.getPosition());
                statement.setString(3, word.getWord());
                setDouble(statement, 4, word.getAccuracyScore());
                statement.setString(5, word.getErrorType());
                return statement;
            }, wordKey);
            long wordId = requireKey(wordKey);
            word.setId(wordId);
            for (int phonemePosition = 0; phonemePosition < word.getPhonemes().size(); phonemePosition++) {
                PronunciationPhonemeResult phoneme = word.getPhonemes().get(phonemePosition);
                phoneme.setWordResultId(wordId);
                phoneme.setPosition(phonemePosition);
                jdbc.update("INSERT INTO pronunciation_phoneme_result "
                                + "(word_result_id, position_index, phoneme_text, accuracy_score) VALUES (?, ?, ?, ?)",
                        wordId, phonemePosition, phoneme.getPhoneme(), phoneme.getAccuracyScore());
            }
        }
        return attemptId;
    }

    public List<PronunciationAttempt> findLatestByVideo(Long userId, Long videoId) {
        List<PronunciationAttempt> attempts = jdbc.query(
                "SELECT " + ATTEMPT_COLUMNS + " FROM pronunciation_attempt pa "
                        + "JOIN (SELECT subtitle_id, MAX(id) AS latest_id FROM pronunciation_attempt "
                        + "WHERE user_id = ? AND video_id = ? GROUP BY subtitle_id) latest "
                        + "ON latest.latest_id = pa.id ORDER BY pa.subtitle_id",
                (rs, rowNum) -> mapAttempt(rs), userId, videoId);
        hydrate(attempts);
        return attempts;
    }

    public List<PronunciationAttempt> findHistoryByVideo(Long userId, Long videoId) {
        List<PronunciationAttempt> attempts = jdbc.query(
                "SELECT " + ATTEMPT_COLUMNS + " FROM pronunciation_attempt "
                        + "WHERE user_id = ? AND video_id = ? ORDER BY id DESC",
                (rs, rowNum) -> mapAttempt(rs), userId, videoId);
        hydrate(attempts);
        return attempts;
    }

    public PronunciationAttempt findOwned(Long userId, Long attemptId) {
        List<PronunciationAttempt> attempts = jdbc.query(
                "SELECT " + ATTEMPT_COLUMNS + " FROM pronunciation_attempt WHERE id = ? AND user_id = ?",
                (rs, rowNum) -> mapAttempt(rs), attemptId, userId);
        hydrate(attempts);
        return attempts.isEmpty() ? null : attempts.get(0);
    }

    public void deleteOwned(Long userId, Long attemptId) {
        jdbc.update("DELETE FROM pronunciation_attempt WHERE id = ? AND user_id = ?", attemptId, userId);
    }

    private void hydrate(List<PronunciationAttempt> attempts) {
        if (attempts.isEmpty()) return;
        Map<Long, PronunciationAttempt> byId = new HashMap<>();
        List<Long> attemptIds = new ArrayList<>();
        attempts.forEach(attempt -> {
            byId.put(attempt.getId(), attempt);
            attemptIds.add(attempt.getId());
        });
        String placeholders = placeholders(attemptIds.size());
        Map<Long, PronunciationWordResult> wordsById = new HashMap<>();
        jdbc.query("SELECT id, attempt_id, position_index, word_text, accuracy_score, error_type "
                        + "FROM pronunciation_word_result WHERE attempt_id IN (" + placeholders + ") "
                        + "ORDER BY attempt_id, position_index",
                (rs, rowNum) -> {
                    PronunciationWordResult word = new PronunciationWordResult();
                    word.setId(rs.getLong("id"));
                    word.setAttemptId(rs.getLong("attempt_id"));
                    word.setPosition(rs.getInt("position_index"));
                    word.setWord(rs.getString("word_text"));
                    word.setAccuracyScore(number(rs.getObject("accuracy_score")));
                    word.setErrorType(rs.getString("error_type"));
                    wordsById.put(word.getId(), word);
                    PronunciationAttempt parent = byId.get(word.getAttemptId());
                    if (parent != null) parent.getWords().add(word);
                    return word;
                }, attemptIds.toArray());
        if (wordsById.isEmpty()) return;
        List<Long> wordIds = new ArrayList<>(wordsById.keySet());
        jdbc.query("SELECT id, word_result_id, position_index, phoneme_text, accuracy_score "
                        + "FROM pronunciation_phoneme_result WHERE word_result_id IN (" + placeholders(wordIds.size()) + ") "
                        + "ORDER BY word_result_id, position_index",
                (rs, rowNum) -> {
                    PronunciationPhonemeResult phoneme = new PronunciationPhonemeResult();
                    phoneme.setId(rs.getLong("id"));
                    phoneme.setWordResultId(rs.getLong("word_result_id"));
                    phoneme.setPosition(rs.getInt("position_index"));
                    phoneme.setPhoneme(rs.getString("phoneme_text"));
                    phoneme.setAccuracyScore(number(rs.getObject("accuracy_score")));
                    PronunciationWordResult parent = wordsById.get(phoneme.getWordResultId());
                    if (parent != null) parent.getPhonemes().add(phoneme);
                    return phoneme;
                }, wordIds.toArray());
    }

    private static PronunciationAttempt mapAttempt(java.sql.ResultSet rs) throws java.sql.SQLException {
        PronunciationAttempt attempt = new PronunciationAttempt();
        attempt.setId(rs.getLong("id"));
        attempt.setUserId(rs.getLong("user_id"));
        attempt.setVideoId(rs.getLong("video_id"));
        attempt.setSubtitleId(rs.getLong("subtitle_id"));
        attempt.setStorageKey(rs.getString("storage_key"));
        attempt.setAudioContentType(rs.getString("audio_content_type"));
        attempt.setAudioSizeBytes(rs.getLong("audio_size_bytes"));
        attempt.setAudioDurationSeconds(number(rs.getObject("audio_duration_seconds")));
        attempt.setRecognitionStatus(rs.getString("recognition_status"));
        attempt.setReferenceText(rs.getString("reference_text"));
        attempt.setRecognizedText(rs.getString("recognized_text"));
        attempt.setPronunciationScore(number(rs.getObject("pronunciation_score")));
        attempt.setAccuracyScore(number(rs.getObject("accuracy_score")));
        attempt.setFluencyScore(number(rs.getObject("fluency_score")));
        attempt.setCompletenessScore(number(rs.getObject("completeness_score")));
        attempt.setProsodyScore(number(rs.getObject("prosody_score")));
        attempt.setFeedback(rs.getString("feedback"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) attempt.setCreatedAt(createdAt.toLocalDateTime());
        return attempt;
    }

    private static void setDouble(PreparedStatement statement, int index, Double value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.DECIMAL);
        else statement.setDouble(index, value);
    }

    private static long requireKey(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Không tạo được id bản ghi phát âm");
        return key.longValue();
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }
}
