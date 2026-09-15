package com.jlas.shadowing.repository;

import com.jlas.shadowing.dto.practice.PracticeStopView;
import com.jlas.shadowing.model.practice.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class ShadowingPracticeRepository {
    private final JdbcTemplate jdbc;

    public ShadowingPracticeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertSession(ShadowingPracticeSession session) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO shadowing_practice_session "
                            + "(user_id, video_id, practice_mode, question_order, requested_count, total_questions, "
                            + "current_position, correct_count, score_total, random_seed, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, ?, 'ACTIVE')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, session.getUserId());
            statement.setLong(2, session.getVideoId());
            statement.setString(3, session.getMode().name());
            statement.setString(4, session.getOrder().name());
            statement.setInt(5, session.getRequestedCount());
            statement.setInt(6, session.getTotalQuestions());
            statement.setLong(7, session.getRandomSeed());
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Không tạo được phiên luyện nghe");
        session.setId(key.longValue());
        return session.getId();
    }

    @Transactional
    public long createSession(ShadowingPracticeSession session, List<ShadowingPracticeItem> items) {
        long sessionId = insertSession(session);
        items.forEach(item -> item.setSessionId(sessionId));
        insertItems(sessionId, items);
        return sessionId;
    }

    public void insertItems(long sessionId, List<ShadowingPracticeItem> items) {
        for (ShadowingPracticeItem item : items) {
            jdbc.update("INSERT INTO shadowing_practice_item "
                            + "(session_id, subtitle_id, cloze_candidate_id, position_index, difficulty_score) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    sessionId, item.getSubtitleId(), item.getClozeCandidateId(),
                    item.getPosition(), item.getDifficultyScore());
        }
    }

    public ShadowingPracticeSession findOwnedSession(long sessionId, long userId) {
        return jdbc.query("SELECT id, user_id, video_id, practice_mode, question_order, requested_count, "
                        + "total_questions, current_position, correct_count, score_total, random_seed, status, "
                        + "started_at, completed_at FROM shadowing_practice_session WHERE id = ? AND user_id = ?",
                rs -> rs.next() ? mapSession(rs) : null, sessionId, userId);
    }

    public ShadowingPracticeItem findItem(long sessionId, int position) {
        return jdbc.query("SELECT id, session_id, subtitle_id, cloze_candidate_id, position_index, difficulty_score, answered_at "
                        + "FROM shadowing_practice_item WHERE session_id = ? AND position_index = ?",
                rs -> {
                    if (!rs.next()) return null;
                    ShadowingPracticeItem item = new ShadowingPracticeItem();
                    item.setId(rs.getLong("id"));
                    item.setSessionId(rs.getLong("session_id"));
                    item.setSubtitleId(rs.getLong("subtitle_id"));
                    long candidateId = rs.getLong("cloze_candidate_id");
                    item.setClozeCandidateId(rs.wasNull() ? null : candidateId);
                    item.setPosition(rs.getInt("position_index"));
                    item.setDifficultyScore(rs.getInt("difficulty_score"));
                    Timestamp answered = rs.getTimestamp("answered_at");
                    item.setAnsweredAt(answered == null ? null : answered.toLocalDateTime());
                    return item;
                }, sessionId, position);
    }

    public List<PracticeStopView> findStops(long sessionId) {
        return jdbc.query("SELECT spi.subtitle_id, spi.position_index, spi.difficulty_score, "
                        + "s.start_time, s.end_time FROM shadowing_practice_item spi "
                        + "JOIN subtitle s ON s.id = spi.subtitle_id "
                        + "WHERE spi.session_id = ? ORDER BY spi.position_index",
                (rs, rowNum) -> {
                    int difficulty = rs.getInt("difficulty_score");
                    return new PracticeStopView(
                            rs.getLong("subtitle_id"),
                            rs.getInt("position_index") + 1,
                            difficulty,
                            difficultyLabel(difficulty),
                            rs.getDouble("start_time"),
                            rs.getDouble("end_time"));
                }, sessionId);
    }

    public ShadowingClozeCandidate findCandidate(long candidateId) {
        return jdbc.query("SELECT id, analysis_id, candidate_order, answer_text, reading_text, occurrence_index, candidate_type, "
                        + "jlpt_level, importance_factor, grammar_complexity_factor, context_predictability_factor, "
                        + "acoustic_confusability_factor, confidence_factor, difficulty_score, generation_source "
                        + "FROM shadowing_cloze_candidate WHERE id = ?",
                rs -> {
                    if (!rs.next()) return null;
                    ShadowingClozeCandidate candidate = new ShadowingClozeCandidate();
                    candidate.setId(rs.getLong("id"));
                    candidate.setAnalysisId(rs.getLong("analysis_id"));
                    candidate.setCandidateOrder(rs.getInt("candidate_order"));
                    candidate.setAnswerText(rs.getString("answer_text"));
                    candidate.setReadingText(rs.getString("reading_text"));
                    candidate.setOccurrenceIndex(rs.getInt("occurrence_index"));
                    candidate.setCandidateType(rs.getString("candidate_type"));
                    candidate.setJlptLevel(rs.getString("jlpt_level"));
                    candidate.setImportanceFactor(rs.getInt("importance_factor"));
                    candidate.setGrammarComplexityFactor(rs.getInt("grammar_complexity_factor"));
                    candidate.setContextPredictabilityFactor(rs.getInt("context_predictability_factor"));
                    candidate.setAcousticConfusabilityFactor(rs.getInt("acoustic_confusability_factor"));
                    candidate.setConfidenceFactor(rs.getInt("confidence_factor"));
                    candidate.setDifficultyScore(rs.getInt("difficulty_score"));
                    candidate.setGenerationSource(rs.getString("generation_source"));
                    return candidate;
                }, candidateId);
    }

    public ShadowingPracticeAnswer findAnswer(long itemId) {
        return jdbc.query("SELECT id, practice_item_id, attempt_number, user_answer, normalized_answer, answer_result, "
                        + "score, feedback, answered_at FROM shadowing_practice_answer "
                        + "WHERE practice_item_id = ? ORDER BY attempt_number DESC LIMIT 1",
                rs -> {
                    if (!rs.next()) return null;
                    ShadowingPracticeAnswer answer = new ShadowingPracticeAnswer();
                    answer.setId(rs.getLong("id"));
                    answer.setPracticeItemId(rs.getLong("practice_item_id"));
                    answer.setAttemptNumber(rs.getInt("attempt_number"));
                    answer.setUserAnswer(rs.getString("user_answer"));
                    answer.setNormalizedAnswer(rs.getString("normalized_answer"));
                    answer.setResult(AnswerResult.valueOf(rs.getString("answer_result")));
                    answer.setScore(rs.getInt("score"));
                    answer.setFeedback(rs.getString("feedback"));
                    Timestamp answered = rs.getTimestamp("answered_at");
                    answer.setAnsweredAt(answered == null ? null : answered.toLocalDateTime());
                    return answer;
                }, itemId);
    }

    public void insertAnswer(ShadowingPracticeAnswer answer) {
        jdbc.update("INSERT INTO shadowing_practice_answer "
                        + "(practice_item_id, attempt_number, user_answer, normalized_answer, answer_result, score, feedback) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                answer.getPracticeItemId(), answer.getAttemptNumber(), answer.getUserAnswer(),
                answer.getNormalizedAnswer(), answer.getResult().name(), answer.getScore(), answer.getFeedback());
        jdbc.update("UPDATE shadowing_practice_item SET answered_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                answer.getPracticeItemId());
    }

    public void addSessionScore(long sessionId, int score, boolean correct) {
        jdbc.update("UPDATE shadowing_practice_session SET score_total = score_total + ?, "
                        + "correct_count = correct_count + ? WHERE id = ?",
                score, correct ? 1 : 0, sessionId);
    }

    public void moveTo(long sessionId, int position, boolean completed) {
        if (completed) {
            jdbc.update("UPDATE shadowing_practice_session SET current_position = ?, status = 'COMPLETED', "
                            + "completed_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                    position, sessionId);
        } else {
            jdbc.update("UPDATE shadowing_practice_session SET current_position = ? WHERE id = ?", position, sessionId);
        }
    }

    private static ShadowingPracticeSession mapSession(java.sql.ResultSet rs) throws java.sql.SQLException {
        ShadowingPracticeSession session = new ShadowingPracticeSession();
        session.setId(rs.getLong("id"));
        session.setUserId(rs.getLong("user_id"));
        session.setVideoId(rs.getLong("video_id"));
        session.setMode(PracticeMode.valueOf(rs.getString("practice_mode")));
        session.setOrder(PracticeOrder.valueOf(rs.getString("question_order")));
        session.setRequestedCount(rs.getInt("requested_count"));
        session.setTotalQuestions(rs.getInt("total_questions"));
        session.setCurrentPosition(rs.getInt("current_position"));
        session.setCorrectCount(rs.getInt("correct_count"));
        session.setScoreTotal(rs.getInt("score_total"));
        session.setRandomSeed(rs.getLong("random_seed"));
        session.setStatus(PracticeSessionStatus.valueOf(rs.getString("status")));
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp completed = rs.getTimestamp("completed_at");
        session.setStartedAt(started == null ? null : started.toLocalDateTime());
        session.setCompletedAt(completed == null ? null : completed.toLocalDateTime());
        return session;
    }

    private static String difficultyLabel(int score) {
        if (score < 25) return "Rất dễ";
        if (score < 45) return "Dễ";
        if (score < 65) return "Trung bình";
        if (score < 80) return "Khó";
        return "Rất khó";
    }
}
