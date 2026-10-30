package com.jlas.assessment.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.assessment.dto.JlptHistoryEntryResponse;
import com.jlas.assessment.dto.JlptHistoryDetailResponse;
import com.jlas.assessment.dto.JlptHistoryPageResponse;
import com.jlas.assessment.dto.JlptHistorySubmitRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class JlptHistoryService {
    private static final TypeReference<List<Integer>> ORDER_TYPE = new TypeReference<>() {};
    private static final Map<String, Integer> PASSING_SCORES = Map.of(
            "N1", 100, "N2", 90, "N3", 95, "N4", 90, "N5", 80);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JlptWeaknessNotebookService weaknessNotebook;

    public JlptHistoryService(JdbcTemplate jdbc, ObjectMapper objectMapper, JlptWeaknessNotebookService weaknessNotebook) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.weaknessNotebook = weaknessNotebook;
    }

    @Transactional
    public JlptHistoryEntryResponse save(Long userId, JlptHistorySubmitRequest request) {
        if (!isUuid(request.submissionId())) throw badRequest("Mã lần nộp không hợp lệ.");
        List<Map<String, Object>> exams = jdbc.queryForList("""
                SELECT id, exam_code, title, jlpt_level
                FROM jlpt_exam
                WHERE exam_code = ? AND status IN ('DRAFT', 'PUBLISHED')
                """, request.examCode());
        if (exams.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đề JLPT.");
        Map<String, Object> exam = exams.getFirst();
        long examId = ((Number) exam.get("id")).longValue();
        String level = (String) exam.get("jlpt_level");

        String partClause = request.partCode() == null ? "" : " AND p.part_code = ?";
        String sql = """
                SELECT q.id, p.part_code, m.format_code, q.correct_option, q.correct_order,q.target_vocabulary_id
                FROM jlpt_question q
                JOIN jlpt_mondai m ON m.id = q.mondai_id
                JOIN jlpt_part p ON p.id = m.part_id
                WHERE p.exam_id = ?
                """ + partClause + """
                  AND NOT (p.part_code = 'listening' AND
                      REPLACE(REPLACE(TRIM(q.question_text), ' ', ''), '　', '') IN
                      ('れい','例','(れい)','（れい）','(例)','（例）','(例(れい))','（例（れい））'))
                ORDER BY p.order_index, m.order_index, q.order_index
                """;
        List<Question> questions = request.partCode() == null
                ? jdbc.query(sql, (rs, row) -> question(rs), examId)
                : jdbc.query(sql, (rs, row) -> question(rs), examId, request.partCode());
        if (questions.isEmpty()) throw badRequest("Đề hoặc phần thi này không có câu hỏi.");

        Map<Long, Integer> answers = request.answers() == null ? Map.of() : request.answers();
        Map<Long, List<Integer>> sentenceOrders = request.sentenceOrders() == null ? Map.of() : request.sentenceOrders();
        Set<Long> allowedIds = new HashSet<>();
        questions.forEach(question -> allowedIds.add(question.id()));
        if (!allowedIds.containsAll(answers.keySet()) || !allowedIds.containsAll(sentenceOrders.keySet())) {
            throw badRequest("Câu trả lời không thuộc đề hoặc phần thi đã chọn.");
        }
        answers.forEach((id, value) -> {
            if (id == null || value == null || value < 1 || value > 4) throw badRequest("Lựa chọn đáp án không hợp lệ.");
        });

        int correct = 0;
        int answered = 0;
        Map<String, PartScore> partScores = new HashMap<>();
        for (Question question : questions) {
            partScores.computeIfAbsent(scoringSection(level, request.partCode(), question), ignored -> new PartScore()).total++;
            Integer selected = answers.get(question.id());
            List<Integer> order = sentenceOrders.get(question.id());
            List<Integer> completeOrder = isCompleteOrder(order) ? order : null;
            boolean hasAnswer = completeOrder != null || selected != null;
            boolean isCorrect = "PRACTICE".equals(request.mode()) && question.correctOrder() != null
                    ? completeOrder != null && completeOrder.equals(question.correctOrder())
                    : selected != null && question.correctOption() != null
                            && selected.equals(question.correctOption());
            if (hasAnswer) answered++;
            if (isCorrect) {
                correct++;
                partScores.get(scoringSection(level, request.partCode(), question)).correct++;
            }
            if (!isCorrect && selected != null && "vocabulary".equals(question.partCode()))
                weaknessNotebook.recordWrongAnswer(userId, question.id(), selected);
        }
        boolean fullAttempt = request.partCode() == null;
        Map<String, Integer> sectionScores = new HashMap<>();
        int totalScore = 0;
        for (Map.Entry<String, PartScore> entry : partScores.entrySet()) {
            int sectionMaximum = sectionMaximum(level, entry.getKey(), fullAttempt);
            int sectionScore = (int) Math.round(entry.getValue().correct * (double) sectionMaximum / entry.getValue().total);
            sectionScores.put(entry.getKey(), sectionScore);
            totalScore += sectionScore;
        }
        boolean passed = fullAttempt && passes(level, totalScore, sectionScores);
        BigDecimal scorePercent = BigDecimal.valueOf(correct * 100.0 / questions.size()).setScale(2, RoundingMode.HALF_UP);

        jdbc.update("""
                INSERT INTO jlpt_attempt_history
                    (user_id, submission_id, exam_code, exam_title, jlpt_level, mode, part_code,
                     correct_answers, answered_questions, total_questions, score_percent, passed, submitted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE submission_id = VALUES(submission_id)
                """, userId, request.submissionId(), request.examCode(), exam.get("title"), level,
                request.mode(), request.partCode(), correct, answered, questions.size(), scorePercent, passed);
        jdbc.update("""
                INSERT INTO jlpt_attempt_answer_snapshot (attempt_id, answers_json, sentence_orders_json)
                SELECT id, ?, ? FROM jlpt_attempt_history WHERE user_id = ? AND submission_id = ?
                ON DUPLICATE KEY UPDATE answers_json = VALUES(answers_json),
                                        sentence_orders_json = VALUES(sentence_orders_json)
                """, toJson(answers), toJson(sentenceOrders), userId, request.submissionId());
        jdbc.update("""
                INSERT INTO jlpt_attempt_score_snapshot (attempt_id, total_score, section_scores_json)
                SELECT id, ?, ? FROM jlpt_attempt_history WHERE user_id = ? AND submission_id = ?
                ON DUPLICATE KEY UPDATE total_score = VALUES(total_score),
                                        section_scores_json = VALUES(section_scores_json)
                """, totalScore, toJson(sectionScores), userId, request.submissionId());
        return findBySubmissionId(userId, request.submissionId());
    }

    @Transactional(readOnly = true)
    public JlptHistoryPageResponse list(Long userId, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 50));
        int safePage = Math.max(0, Math.min(page, 100_000));
        int offset = safePage * safeSize;
        List<JlptHistoryEntryResponse> rows = jdbc.query("""
                SELECT h.id, h.exam_code, h.exam_title, h.jlpt_level, h.mode, h.part_code,
                       h.correct_answers, h.answered_questions, h.total_questions, h.score_percent, h.passed,
                       h.submitted_at, ss.total_score AS estimated_total_score, ss.section_scores_json,
                       EXISTS(SELECT 1 FROM jlpt_attempt_answer_snapshot s WHERE s.attempt_id = h.id) AS review_available
                FROM jlpt_attempt_history h
                LEFT JOIN jlpt_attempt_score_snapshot ss ON ss.attempt_id = h.id
                WHERE h.user_id = ?
                ORDER BY h.submitted_at DESC, h.id DESC
                LIMIT ? OFFSET ?
                """, (rs, row) -> entry(rs), userId, safeSize + 1, offset);
        boolean hasMore = rows.size() > safeSize;
        if (hasMore) rows.removeLast();
        return new JlptHistoryPageResponse(rows, safePage, safeSize, hasMore);
    }

    @Transactional(readOnly = true)
    public JlptHistoryDetailResponse detail(Long userId, long historyId) {
        JlptHistoryEntryResponse attempt = jdbc.query("""
                SELECT h.id, h.exam_code, h.exam_title, h.jlpt_level, h.mode, h.part_code,
                       h.correct_answers, h.answered_questions, h.total_questions, h.score_percent, h.passed,
                       h.submitted_at, ss.total_score AS estimated_total_score, ss.section_scores_json,
                       EXISTS(SELECT 1 FROM jlpt_attempt_answer_snapshot s WHERE s.attempt_id = h.id) AS review_available
                FROM jlpt_attempt_history h
                LEFT JOIN jlpt_attempt_score_snapshot ss ON ss.attempt_id = h.id
                WHERE h.id = ? AND h.user_id = ?
                """, rs -> rs.next() ? entry(rs) : null, historyId, userId);
        if (attempt == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lần làm bài.");
        if (!attempt.reviewAvailable()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Lần làm bài này được lưu trước khi có chức năng xem lại chi tiết.");
        }
        return jdbc.queryForObject("""
                SELECT answers_json, sentence_orders_json
                FROM jlpt_attempt_answer_snapshot WHERE attempt_id = ?
                """, (rs, row) -> new JlptHistoryDetailResponse(attempt,
                        readJson(rs.getString("answers_json"), new TypeReference<Map<Long, Integer>>() {}),
                        readJson(rs.getString("sentence_orders_json"), new TypeReference<Map<Long, List<Integer>>>() {})),
                historyId);
    }

    private JlptHistoryEntryResponse findBySubmissionId(Long userId, String submissionId) {
        return jdbc.queryForObject("""
                SELECT h.id, h.exam_code, h.exam_title, h.jlpt_level, h.mode, h.part_code,
                       h.correct_answers, h.answered_questions, h.total_questions, h.score_percent, h.passed,
                       h.submitted_at, ss.total_score AS estimated_total_score, ss.section_scores_json,
                       EXISTS(SELECT 1 FROM jlpt_attempt_answer_snapshot s WHERE s.attempt_id = h.id) AS review_available
                FROM jlpt_attempt_history h
                LEFT JOIN jlpt_attempt_score_snapshot ss ON ss.attempt_id = h.id
                WHERE h.user_id = ? AND h.submission_id = ?
                """, (rs, row) -> entry(rs), userId, submissionId);
    }

    private Question question(ResultSet rs) throws SQLException {
        List<Integer> correctOrder = null;
        String rawOrder = rs.getString("correct_order");
        if (rawOrder != null && !rawOrder.isBlank()) {
            try { correctOrder = objectMapper.readValue(rawOrder, ORDER_TYPE); }
            catch (Exception exception) { throw new IllegalStateException("Invalid JLPT correct_order JSON", exception); }
        }
        int correctOptionValue = rs.getInt("correct_option");
        Integer correctOption = rs.wasNull() ? null : correctOptionValue;
        return new Question(rs.getLong("id"), rs.getString("part_code"), rs.getString("format_code"), correctOption, correctOrder);
    }

    private JlptHistoryEntryResponse entry(ResultSet rs) throws SQLException {
        Integer totalScore = rs.getObject("estimated_total_score") == null ? null : rs.getInt("estimated_total_score");
        String rawScores = rs.getString("section_scores_json");
        Map<String, Integer> sectionScores = rawScores == null ? Map.of()
                : readJson(rawScores, new TypeReference<Map<String, Integer>>() {});
        return new JlptHistoryEntryResponse(rs.getLong("id"), rs.getString("exam_code"),
                rs.getString("exam_title"), rs.getString("jlpt_level"), rs.getString("mode"),
                rs.getString("part_code"), rs.getInt("correct_answers"), rs.getInt("answered_questions"),
                rs.getInt("total_questions"), rs.getBigDecimal("score_percent"), totalScore, sectionScores,
                rs.getBoolean("passed"), rs.getTimestamp("submitted_at").toLocalDateTime(),
                rs.getString("part_code") == null, rs.getBoolean("review_available"));
    }

    private static boolean passes(String level, int total, Map<String, Integer> scores) {
        if (total < PASSING_SCORES.getOrDefault(level, 180)) return false;
        int listening = scores.getOrDefault("listening", 0);
        if ("N4".equals(level) || "N5".equals(level)) {
            return scores.getOrDefault("language_reading", 0) >= 38 && listening >= 19;
        }
        if ("N1".equals(level) || "N2".equals(level)) {
            return scores.getOrDefault("language_knowledge", 0) >= 19
                    && scores.getOrDefault("reading", 0) >= 19 && listening >= 19;
        }
        return scores.getOrDefault("vocabulary", 0) >= 19
                && scores.getOrDefault("grammar_reading", 0) >= 19
                && listening >= 19;
    }

    private static String scoringSection(String level, String requestedPart, Question question) {
        if (requestedPart != null) return requestedPart;
        if ("listening".equals(question.partCode())) return "listening";
        if ("N1".equals(level) || "N2".equals(level)) {
            if ("vocabulary".equals(question.partCode())) return "language_knowledge";
            return question.formatCode() != null && question.formatCode().toLowerCase().contains("reading")
                    ? "reading" : "language_knowledge";
        }
        if ("N4".equals(level) || "N5".equals(level)) return "language_reading";
        return question.partCode();
    }

    private static int sectionMaximum(String level, String section, boolean fullAttempt) {
        if (fullAttempt && ("N4".equals(level) || "N5".equals(level)) && "language_reading".equals(section)) return 120;
        return 60;
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Could not save JLPT answer snapshot", exception); }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (Exception exception) { throw new IllegalStateException("Invalid JLPT answer snapshot JSON", exception); }
    }

    private static boolean isCompleteOrder(List<Integer> order) {
        if (order == null || order.size() != 4 || order.stream().anyMatch(value -> value == null || value < 1 || value > 4)) return false;
        return new HashSet<>(order).size() == 4;
    }

    private static boolean isUuid(String value) {
        try { UUID.fromString(value); return true; }
        catch (RuntimeException exception) { return false; }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Question(long id, String partCode, String formatCode, Integer correctOption, List<Integer> correctOrder) {}

    private static final class PartScore {
        private int correct;
        private int total;
    }
}
